package com.atakmap.android.ideaplow.map

import android.util.Log
import com.atakmap.android.ideaplow.coverage.CoverageStore
import com.atakmap.android.ideaplow.coverage.Freshness
import com.atakmap.android.ideaplow.coverage.FreshnessModel
import com.atakmap.android.ideaplow.model.TreatSegment
import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Polyline
import com.atakmap.coremap.maps.coords.GeoPoint
import com.atakmap.coremap.maps.coords.GeoPointMetaData

/**
 * Renders [TreatSegment]s as polylines in the "IdeaPlow" MapGroup: stroke
 * width scaled from plow width, color from freshness. The shared recolor
 * tick re-classifies every segment periodically and removes expired ones
 * from the display (the store prunes them from persistence).
 */
class CoverageOverlay(
    private val mapView: MapView,
    private val coverageStore: CoverageStore,
    private val freshnessModel: FreshnessModel
) : CoverageStore.Listener {

    private var group: MapGroup? = null
    private val lines = HashMap<String, Polyline>()
    private val segmentEnd = HashMap<String, Long>()

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
        segmentEnd.clear()
        mapView.rootGroup.removeGroup(g)
        group = null
    }

    // ------------------------------------------------- store callbacks

    override fun onSegmentAdded(segment: TreatSegment, local: Boolean) {
        mapView.post { render(segment) }
    }

    override fun onSegmentsRemoved(ids: Collection<String>) {
        mapView.post {
            val g = group ?: return@post
            for (id in ids) {
                lines.remove(id)?.let { safeRemove(g, it) }
                segmentEnd.remove(id)
            }
        }
    }

    /** Shared recolor tick (call from the periodic UI timer). */
    fun recolorAll(nowMs: Long) {
        mapView.post {
            val g = group ?: return@post
            val expired = mutableListOf<String>()
            for ((id, line) in lines) {
                val end = segmentEnd[id] ?: continue
                val freshness = freshnessModel.classify(end, nowMs)
                if (freshness == Freshness.EXPIRED) {
                    expired.add(id)
                } else {
                    line.strokeColor = colorFor(freshness)
                }
            }
            for (id in expired) {
                lines.remove(id)?.let { safeRemove(g, it) }
                segmentEnd.remove(id)
            }
        }
    }

    // -------------------------------------------------------- rendering

    private fun render(segment: TreatSegment) {
        val g = group ?: return
        try {
            val freshness = freshnessModel.classify(segment.endTimeMs, System.currentTimeMillis())
            if (freshness == Freshness.EXPIRED) return

            lines.remove(segment.id)?.let { safeRemove(g, it) }

            val line = Polyline("$ITEM_UID_PREFIX${segment.id}")
            val pts = segment.points.map { p ->
                GeoPointMetaData.wrap(GeoPoint(p.lat, p.lon))
            }.toTypedArray()
            line.setPoints(pts)
            line.strokeColor = colorFor(freshness)
            line.strokeWeight = strokeWeightFor(segment.widthM)
            line.setMetaBoolean("addToObjList", false) // not a user-manageable item
            line.setMetaString("ideaplow.segment", segment.id)

            g.addItem(line)
            lines[segment.id] = line
            segmentEnd[segment.id] = segment.endTimeMs
        } catch (e: Exception) {
            Log.e(TAG, "failed rendering segment ${segment.id}", e)
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
        private const val TAG = "IdeaPlowCoverage"
        const val GROUP_NAME = "IdeaPlow"
        private const val ITEM_UID_PREFIX = "ideaplow-cov-"

        // ARGB stroke colors per freshness bucket.
        private const val COLOR_GREEN = 0xC02ECC40.toInt()
        private const val COLOR_YELLOW = 0xC0FFDC00.toInt()
        private const val COLOR_RED = 0xC0FF4136.toInt()

        fun colorFor(freshness: Freshness): Int = when (freshness) {
            Freshness.GREEN -> COLOR_GREEN
            Freshness.YELLOW -> COLOR_YELLOW
            Freshness.RED -> COLOR_RED
            Freshness.EXPIRED -> 0x00000000
        }

        /**
         * Screen stroke weight from physical plow width. True
         * meters-on-ground stroking needs an AbstractLayer (Phase 2); this
         * approximation keeps wide tow plows visually heavier.
         */
        fun strokeWeightFor(widthM: Double): Double =
            (widthM * 1.5).coerceIn(3.0, 12.0)
    }
}
