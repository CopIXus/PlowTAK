package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.TreatSegment

/** Which side of the road corridor a pass treated (right-hand traffic). */
enum class RoadSide(val wireName: String) {
    RIGHT("right"),
    LEFT("left"),
    UNKNOWN("unknown");

    companion object {
        fun fromWireName(name: String?): RoadSide? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** Direction-pairing state of a segment relative to the fleet's coverage. */
enum class DirectionStatus {
    /** An opposite-direction pass over the same corridor is also fresh. */
    PAIRED,
    /** Only this direction is treated — the other side still needs a pass. */
    ONE_WAY_ONLY,
    /** No usable heading on the segment; pairing cannot be evaluated. */
    UNKNOWN
}

/**
 * Direction and side-of-road reasoning over treated segments, without road
 * GIS: headings are binned so two passes over the same corridor ~180 deg
 * apart count as distinct coverage (northbound done does NOT mean southbound
 * done), and the treated side of the centerline is estimated from heading
 * under right-hand traffic.
 *
 * Caveat documented in ops guide: on one-way roads (ramps, divided
 * carriageways) ONE_WAY_ONLY is the *normal* state; lane GIS in Phase 3
 * suppresses those false gaps.
 */
object DirectionModel {

    /** 16 bins of 22.5 deg — fine enough to separate diverging diagonals. */
    const val DEFAULT_BIN_COUNT = 16

    /** Headings within this of exactly-opposite count as an opposing pass. */
    const val DEFAULT_OPPOSITE_TOLERANCE_DEG = 60.0

    /** Max lateral offset for two passes to be "the same road corridor". */
    const val DEFAULT_CORRIDOR_WIDTH_M = 30.0

    /** Probe points sampled along a segment when looking for opposing passes. */
    private const val PROBE_COUNT = 5

    /** Bearing bin index (0-based) for a heading; -1 when heading is NaN. */
    fun bearingBin(headingDeg: Double, binCount: Int = DEFAULT_BIN_COUNT): Int {
        if (headingDeg.isNaN()) return -1
        val h = ((headingDeg % 360.0) + 360.0) % 360.0
        val binWidth = 360.0 / binCount
        // Center bins on their heading (bin 0 spans [-binWidth/2, +binWidth/2)).
        return (((h + binWidth / 2) / binWidth).toInt()) % binCount
    }

    /** True when two bins are opposite (within +-1 bin of 180 deg apart). */
    fun binsOpposite(a: Int, b: Int, binCount: Int = DEFAULT_BIN_COUNT): Boolean {
        if (a < 0 || b < 0) return false
        val half = binCount / 2
        val diff = ((a - b) % binCount + binCount) % binCount
        return diff in (half - 1)..(half + 1)
    }

    fun isOppositeHeading(
        h1: Double,
        h2: Double,
        toleranceDeg: Double = DEFAULT_OPPOSITE_TOLERANCE_DEG
    ): Boolean {
        if (h1.isNaN() || h2.isNaN()) return false
        return GeoMath.angleDiffDeg(h1, h2) >= 180.0 - toleranceDeg
    }

    /**
     * Side of the corridor centerline treated by a pass with this heading,
     * assuming right-hand traffic: the corridor axis is the heading folded
     * to [0, 180); traveling along the axis paints the RIGHT side, against
     * it the LEFT.
     */
    fun sideOfRoad(headingDeg: Double): RoadSide {
        if (headingDeg.isNaN()) return RoadSide.UNKNOWN
        val h = ((headingDeg % 360.0) + 360.0) % 360.0
        return if (h < 180.0) RoadSide.RIGHT else RoadSide.LEFT
    }

    /**
     * Local heading at point [index]: the stored GPS heading when present,
     * else the bearing to the next (or from the previous) point.
     */
    fun headingAt(segment: TreatSegment, index: Int): Double {
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

    /**
     * Classify whether [segment] has a *fresh* opposite-direction companion
     * pass over the same corridor among [candidates] (typically pulled from
     * the segment spatial index).
     *
     * Probes up to [PROBE_COUNT] points along the segment; the majority of
     * probes with a usable heading must see an opposing pass within
     * [corridorWidthM] for PAIRED.
     */
    fun directionStatus(
        segment: TreatSegment,
        candidates: List<TreatSegment>,
        nowMs: Long,
        freshWithinMs: Long,
        corridorWidthM: Double = DEFAULT_CORRIDOR_WIDTH_M,
        toleranceDeg: Double = DEFAULT_OPPOSITE_TOLERANCE_DEG
    ): DirectionStatus {
        val fresh = candidates.filter {
            it.id != segment.id && nowMs - it.endTimeMs <= freshWithinMs
        }
        if (segment.headingDeg.isNaN() &&
            segment.points.all { it.headingDeg.isNaN() } && segment.points.size < 2
        ) return DirectionStatus.UNKNOWN

        val probeIndices = probeIndices(segment.points.size)
        var usable = 0
        var matched = 0
        for (i in probeIndices) {
            val probeHeading = headingAt(segment, i)
            if (probeHeading.isNaN()) continue
            usable++
            val p = segment.points[i]
            if (hasOpposingPassAt(
                    p.lat, p.lon, probeHeading, fresh, corridorWidthM, toleranceDeg
                )
            ) matched++
        }
        return when {
            usable == 0 -> DirectionStatus.UNKNOWN
            matched * 2 > usable -> DirectionStatus.PAIRED
            else -> DirectionStatus.ONE_WAY_ONLY
        }
    }

    /**
     * True when any candidate has a sub-segment within [corridorWidthM] of
     * the probe point whose bearing opposes [probeHeading].
     */
    fun hasOpposingPassAt(
        lat: Double,
        lon: Double,
        probeHeading: Double,
        candidates: List<TreatSegment>,
        corridorWidthM: Double = DEFAULT_CORRIDOR_WIDTH_M,
        toleranceDeg: Double = DEFAULT_OPPOSITE_TOLERANCE_DEG
    ): Boolean {
        for (cand in candidates) {
            val pts = cand.points
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val lateral = GeoMath.crossTrackMeters(lat, lon, a.lat, a.lon, b.lat, b.lon)
                if (lateral > corridorWidthM) continue
                val candBearing =
                    if (!a.headingDeg.isNaN()) a.headingDeg
                    else GeoMath.bearingDeg(a.lat, a.lon, b.lat, b.lon)
                if (isOppositeHeading(probeHeading, candBearing, toleranceDeg)) return true
            }
        }
        return false
    }

    private fun probeIndices(size: Int): List<Int> {
        if (size <= PROBE_COUNT) return (0 until size).toList()
        return (0 until PROBE_COUNT).map { it * (size - 1) / (PROBE_COUNT - 1) }
    }
}
