package com.atakmap.android.plowtak.demo

import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.coverage.GraphHopperGraph
import com.atakmap.android.plowtak.coverage.RoadSnapper
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Walks a GraphHopper (VNS) road pack edge-by-edge so demo trucks stay on
 * real roadways. Fail-open: if the pack is missing, [seed] returns null and
 * callers can fall back to random geodesic motion.
 */
class DemoRoadWalker(
    private val snapper: RoadSnapper,
    private val random: Random = Random()
) {
    private val graph: GraphHopperGraph get() = snapper.graph

    data class Pose(
        val lat: Double,
        val lon: Double,
        val headingDeg: Double,
        val edgeId: Int,
        /** Distance along the current edge polyline, meters. */
        val alongM: Double,
        /** +1 toward node B, -1 toward node A. */
        val direction: Int
    )

    /**
     * Place a walker on a road within [radiusM] of the anchor. Tries several
     * random offsets + snaps; returns null if no road is found.
     */
    fun seed(anchorLat: Double, anchorLon: Double, radiusM: Double): Pose? {
        for (attempt in 0 until SEED_ATTEMPTS) {
            val bearing = random.nextDouble() * 360.0
            val dist = random.nextDouble() * radiusM
            val (lat, lon) = offset(anchorLat, anchorLon, bearing, dist)
            val snap = snapper.snap(lat, lon, maxDistM = SEED_SNAP_M) ?: continue
            val poly = graph.edgeGeometry(snap.edgeId)
            if (poly.size < 4) continue
            val along = distanceAlongPolyline(poly, snap.lat, snap.lon)
            val dir = if (random.nextBoolean()) 1 else -1
            val heading = headingAt(poly, along, dir)
            return Pose(snap.lat, snap.lon, heading, snap.edgeId, along, dir)
        }
        return null
    }

    /** Advance [meters] along the road network; picks a new edge at junctions. */
    fun advance(pose: Pose, meters: Double): Pose {
        var edgeId = pose.edgeId
        var along = pose.alongM
        var dir = pose.direction
        var remaining = meters
        var hops = 0

        while (remaining > 0.1 && hops++ < MAX_HOPS) {
            val poly = graph.edgeGeometry(edgeId)
            if (poly.size < 4) break
            val edgeLen = polylineLengthM(poly)
            if (edgeLen < 1.0) {
                val next = chooseNext(edgeId, dir) ?: break
                edgeId = next.first
                dir = next.second
                along = startAlong(edgeId, dir)
                continue
            }

            val targetAlong = along + dir * remaining
            when {
                targetAlong in 0.0..edgeLen -> {
                    along = targetAlong
                    remaining = 0.0
                }
                dir > 0 -> {
                    remaining -= (edgeLen - along).coerceAtLeast(0.0)
                    val next = chooseNext(edgeId, dir) ?: run {
                        dir = -1
                        along = edgeLen
                        remaining = 0.0
                        null
                    } ?: break
                    edgeId = next.first
                    dir = next.second
                    along = startAlong(edgeId, dir)
                }
                else -> {
                    remaining -= along.coerceAtLeast(0.0)
                    val next = chooseNext(edgeId, dir) ?: run {
                        dir = 1
                        along = 0.0
                        remaining = 0.0
                        null
                    } ?: break
                    edgeId = next.first
                    dir = next.second
                    along = startAlong(edgeId, dir)
                }
            }
        }

        val poly = graph.edgeGeometry(edgeId)
        val edgeLen = if (poly.size >= 4) polylineLengthM(poly) else 0.0
        along = along.coerceIn(0.0, edgeLen.coerceAtLeast(0.0))
        val p = if (poly.size >= 4) pointAtDistance(poly, along)
        else doubleArrayOf(pose.lat, pose.lon)
        val heading = if (poly.size >= 4) headingAt(poly, along, dir) else pose.headingDeg
        return Pose(p[0], p[1], heading, edgeId, along, dir)
    }

    private fun startAlong(edgeId: Int, dir: Int): Double =
        if (dir > 0) 0.0 else polylineLengthM(graph.edgeGeometry(edgeId))

    /**
     * Leave the tower at the end of travel on [fromEdge]. Prefers a random
     * outgoing edge; ~12% of the time reverses (loading-lot / U-turn feel).
     */
    private fun chooseNext(fromEdge: Int, fromDir: Int): Pair<Int, Int>? {
        val node = if (fromDir > 0) graph.edgeNodeB(fromEdge) else graph.edgeNodeA(fromEdge)
        val edges = mutableListOf<Int>()
        graph.edgesOf(node, edges)
        val others = edges.filter { it != fromEdge }
        if (others.isEmpty()) return fromEdge to -fromDir
        if (random.nextDouble() < 0.12) return fromEdge to -fromDir
        val pick = others[random.nextInt(others.size)]
        val newDir = when (node) {
            graph.edgeNodeA(pick) -> 1
            graph.edgeNodeB(pick) -> -1
            else -> 1
        }
        return pick to newDir
    }

    companion object {
        private const val SEED_ATTEMPTS = 40
        private const val SEED_SNAP_M = 250.0
        private const val MAX_HOPS = 32

        fun openOrNull(packDir: File): DemoRoadWalker? {
            val snapper = RoadSnapper.openOrNull(packDir) ?: return null
            return DemoRoadWalker(snapper)
        }

        /**
         * Resolve a GraphHopper pack: explicit [preferredDir], else first
         * non-empty region under ATAK `tools/VNS/GH/`.
         */
        fun resolvePackDir(preferredDir: String, vnsGhRoot: File?): File? {
            if (preferredDir.isNotBlank()) {
                val f = File(preferredDir)
                if (looksLikeGhPack(f)) return f
            }
            val root = vnsGhRoot ?: return null
            if (!root.isDirectory) return null
            return root.listFiles()
                ?.filter { it.isDirectory && looksLikeGhPack(it) }
                ?.minByOrNull { it.name.lowercase() }
        }

        fun looksLikeGhPack(dir: File): Boolean =
            File(dir, "nodes").isFile &&
                File(dir, "edges").isFile &&
                File(dir, "geometry").isFile

        private fun offset(
            lat: Double, lon: Double, bearingDeg: Double, distM: Double
        ): Pair<Double, Double> {
            val br = Math.toRadians(bearingDeg)
            val dLat = (distM * cos(br)) / 111_320.0
            val dLon = (distM * sin(br)) /
                (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            return (lat + dLat) to (lon + dLon)
        }

        private fun polylineLengthM(poly: DoubleArray): Double {
            var sum = 0.0
            var i = 0
            while (i < poly.size - 2) {
                sum += GeoMath.distanceMeters(
                    poly[i], poly[i + 1], poly[i + 2], poly[i + 3]
                )
                i += 2
            }
            return sum
        }

        private fun distanceAlongPolyline(
            poly: DoubleArray, lat: Double, lon: Double
        ): Double {
            var bestAlong = 0.0
            var bestDist = Double.MAX_VALUE
            var walked = 0.0
            var i = 0
            while (i < poly.size - 2) {
                val r = GeoMath.closestPointOnSegment(
                    lat, lon, poly[i], poly[i + 1], poly[i + 2], poly[i + 3]
                )
                val segLen = GeoMath.distanceMeters(
                    poly[i], poly[i + 1], poly[i + 2], poly[i + 3]
                )
                if (r[2] < bestDist) {
                    bestDist = r[2]
                    val t = if (segLen < 1e-3) 0.0
                    else GeoMath.distanceMeters(poly[i], poly[i + 1], r[0], r[1])
                    bestAlong = walked + t
                }
                walked += segLen
                i += 2
            }
            return bestAlong
        }

        private fun pointAtDistance(poly: DoubleArray, alongM: Double): DoubleArray {
            var remaining = alongM.coerceAtLeast(0.0)
            var i = 0
            while (i < poly.size - 2) {
                val segLen = GeoMath.distanceMeters(
                    poly[i], poly[i + 1], poly[i + 2], poly[i + 3]
                )
                if (remaining <= segLen || i >= poly.size - 4) {
                    val t = if (segLen < 1e-6) 0.0 else (remaining / segLen).coerceIn(0.0, 1.0)
                    return doubleArrayOf(
                        poly[i] + t * (poly[i + 2] - poly[i]),
                        poly[i + 1] + t * (poly[i + 3] - poly[i + 1])
                    )
                }
                remaining -= segLen
                i += 2
            }
            return doubleArrayOf(poly[poly.size - 2], poly[poly.size - 1])
        }

        private fun headingAt(poly: DoubleArray, alongM: Double, dir: Int): Double {
            val a = pointAtDistance(poly, alongM)
            val b = pointAtDistance(poly, alongM + dir * 8.0)
            return GeoMath.bearingDeg(a[0], a[1], b[0], b[1])
        }
    }
}
