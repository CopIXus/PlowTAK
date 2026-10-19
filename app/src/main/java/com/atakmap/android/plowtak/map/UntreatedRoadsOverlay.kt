package com.atakmap.android.plowtak.map

import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Polyline
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.coverage.Freshness
import com.atakmap.android.plowtak.coverage.FreshnessModel
import com.atakmap.android.plowtak.gis.GhRoadClass
import com.atakmap.android.plowtak.gis.GraphHopperRoadSource
import com.atakmap.android.plowtak.gis.OverlayRoadSource
import com.atakmap.coremap.maps.coords.GeoPoint
import com.atakmap.coremap.maps.coords.GeoPointMetaData

/**
 * Dim-red hint for plow-class roads in the current view that have no fresh
 * pass. Viewport limited and hidden when zoomed out, because a state pack
 * has millions of edges.
 */
class UntreatedRoadsOverlay(
    private val mapView: MapView,
    private val coverageStore: CoverageStore,
    private val freshnessModel: FreshnessModel,
    private val source: () -> OverlayRoadSource
) {
    private var group: MapGroup? = null
    private val lines = ArrayList<Polyline>()
    var lastUntreatedMiles: Double = 0.0
        private set

    fun start() {
        if (group != null) return
        val root = mapView.rootGroup
        group = root.findMapGroup(GROUP) ?: root.addGroup(GROUP)
    }

    fun dispose() {
        val g = group ?: return
        clear(g)
        mapView.rootGroup.removeGroup(g)
        group = null
    }

    fun refresh(nowMs: Long) {
        val g = group ?: return
        val gh = source().pack as? GraphHopperRoadSource ?: run {
            clear(g)
            return
        }
        val center = mapView.centerPoint?.get()
        if (center == null || !center.isValid) return
        val resolution = try {
            mapView.mapResolution
        } catch (_: Throwable) {
            100.0
        }
        if (resolution > STREET_RESOLUTION_M) {
            clear(g)
            return
        }
        val radius = (resolution * 400).coerceIn(200.0, 2_500.0)
        val edges = gh.snapper.edgesAround(center.latitude, center.longitude, radius, MAX_LINES)
        clear(g)
        var meters = 0.0
        var drawn = 0
        for (edge in edges) {
            if (drawn >= MAX_LINES) break
            val flags = gh.snapper.graph.edgeFlags(edge)
            if (!GhRoadClass.isPlowClass(GhRoadClass.roadClass(flags))) continue
            val poly = gh.snapper.graph.edgeGeometry(edge)
            if (poly.size < 4) continue
            val pair = (poly.size / 4) * 2
            val lat = poly[pair]
            val lon = poly[pair + 1]
            if (freshNear(lat, lon, nowMs)) continue
            val line = Polyline("plowtak-untreated-$edge")
            val pts = Array(poly.size / 2) { i ->
                GeoPointMetaData.wrap(GeoPoint(poly[i * 2], poly[i * 2 + 1]))
            }
            line.setPoints(pts)
            line.strokeColor = COLOR
            line.strokeWeight = 2.0
            line.setMetaBoolean("addToObjList", false)
            g.addItem(line)
            lines.add(line)
            drawn++
            meters += edgeLength(poly)
        }
        lastUntreatedMiles = meters / 1609.34
    }

    private fun freshNear(lat: Double, lon: Double, nowMs: Long): Boolean {
        val near = coverageStore.nearby(lat, lon, 25.0)
        return near.any { freshnessModel.classify(it.endTimeMs, nowMs) == Freshness.GREEN }
    }

    private fun clear(g: MapGroup) {
        lines.forEach { runCatching { g.removeItem(it) } }
        lines.clear()
        lastUntreatedMiles = 0.0
    }

    private fun edgeLength(poly: DoubleArray): Double {
        var len = 0.0
        var i = 2
        while (i + 1 < poly.size) {
            val dLat = (poly[i] - poly[i - 2]) * 111_320.0
            val dLon = (poly[i + 1] - poly[i - 1]) * 111_320.0 * 0.8
            len += kotlin.math.sqrt(dLat * dLat + dLon * dLon)
            i += 2
        }
        return len
    }

    companion object {
        private const val GROUP = "PlowTAK Untreated"
        private const val COLOR = 0x60FF4136
        private const val MAX_LINES = 3000
        /** Coarser than this (about county scale) and the hint is hidden. */
        private const val STREET_RESOLUTION_M = 15.0
    }
}
