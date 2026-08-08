package com.atakmap.android.ideaplow.gis

import com.atakmap.android.ideaplow.coverage.GeoMath
import com.atakmap.android.ideaplow.model.TreatSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lane-aware coverage reasoning when the imported road network carries lane
 * counts. Builds on the Phase 2 direction model (heading vs road bearing)
 * plus the signed lateral offset from [RoadNetwork.nearest]:
 *
 *  - a pass is FORWARD when its heading aligns with the digitized direction
 *    (within 90 deg), REVERSE otherwise;
 *  - the lane index within that direction comes from the magnitude of the
 *    lateral offset in lane widths (right-hand traffic: forward lanes sit
 *    right of the centerline, reverse lanes left);
 *  - a pass wider than one lane (wing/tow) covers adjacent lanes.
 *
 * All heuristics are documented best-effort: GPS lateral accuracy limits
 * lane discrimination, so estimates saturate at the road's lane count and a
 * road is "fully treated" only when every lane slot has a fresh pass.
 */
object LaneModel {

    /** Standard US lane width; agencies can be off a foot without harm. */
    const val DEFAULT_LANE_WIDTH_M = 3.5

    /** Travel direction relative to the road's digitized direction. */
    enum class TravelDirection { FORWARD, REVERSE }

    /** A lane slot on a road: direction + 0-based index from centerline. */
    data class LaneSlot(val direction: TravelDirection, val index: Int)

    /** Lane estimate for one pass over a road. */
    data class LaneEstimate(
        val road: Road,
        val direction: TravelDirection,
        /** Lane slots this pass is estimated to have covered (>= 1). */
        val lanes: Set<LaneSlot>
    )

    /** Per-road lane coverage rollup. */
    data class RoadLaneCoverage(
        val road: Road,
        /** Distinct lane slots with a fresh pass, capped at [Road.lanes]. */
        val lanesTreated: Int,
        /** lanesTreated / lanes; 0 when the road has no fresh pass. */
        val fraction: Double
    ) {
        val fullyTreated: Boolean get() = lanesTreated >= road.lanes && road.lanes > 0
        val partiallyTreated: Boolean get() = lanesTreated in 1 until road.lanes
    }

    /** Network-wide lane-mile metrics for the supervisor panel. */
    data class LaneMetrics(
        /** Sum of length x lanes over all lane-attributed roads, miles. */
        val totalLaneMiles: Double,
        /** Lane-miles with a fresh pass (per-road lanesTreated x length). */
        val treatedLaneMiles: Double,
        /** Roads with at least one fresh pass but not all lanes done. */
        val roadsPartial: Int,
        /** Roads with every lane slot fresh. */
        val roadsFull: Int
    ) {
        val fraction: Double
            get() = if (totalLaneMiles <= 0.0) 0.0 else treatedLaneMiles / totalLaneMiles
    }

    // ------------------------------------------------------------ matching

    /**
     * Match a treated segment to its road: probes up to [probes] points
     * along the centerline, requires the majority of probed points to agree
     * on the same road within [maxDistM] AND the pass heading to be roughly
     * parallel to the road (within [maxSkewDeg] of either direction) so
     * cross streets don't steal the match.
     */
    fun matchSegment(
        network: RoadNetwork,
        segment: TreatSegment,
        maxDistM: Double = RoadNetwork.DEFAULT_MATCH_DIST_M,
        maxSkewDeg: Double = 45.0,
        probes: Int = 5
    ): RoadMatch? {
        if (network.isEmpty()) return null
        val pts = segment.points
        val indices = probeIndices(pts.size, probes)

        val votes = HashMap<String, MutableList<RoadMatch>>()
        for (i in indices) {
            val p = pts[i]
            val match = network.nearest(p.lat, p.lon, maxDistM) ?: continue
            val heading = headingAt(segment, i)
            if (!heading.isNaN()) {
                val skew = GeoMath.angleDiffDeg(heading, match.roadBearingDeg)
                // Parallel either way: aligned or opposing traffic.
                if (min(skew, 180.0 - skew) > maxSkewDeg) continue
            }
            votes.getOrPut(match.road.id) { mutableListOf() }.add(match)
        }
        val winner = votes.values.maxByOrNull { it.size } ?: return null
        if (winner.size * 2 <= indices.size) return null // no majority
        // Representative match: the median-distance probe of the winner.
        return winner.sortedBy { it.distanceM }[winner.size / 2]
    }

    // ------------------------------------------------------- lane estimate

    /**
     * Estimated travel direction for a pass with [headingDeg] on a matched
     * road. One-way roads are always FORWARD (wrong-way GPS noise must not
     * invent a phantom reverse side).
     */
    fun travelDirection(headingDeg: Double, match: RoadMatch): TravelDirection {
        if (match.road.oneway) return TravelDirection.FORWARD
        if (headingDeg.isNaN()) return TravelDirection.FORWARD
        return if (GeoMath.angleDiffDeg(headingDeg, match.roadBearingDeg) <= 90.0)
            TravelDirection.FORWARD else TravelDirection.REVERSE
    }

    /**
     * Lane slots covered by one pass. Index 0 is the lane nearest the
     * centerline for that direction; a pass [passWidthM] wide covers
     * `round(width / laneWidth)` adjacent lanes (min 1), clamped to the
     * direction's lane count.
     */
    fun estimateLanes(
        segment: TreatSegment,
        match: RoadMatch,
        laneWidthM: Double = DEFAULT_LANE_WIDTH_M
    ): LaneEstimate? {
        val road = match.road
        if (road.lanes <= 0) return null
        val direction = travelDirection(segment.headingDeg, match)
        val dirLanes = max(1, road.lanesPerDirection)

        // Distance from centerline toward this direction's curb.
        val offset = abs(match.lateralOffsetM)
        var first = ((offset / laneWidthM) - 0.5).roundToInt().coerceIn(0, dirLanes - 1)

        val covered = max(1, (segment.widthM / laneWidthM).roundToInt())
        // Wide passes extend toward the curb; pull back if we'd overflow.
        if (first + covered > dirLanes) first = max(0, dirLanes - covered)

        val slots = (first until min(dirLanes, first + covered))
            .map { LaneSlot(direction, it) }
            .toSet()
        return LaneEstimate(road, direction, slots)
    }

    // ------------------------------------------------------------ coverage

    /**
     * Per-road lane coverage over [segments] whose pass is still fresh at
     * [nowMs] (ended within [freshWithinMs]). Roads without lane attributes
     * are excluded — width-band rendering remains their truth.
     */
    fun roadLaneCoverage(
        network: RoadNetwork,
        segments: List<TreatSegment>,
        nowMs: Long,
        freshWithinMs: Long,
        laneWidthM: Double = DEFAULT_LANE_WIDTH_M
    ): Map<String, RoadLaneCoverage> {
        val slotsByRoad = HashMap<String, MutableSet<LaneSlot>>()
        val roadsById = HashMap<String, Road>()
        for (seg in segments) {
            if (nowMs - seg.endTimeMs > freshWithinMs) continue
            val match = matchSegment(network, seg) ?: continue
            val estimate = estimateLanes(seg, match, laneWidthM) ?: continue
            roadsById[match.road.id] = match.road
            slotsByRoad.getOrPut(match.road.id) { mutableSetOf() }.addAll(estimate.lanes)
        }
        return slotsByRoad.mapValues { (roadId, slots) ->
            val road = roadsById.getValue(roadId)
            val treated = min(road.lanes, slots.size)
            RoadLaneCoverage(
                road = road,
                lanesTreated = treated,
                fraction = if (road.lanes > 0) treated.toDouble() / road.lanes else 0.0
            )
        }
    }

    /**
     * Network-wide lane-mile rollup: how many lane-miles exist in the
     * attributed network vs how many have a fresh pass. Complements the
     * plain centerline-mile metric that has no lane truth.
     */
    fun laneMetrics(
        network: RoadNetwork,
        segments: List<TreatSegment>,
        nowMs: Long,
        freshWithinMs: Long,
        laneWidthM: Double = DEFAULT_LANE_WIDTH_M
    ): LaneMetrics {
        val attributed = network.roads.filter { it.lanes > 0 }
        val totalLaneM = attributed.sumOf { it.lengthM * it.lanes }
        val coverage = roadLaneCoverage(network, segments, nowMs, freshWithinMs, laneWidthM)

        var treatedLaneM = 0.0
        var partial = 0
        var full = 0
        for (c in coverage.values) {
            treatedLaneM += c.road.lengthM * c.lanesTreated
            if (c.fullyTreated) full++ else if (c.partiallyTreated) partial++
        }
        return LaneMetrics(
            totalLaneMiles = totalLaneM / METERS_PER_MILE,
            treatedLaneMiles = treatedLaneM / METERS_PER_MILE,
            roadsPartial = partial,
            roadsFull = full
        )
    }

    // ------------------------------------------------------------ helpers

    private const val METERS_PER_MILE = 1609.344

    private fun probeIndices(size: Int, probes: Int): List<Int> {
        if (size <= probes) return (0 until size).toList()
        return (0 until probes).map { it * (size - 1) / (probes - 1) }
    }

    /** Stored heading at a point, else bearing to its neighbor. */
    private fun headingAt(segment: TreatSegment, index: Int): Double {
        val pts = segment.points
        val p = pts[index]
        if (!p.headingDeg.isNaN()) return p.headingDeg
        return when {
            index < pts.size - 1 ->
                GeoMath.bearingDeg(p.lat, p.lon, pts[index + 1].lat, pts[index + 1].lon)
            index > 0 ->
                GeoMath.bearingDeg(pts[index - 1].lat, pts[index - 1].lon, p.lat, p.lon)
            else -> Double.NaN
        }
    }
}
