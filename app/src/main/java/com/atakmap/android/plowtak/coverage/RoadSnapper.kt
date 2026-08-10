package com.atakmap.android.plowtak.coverage

import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Optional road snapping against an offline GraphHopper 1.0 pack (the same
 * packs VNS uses). Snapping is a *cosmetic* correction for swath rendering:
 * it is OFF by default, and every failure path returns null so the caller
 * falls back to raw GPS — a bad map pack must never block coverage
 * recording.
 *
 * Design: rather than porting GraphHopper's location index (a packed
 * quadtree, v3), we build our own flat grid over tower nodes at load time —
 * one pass over the `nodes` file, two int arrays (CSR layout), no
 * allocation per query. A snap query walks outward ring by ring until it
 * finds tower nodes, collects their edges via the linkA/linkB chains, and
 * projects the query point onto each edge polyline (towers + pillars).
 *
 * Known limitation (documented in ops guide): an edge is only found via its
 * *endpoint towers*, so a point next to a very long edge whose both towers
 * are beyond [MAX_TOWER_SEARCH_M] will not snap. Rural interstates between
 * exits can exceed this; those points simply stay raw GPS.
 */
class RoadSnapper private constructor(
    /** Shared with callers that need edge walking (demo fleet, etc.). */
    val graph: GraphHopperGraph,
    private val cellSizeDeg: Double
) {

    data class Snap(
        val lat: Double,
        val lon: Double,
        /** Meters from the raw point to the snapped point. */
        val distanceM: Double,
        val edgeId: Int
    )

    // CSR grid over tower nodes: cellStart[c]..cellStart[c+1] indexes
    // cellNodes, which holds node ids sorted by cell.
    private val latCells: Int
    private val lonCells: Int
    private val cellStart: IntArray
    private val cellNodes: IntArray

    init {
        latCells = max(1, ((graph.maxLat - graph.minLat) / cellSizeDeg).toInt() + 1)
        lonCells = max(1, ((graph.maxLon - graph.minLon) / cellSizeDeg).toInt() + 1)
        val cellCount = latCells * lonCells
        val counts = IntArray(cellCount + 1)
        for (n in 0 until graph.nodeCount) {
            counts[cellOf(graph.nodeLat(n), graph.nodeLon(n)) + 1]++
        }
        for (c in 1..cellCount) counts[c] += counts[c - 1]
        cellStart = counts
        cellNodes = IntArray(graph.nodeCount)
        val cursor = cellStart.copyOf()
        for (n in 0 until graph.nodeCount) {
            val c = cellOf(graph.nodeLat(n), graph.nodeLon(n))
            cellNodes[cursor[c]++] = n
        }
    }

    private fun cellOf(lat: Double, lon: Double): Int {
        val latC = (((lat - graph.minLat) / cellSizeDeg).toInt()).coerceIn(0, latCells - 1)
        val lonC = (((lon - graph.minLon) / cellSizeDeg).toInt()).coerceIn(0, lonCells - 1)
        return latC * lonCells + lonC
    }

    /**
     * Snap a GPS point to the nearest road edge. Returns null when the
     * point is outside the pack bounds, no road is within [maxDistM], or
     * no tower node exists within [MAX_TOWER_SEARCH_M].
     */
    fun snap(lat: Double, lon: Double, maxDistM: Double = DEFAULT_MAX_SNAP_M): Snap? {
        if (lat < graph.minLat - 0.01 || lat > graph.maxLat + 0.01 ||
            lon < graph.minLon - 0.01 || lon > graph.maxLon + 0.01
        ) return null

        val metersPerDegLat = 111_320.0
        val metersPerDegLon = max(1.0, metersPerDegLat * cos(Math.toRadians(lat)))
        val centerLatC = ((lat - graph.minLat) / cellSizeDeg).toInt()
        val centerLonC = ((lon - graph.minLon) / cellSizeDeg).toInt()

        val seenEdges = HashSet<Int>()
        var best: Snap? = null

        // Expand square rings of cells. Once a ring yields a good-enough
        // snap, scan one extra ring (a nearer edge can hang off a farther
        // tower) and stop.
        val cellM = cellSizeDeg * metersPerDegLat
        val maxRing = (MAX_TOWER_SEARCH_M / cellM).toInt() + 1
        var ringsAfterHit = -1
        for (ring in 0..maxRing) {
            if (ringsAfterHit >= 0 && ring - ringsAfterHit > 1) break
            var foundNodes = false
            forEachRingCell(centerLatC, centerLonC, ring) { cell ->
                var i = cellStart[cell]
                val end = cellStart[cell + 1]
                while (i < end) {
                    foundNodes = true
                    val node = cellNodes[i]
                    val candidate = bestSnapAroundNode(node, lat, lon, seenEdges)
                    if (candidate != null &&
                        (best == null || candidate.distanceM < best!!.distanceM)
                    ) best = candidate
                    i++
                }
            }
            if (foundNodes && best != null && best!!.distanceM <= maxDistM &&
                ringsAfterHit < 0
            ) ringsAfterHit = ring
        }
        val result = best ?: return null
        return if (result.distanceM <= maxDistM) result else null
    }

    /** Project the point onto every edge of [node] not already evaluated. */
    private fun bestSnapAroundNode(
        node: Int,
        lat: Double,
        lon: Double,
        seenEdges: MutableSet<Int>
    ): Snap? {
        val edges = mutableListOf<Int>()
        graph.edgesOf(node, edges)
        var best: Snap? = null
        for (edge in edges) {
            if (!seenEdges.add(edge)) continue
            val poly = graph.edgeGeometry(edge)
            var i = 0
            while (i < poly.size - 2) {
                val r = GeoMath.closestPointOnSegment(
                    lat, lon, poly[i], poly[i + 1], poly[i + 2], poly[i + 3]
                )
                if (best == null || r[2] < best!!.distanceM) {
                    best = Snap(r[0], r[1], r[2], edge)
                }
                i += 2
            }
        }
        return best
    }

    private inline fun forEachRingCell(
        centerLatC: Int, centerLonC: Int, ring: Int, action: (Int) -> Unit
    ) {
        val latLo = centerLatC - ring
        val latHi = centerLatC + ring
        val lonLo = centerLonC - ring
        val lonHi = centerLonC + ring
        for (latC in latLo..latHi) {
            if (latC < 0 || latC >= latCells) continue
            for (lonC in lonLo..lonHi) {
                if (lonC < 0 || lonC >= lonCells) continue
                // Ring border only — the interior was covered by smaller rings.
                if (ring > 0 && latC != latLo && latC != latHi &&
                    lonC != lonLo && lonC != lonHi
                ) continue
                action(latC * lonCells + lonC)
            }
        }
    }

    companion object {

        /** Beyond this the fix is probably a parking lot / private drive. */
        const val DEFAULT_MAX_SNAP_M = 40.0

        /** Give up hunting for tower nodes past this radius. */
        const val MAX_TOWER_SEARCH_M = 4_000.0

        /** ~550 m grid cells — a handful of towers per cell in town. */
        const val DEFAULT_CELL_SIZE_DEG = 0.005

        /**
         * Open a pack directory and build the tower grid (one pass over the
         * nodes file — run on a background thread in the app). Null on any
         * failure: snapping fails open to raw GPS.
         */
        fun openOrNull(dir: File, cellSizeDeg: Double = DEFAULT_CELL_SIZE_DEG): RoadSnapper? {
            val graph = GraphHopperGraph.open(dir) ?: return null
            return try {
                RoadSnapper(graph, cellSizeDeg)
            } catch (e: Exception) {
                null
            }
        }
    }
}
