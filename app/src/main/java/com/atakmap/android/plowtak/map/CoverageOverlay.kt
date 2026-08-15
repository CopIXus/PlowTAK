package com.atakmap.android.plowtak.map

import android.util.Log
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.coverage.CoverageStyle
import com.atakmap.android.plowtak.coverage.DirectionStatus
import com.atakmap.android.plowtak.coverage.Freshness
import com.atakmap.android.plowtak.coverage.FreshnessModel
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Polyline
import com.atakmap.coremap.maps.coords.GeoPoint
import com.atakmap.coremap.maps.coords.GeoPointMetaData

/**
 * Renders [TreatSegment]s as polylines in the "PlowTAK" MapGroup: stroke
 * width scaled from plow width, color from freshness / spread material.
 */
class CoverageOverlay(
    private val mapView: MapView,
    private val coverageStore: CoverageStore,
    private val freshnessModel: FreshnessModel
) : CoverageStore.Listener {

    /** Effective cycle minutes for a segment; null = global model cycle. */
    @Volatile
    var cycleMinutesHook: ((TreatSegment) -> Int)? = null

    /** Direction-pairing classifier; null disables the half-treated style. */
    @Volatile
    var directionHook: ((TreatSegment, Long) -> DirectionStatus)? = null

    private var group: MapGroup? = null
    private val lines = HashMap<String, Polyline>()
    private val renderedSegments = HashMap<String, TreatSegment>()

    fun start() {
        if (group != null) return
        val root = mapView.rootGroup
        group = root.findMapGroup(GROUP_NAME) ?: root.addGroup(GROUP_NAME)
        coverageStore.addListener(this)
        coverageStore.all().forEach { render(it) }
    }

    fun dispose() {
        coverageStore.removeListener(this)
        val g = group ?: return
        lines.values.forEach { safeRemove(g, it) }
        lines.clear()
        renderedSegments.clear()
        mapView.rootGroup.removeGroup(g)
        group = null
    }

    override fun onSegmentAdded(segment: TreatSegment, local: Boolean) {
        mapView.post { render(segment) }
    }

    override fun onSegmentsRemoved(ids: Collection<String>) {
        mapView.post {
            val g = group ?: return@post
            for (id in ids) {
                lines.remove(id)?.let { safeRemove(g, it) }
                renderedSegments.remove(id)
            }
        }
    }

    fun recolorAll(nowMs: Long) {
        mapView.post {
            val g = group ?: return@post
            val expired = mutableListOf<String>()
            for ((id, line) in lines) {
                val segment = renderedSegments[id] ?: continue
                val freshness = classify(segment, nowMs)
                if (freshness == Freshness.EXPIRED) {
                    // Only remove when retention > 0 yields EXPIRED.
                    expired.add(id)
                } else {
                    line.strokeColor = CoverageStyle.colorFor(freshness, segment)
                    applyDirectionStyle(line, segment, nowMs)
                }
            }
            for (id in expired) {
                lines.remove(id)?.let { safeRemove(g, it) }
                renderedSegments.remove(id)
            }
        }
    }

    private fun classify(segment: TreatSegment, nowMs: Long): Freshness {
        val cycle = cycleMinutesHook?.invoke(segment)
        return if (cycle != null) freshnessModel.classify(segment.endTimeMs, nowMs, cycle)
        else freshnessModel.classify(segment.endTimeMs, nowMs)
    }

    private fun render(segment: TreatSegment) {
        val g = group ?: return
        try {
            val now = System.currentTimeMillis()
            val freshness = classify(segment, now)
            if (freshness == Freshness.EXPIRED) return

            lines.remove(segment.id)?.let { safeRemove(g, it) }

            val line = Polyline("$ITEM_UID_PREFIX${segment.id}")
            val pts = segment.points.map { p ->
                GeoPointMetaData.wrap(GeoPoint(p.lat, p.lon))
            }.toTypedArray()
            line.setPoints(pts)
            line.strokeColor = CoverageStyle.colorFor(freshness, segment)
            line.strokeWeight = CoverageStyle.strokeWeightFor(segment)
            line.setMetaBoolean("addToObjList", false)
            line.setMetaString("plowtak.segment", segment.id)
            applyDirectionStyle(line, segment, now)

            g.addItem(line)
            lines[segment.id] = line
            renderedSegments[segment.id] = segment
        } catch (e: Exception) {
            Log.e(TAG, "failed rendering segment ${segment.id}", e)
        }
    }

    private fun applyDirectionStyle(line: Polyline, segment: TreatSegment, nowMs: Long) {
        try {
            val spreadOnly = segment.material == MaterialMode.SALT
            val half = directionHook?.invoke(segment, nowMs) == DirectionStatus.ONE_WAY_ONLY
            line.basicLineStyle =
                if (spreadOnly || half) Polyline.BASIC_LINE_STYLE_DASHED
                else Polyline.BASIC_LINE_STYLE_SOLID
        } catch (_: Throwable) {
            // Style is cosmetic.
        }
    }

    private fun safeRemove(g: MapGroup, line: Polyline) {
        try {
            g.removeItem(line)
        } catch (e: Exception) {
            Log.w(TAG, "failed removing polyline", e)
        }
    }

    companion object {
        private const val TAG = "PlowTakCoverage"
        const val GROUP_NAME = "PlowTAK"
        private const val ITEM_UID_PREFIX = "plowtak-cov-"

        /** @deprecated Prefer [CoverageStyle.colorFor]. */
        fun colorFor(freshness: Freshness, segment: TreatSegment? = null): Int =
            CoverageStyle.colorFor(freshness, segment)

        /** @deprecated Prefer [CoverageStyle.strokeWeightFor]. */
        fun strokeWeightFor(segment: TreatSegment): Double =
            CoverageStyle.strokeWeightFor(segment)

        fun strokeWeightFor(widthM: Double): Double =
            CoverageStyle.strokeWeightFor(widthM)
    }
}
