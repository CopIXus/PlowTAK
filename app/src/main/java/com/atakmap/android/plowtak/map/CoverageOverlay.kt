package com.atakmap.android.plowtak.map

import android.util.Log
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.coverage.DirectionStatus
import com.atakmap.android.plowtak.coverage.Freshness
import com.atakmap.android.plowtak.coverage.FreshnessModel
import com.atakmap.android.plowtak.model.Material
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
                    expired.add(id)
                } else {
                    line.strokeColor = colorFor(freshness, segment)
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
            line.strokeColor = colorFor(freshness, segment)
            line.strokeWeight = strokeWeightFor(segment)
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

        private const val COLOR_GREEN = 0xC02ECC40.toInt()
        private const val COLOR_YELLOW = 0xC0FFDC00.toInt()
        private const val COLOR_RED = 0xC0FF4136.toInt()

        fun colorFor(freshness: Freshness, segment: TreatSegment? = null): Int {
            val base = when (freshness) {
                Freshness.GREEN -> COLOR_GREEN
                Freshness.YELLOW -> COLOR_YELLOW
                Freshness.RED -> COLOR_RED
                Freshness.EXPIRED -> return 0x00000000
            }
            if (segment?.material == MaterialMode.SALT) {
                val tint = when (segment.spreadMaterial) {
                    Material.SAND -> 0xC0C2B280.toInt()
                    Material.GRAVEL -> 0xC0888888.toInt()
                    Material.BRINE, Material.PREWET -> 0xC04FC3F7.toInt()
                    else -> 0xC064B5F6.toInt()
                }
                return when (freshness) {
                    Freshness.GREEN -> tint
                    Freshness.YELLOW -> 0xC0FFB74D.toInt()
                    Freshness.RED -> COLOR_RED
                    Freshness.EXPIRED -> 0
                }
            }
            return base
        }

        fun strokeWeightFor(segment: TreatSegment): Double {
            val base = (segment.widthM * 1.5).coerceIn(3.0, 12.0)
            return if (segment.material == MaterialMode.SALT) (base * 0.55).coerceIn(2.0, 7.0)
            else base
        }

        fun strokeWeightFor(widthM: Double): Double =
            (widthM * 1.5).coerceIn(3.0, 12.0)
    }
}
