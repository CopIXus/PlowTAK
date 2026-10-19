package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import kotlin.math.max

/**
 * Order-independent trim: the newer same-direction pass over a corridor
 * wins, and the earlier pass loses the overlapped portion. Arrival order
 * does not matter — [TreatSegment.endTimeMs] decides — so CoT and Data Sync
 * converge on the same picture.
 */
object SegmentSupersede {

    /** Same-direction test: headings within this count as the same way. */
    const val SAME_DIRECTION_DEG = 60.0

    /** Road-level slack: consumer GPS cannot tell lanes apart. */
    const val DEFAULT_LANE_SLACK_M = 6.0

    /** Strict-lanes slack for RTK / good receivers. */
    const val STRICT_LANE_SLACK_M = 2.0

    /** Remnants shorter than this are dropped. */
    const val MIN_REMNANT_M = 15.0

    /** Covered fraction at or above this drops the whole older segment. */
    const val DROP_COVERED_FRACTION = 0.90

    /**
     * Outcome of one pair. [droppedId] leaves the store; [remnants] replace
     * it (empty when the older pass is fully covered).
     */
    data class Result(val droppedId: String, val remnants: List<TreatSegment>)

    /**
     * Compare [a] and [b]. Returns null when neither is trimmed. The segment
     * with the earlier [TreatSegment.endTimeMs] is the one that can lose
     * geometry; ties break on start time, then id, so every device agrees.
     */
    fun apply(a: TreatSegment, b: TreatSegment, laneSlackM: Double): Result? {
        if (a.id == b.id) return null
        val (winner, loser) = ordered(a, b)
        if (!canSupersede(winner.material, loser.material)) return null
        val corridor = max(winner.widthM, loser.widthM) / 2.0 + laneSlackM
        return trim(loser, winner, corridor)
    }

    /** A plow pass supersedes anything; salt supersedes only salt. */
    fun canSupersede(winner: MaterialMode, loser: MaterialMode): Boolean = when (winner) {
        MaterialMode.PLOW_ONLY, MaterialMode.PLOW_AND_SALT -> true
        MaterialMode.SALT -> loser == MaterialMode.SALT
        MaterialMode.NONE -> false
    }

    private fun ordered(a: TreatSegment, b: TreatSegment): Pair<TreatSegment, TreatSegment> {
        val cmp = compareValues(a.endTimeMs, b.endTimeMs)
        val earlier = when {
            cmp < 0 -> a
            cmp > 0 -> b
            a.startTimeMs != b.startTimeMs ->
                if (a.startTimeMs < b.startTimeMs) a else b
            else -> if (a.id <= b.id) a else b
        }
        return if (earlier === a) b to a else a to b
    }

    private fun trim(older: TreatSegment, newer: TreatSegment, corridorM: Double): Result? {
        val n = older.points.size
        val covered = BooleanArray(n)
        for (i in 0 until n) {
            covered[i] = coveredBy(older, i, newer, corridorM)
        }
        var total = 0.0
        var coveredLen = 0.0
        for (i in 1 until n) {
            val d = GeoMath.distanceMeters(
                older.points[i - 1].lat, older.points[i - 1].lon,
                older.points[i].lat, older.points[i].lon
            )
            total += d
            when {
                covered[i - 1] && covered[i] -> coveredLen += d
                covered[i - 1] || covered[i] -> coveredLen += d / 2.0
            }
        }
        if (total <= 0.0 || coveredLen < MIN_REMNANT_M) return null
        if (coveredLen / total >= DROP_COVERED_FRACTION) {
            return Result(older.id, emptyList())
        }

        val remnants = ArrayList<TreatSegment>()
        var seq = 1
        var i = 0
        while (i < n) {
            if (covered[i]) {
                i++
                continue
            }
            var j = i
            while (j < n && !covered[j]) j++
            val start = if (i > 0) i - 1 else i
            val end = if (j < n) j + 1 else j
            val slice = older.points.subList(start, end)
            if (slice.size >= 2 && lengthM(slice) >= MIN_REMNANT_M) {
                remnants.add(remnant(older, slice, seq))
                seq++
            }
            i = j
        }
        if (remnants.isEmpty()) return Result(older.id, emptyList())
        return Result(older.id, remnants)
    }

    private fun coveredBy(
        older: TreatSegment,
        index: Int,
        newer: TreatSegment,
        corridorM: Double
    ): Boolean {
        val heading = DirectionModel.headingAt(older, index)
        if (heading.isNaN()) return false
        val p = older.points[index]
        var bestDist = Double.MAX_VALUE
        var bestHeading = Double.NaN
        val pts = newer.points
        for (k in 0 until pts.size - 1) {
            val r = GeoMath.closestPointOnSegment(
                p.lat, p.lon, pts[k].lat, pts[k].lon, pts[k + 1].lat, pts[k + 1].lon
            )
            if (r[2] < bestDist) {
                bestDist = r[2]
                bestHeading = legHeading(newer, k)
            }
        }
        if (bestDist > corridorM || bestHeading.isNaN()) return false
        return GeoMath.angleDiffDeg(heading, bestHeading) <= SAME_DIRECTION_DEG
    }

    /** Heading of the newer pass along leg [k]: stored GPS heading, else the leg bearing. */
    private fun legHeading(segment: TreatSegment, k: Int): Double {
        val stored = DirectionModel.headingAt(segment, k)
        if (!stored.isNaN()) return stored
        val a = segment.points[k]
        val b = segment.points[k + 1]
        return GeoMath.bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    private fun remnant(older: TreatSegment, slice: List<TrackPoint>, seq: Int): TreatSegment {
        val base = older.id.substringBefore('~')
        return older.copy(
            id = "$base~$seq",
            points = slice.toList(),
            startTimeMs = slice.first().timeMs,
            endTimeMs = slice.last().timeMs
        )
    }

    private fun lengthM(points: List<TrackPoint>): Double {
        var len = 0.0
        for (i in 1 until points.size) {
            len += GeoMath.distanceMeters(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon
            )
        }
        return len
    }
}
