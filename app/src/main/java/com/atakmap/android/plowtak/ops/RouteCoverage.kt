package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.coverage.SegmentIndex
import com.atakmap.android.plowtak.gis.RoadNetwork
import com.atakmap.android.plowtak.gis.RoadPoint
import com.atakmap.android.plowtak.model.TreatSegment

/** Result of measuring treated coverage against an assigned route. */
data class RouteCoverageResult(
    val routeLengthM: Double,
    val coveredLengthM: Double
) {
    /** 0..1 fraction of the route's road length treated this storm. */
    val fraction: Double
        get() = if (routeLengthM <= 0.0) 0.0
        else (coveredLengthM / routeLengthM).coerceIn(0.0, 1.0)

    val percent: Int get() = (fraction * 100.0).toInt()
}

/**
 * "% of assigned route covered this storm" for the fleet list and driver
 * panel. Walks the route's centerlines at a fixed sampling step and counts
 * a sample as covered when any treated segment (optionally filtered to the
 * active storm) passes within that segment's half-width plus a GPS
 * tolerance. Sampling keeps the cost bounded and matches how the overlay's
 * recolor tick already thinks about proximity.
 *
 * Pure Kotlin; callers hand in the [SegmentIndex] that CoverageStore
 * maintains. Route geometry comes from the imported [RoadNetwork] for GIS
 * routes, or from the map layer as a raw polyline for drawn ATAK routes.
 */
object RouteCoverage {

    /** Sampling interval along the route centerlines. */
    const val STEP_M = 25.0

    /** GPS + map slop added to each segment's half swath width. */
    const val TOLERANCE_M = 8.0

    /** Coverage of a named GIS route. */
    fun forRoute(
        network: RoadNetwork,
        routeId: String,
        index: SegmentIndex,
        stormId: String? = null
    ): RouteCoverageResult = forPolylines(
        network.roadsForRoute(routeId).map { road -> road.points },
        index, stormId
    )

    /** Coverage of an arbitrary polyline (drawn ATAK route). */
    fun forPolylines(
        polylines: List<List<RoadPoint>>,
        index: SegmentIndex,
        stormId: String? = null
    ): RouteCoverageResult {
        var totalM = 0.0
        var coveredM = 0.0
        for (line in polylines) {
            for (i in 0 until line.size - 1) {
                val a = line[i]
                val b = line[i + 1]
                val legM = GeoMath.distanceMeters(a.lat, a.lon, b.lat, b.lon)
                if (legM <= 0.0) continue
                totalM += legM
                // Sample at least once per leg (midpoint of a short leg).
                val steps = maxOf(1, (legM / STEP_M).toInt())
                val stepLenM = legM / steps
                for (s in 0 until steps) {
                    val t = (s + 0.5) / steps
                    val lat = a.lat + (b.lat - a.lat) * t
                    val lon = a.lon + (b.lon - a.lon) * t
                    if (isTreated(lat, lon, index, stormId)) coveredM += stepLenM
                }
            }
        }
        return RouteCoverageResult(routeLengthM = totalM, coveredLengthM = coveredM)
    }

    private fun isTreated(
        lat: Double,
        lon: Double,
        index: SegmentIndex,
        stormId: String?
    ): Boolean {
        val candidates = index.nearby(lat, lon, MAX_SWATH_QUERY_M)
        for (seg in candidates) {
            if (stormId != null && seg.stormId != stormId) continue
            val reach = seg.widthM / 2.0 + TOLERANCE_M
            if (distanceToSegment(lat, lon, seg) <= reach) return true
        }
        return false
    }

    private fun distanceToSegment(lat: Double, lon: Double, seg: TreatSegment): Double {
        var best = Double.MAX_VALUE
        val pts = seg.points
        for (i in 0 until pts.size - 1) {
            val d = GeoMath.closestPointOnSegment(
                lat, lon, pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon
            )[2]
            if (d < best) best = d
        }
        return best
    }

    /** Generous bbox radius for the coarse index query. */
    private const val MAX_SWATH_QUERY_M = 40.0
}
