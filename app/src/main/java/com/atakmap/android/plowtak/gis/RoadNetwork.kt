package com.atakmap.android.plowtak.gis

import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.model.RoutePriority
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/** One vertex of a road centerline. */
data class RoadPoint(val lat: Double, val lon: Double)

/**
 * One attributed road centerline from agency GIS. Attributes follow the
 * agreed GeoJSON property names (`lanes`, `priority`, `route_id`, `oneway`,
 * `name`); anything missing gets a neutral default so partially attributed
 * data still imports.
 */
data class Road(
    /** Stable id: feature id / property, else "road-<index>" at import. */
    val id: String,
    val name: String = "",
    /** Total lane count both directions; 0 = unknown (no lane-aware logic). */
    val lanes: Int = 0,
    /** Agency priority; null = not attributed (manual default applies). */
    val priority: RoutePriority? = null,
    /** Named route/beat this centerline belongs to; empty = none. */
    val routeId: String = "",
    val oneway: Boolean = false,
    val points: List<RoadPoint>
) {
    init {
        require(points.size >= 2) { "Road requires at least 2 points" }
    }

    /** Centerline length in meters. */
    val lengthM: Double by lazy {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += GeoMath.distanceMeters(
                points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon
            )
        }
        total
    }

    /**
     * Directional lane count: a one-way road has all lanes in its digitized
     * direction; a two-way road splits evenly (odd counts round up so a
     * 3-lane road still models 2 one way). 0 when lanes are unknown.
     */
    val lanesPerDirection: Int
        get() = when {
            lanes <= 0 -> 0
            oneway -> lanes
            else -> (lanes + 1) / 2
        }
}

/**
 * Result of matching a position to the nearest attributed road.
 *
 * [lateralOffsetM] is signed relative to the road's digitized direction:
 * positive = right of the centerline looking along increasing point index.
 */
data class RoadMatch(
    val road: Road,
    /** Distance from query point to the closest point on the centerline. */
    val distanceM: Double,
    /** Bearing of the matched centerline sub-segment (digitized direction). */
    val roadBearingDeg: Double,
    /** Signed cross-track offset; + = right of digitized direction. */
    val lateralOffsetM: Double
)

/**
 * In-memory attributed road network with a grid spatial index (same
 * cell-binning approach as `coverage/SegmentIndex`) so positions and treated
 * segments can be matched to the nearest attributed road quickly.
 *
 * Built once per import by [RoadNetworkImporter]; queried on the recolor
 * tick and at segment close. Immutable after construction — safe to share
 * across threads.
 */
class RoadNetwork(
    val roads: List<Road>,
    private val cellSizeDeg: Double = DEFAULT_CELL_SIZE_DEG
) {

    // cell key -> packed (roadIndex shl 32 | pointIndex) sub-segment refs
    private val cells = HashMap<Long, MutableList<Long>>()

    init {
        for ((roadIdx, road) in roads.withIndex()) {
            for (i in 0 until road.points.size - 1) {
                val a = road.points[i]
                val b = road.points[i + 1]
                val ref = (roadIdx.toLong() shl 32) or i.toLong()
                // Register the sub-segment in every cell its bbox covers.
                for (latC in cellIndex(minOf(a.lat, b.lat))..cellIndex(maxOf(a.lat, b.lat))) {
                    for (lonC in cellIndex(minOf(a.lon, b.lon))..cellIndex(maxOf(a.lon, b.lon))) {
                        cells.getOrPut(cellKey(latC, lonC)) { mutableListOf() }.add(ref)
                    }
                }
            }
        }
    }

    val size: Int get() = roads.size

    fun isEmpty(): Boolean = roads.isEmpty()

    /** Distinct non-empty route ids, sorted, for the assignment picker. */
    fun routeIds(): List<String> =
        roads.mapNotNull { it.routeId.ifEmpty { null } }.distinct().sorted()

    fun roadsForRoute(routeId: String): List<Road> =
        roads.filter { it.routeId == routeId }

    /** Total attributed length of a named route in meters. */
    fun routeLengthM(routeId: String): Double =
        roadsForRoute(routeId).sumOf { it.lengthM }

    /**
     * Nearest attributed road within [maxDistM] of a point, or null. Scans
     * grid cells in expanding rings; precision is the real point-to-segment
     * distance, not the coarse cell hit.
     */
    fun nearest(lat: Double, lon: Double, maxDistM: Double = DEFAULT_MATCH_DIST_M): RoadMatch? {
        val metersPerDegLat = METERS_PER_DEG_LAT
        val cellM = cellSizeDeg * metersPerDegLat
        val maxRing = (maxDistM / cellM).toInt() + 1
        val centerLatC = cellIndex(lat)
        val centerLonC = cellIndex(lon)

        var best: RoadMatch? = null
        val seen = HashSet<Long>()
        for (ring in 0..maxRing) {
            forEachRingCell(centerLatC, centerLonC, ring) { key ->
                val refs = cells[key] ?: return@forEachRingCell
                for (ref in refs) {
                    if (!seen.add(ref)) continue
                    val candidate = matchSubSegment(ref, lat, lon)
                    if (candidate != null &&
                        (best == null || candidate.distanceM < best!!.distanceM)
                    ) best = candidate
                }
            }
            // One extra ring after a hit catches near edges hanging off
            // farther cells; then stop.
            val b = best
            if (b != null && b.distanceM <= ring * cellM) break
        }
        val result = best ?: return null
        return if (result.distanceM <= maxDistM) result else null
    }

    /** Priority at a point when the GIS attributes one; null otherwise. */
    fun priorityAt(lat: Double, lon: Double, maxDistM: Double = DEFAULT_MATCH_DIST_M): RoutePriority? =
        nearest(lat, lon, maxDistM)?.road?.priority

    // ------------------------------------------------------------ internal

    private fun matchSubSegment(ref: Long, lat: Double, lon: Double): RoadMatch? {
        val roadIdx = (ref ushr 32).toInt()
        val ptIdx = (ref and 0xFFFFFFFFL).toInt()
        val road = roads.getOrNull(roadIdx) ?: return null
        val a = road.points.getOrNull(ptIdx) ?: return null
        val b = road.points.getOrNull(ptIdx + 1) ?: return null

        val closest = GeoMath.closestPointOnSegment(lat, lon, a.lat, a.lon, b.lat, b.lon)
        val bearing = GeoMath.bearingDeg(a.lat, a.lon, b.lat, b.lon)

        // Signed side via local-projection cross product (a as origin).
        val cosLat = cos(Math.toRadians(a.lat))
        val px = Math.toRadians(lon - a.lon) * cosLat * GeoMath.EARTH_RADIUS_M
        val py = Math.toRadians(lat - a.lat) * GeoMath.EARTH_RADIUS_M
        val bx = Math.toRadians(b.lon - a.lon) * cosLat * GeoMath.EARTH_RADIUS_M
        val by = Math.toRadians(b.lat - a.lat) * GeoMath.EARTH_RADIUS_M
        // cross > 0 -> point left of a->b in an east/north frame.
        val cross = bx * py - by * px
        val side = if (cross > 0) -1.0 else 1.0 // + = right of travel

        return RoadMatch(
            road = road,
            distanceM = closest[2],
            roadBearingDeg = bearing,
            lateralOffsetM = side * closest[2]
        )
    }

    private inline fun forEachRingCell(
        centerLatC: Int, centerLonC: Int, ring: Int, action: (Long) -> Unit
    ) {
        val latLo = centerLatC - ring
        val latHi = centerLatC + ring
        val lonLo = centerLonC - ring
        val lonHi = centerLonC + ring
        for (latC in latLo..latHi) {
            for (lonC in lonLo..lonHi) {
                if (ring > 0 && latC != latLo && latC != latHi &&
                    lonC != lonLo && lonC != lonHi
                ) continue
                action(cellKey(latC, lonC))
            }
        }
    }

    private fun cellIndex(deg: Double): Int = floor(deg / cellSizeDeg).toInt()

    private fun cellKey(latC: Int, lonC: Int): Long =
        (latC.toLong() shl 32) or (lonC.toLong() and 0xFFFFFFFFL)

    companion object {
        /** ~550 m cells, matching SegmentIndex. */
        const val DEFAULT_CELL_SIZE_DEG = 0.005

        /** Beyond this a fix probably isn't on an attributed road. */
        const val DEFAULT_MATCH_DIST_M = 30.0

        private const val METERS_PER_DEG_LAT = 111_320.0

        val EMPTY = RoadNetwork(emptyList())
    }
}
