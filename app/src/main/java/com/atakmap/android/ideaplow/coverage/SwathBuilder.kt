package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.Material
import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TrackPoint
import com.atakmap.android.ideaplow.model.TreatSegment

/**
 * Turns a stream of GPS samples into [TreatSegment]s while the treating rule
 * holds. Framework-free: the caller decides *whether* a sample is treating
 * (via CapabilityRules + the equipment provider) and feeds samples in; this
 * class handles point thinning, segment breaking, and simplification.
 *
 * Thinning happens twice:
 *  1. On ingest — samples closer than [Config.minPointSpacingM] to the last
 *     kept point are dropped (GPS jitter while stopped at a light).
 *  2. On segment close — a Douglas-Peucker-lite pass with
 *     [Config.simplifyToleranceM] removes collinear points. DP keeps a subset
 *     of original points, so per-point timestamps/headings survive.
 */
class SwathBuilder(
    private val config: Config = Config(),
    private val onSegment: (TreatSegment) -> Unit
) {

    data class Config(
        /** Ignore samples closer than this to the previously kept point. */
        val minPointSpacingM: Double = 5.0,
        /** A time gap longer than this breaks the segment (tunnel, GPS loss). */
        val maxGapMs: Long = 30_000L,
        /** A jump longer than this breaks the segment (teleport / bad fix). */
        val maxJumpM: Double = 500.0,
        /** Douglas-Peucker tolerance applied when a segment closes. */
        val simplifyToleranceM: Double = 2.0,
        /** Close and emit when a segment grows this large (keeps CoT compact). */
        val maxPointsPerSegment: Int = 200
    )

    /** Identity/context stamped onto every produced segment. */
    data class Context(
        val vehicleUid: String,
        val callsign: String,
        val stormId: String,
        val operatorId: String
    )

    private var context: Context = Context("", "", "", "")
    private val buffer = mutableListOf<TrackPoint>()
    private var currentMaterial: MaterialMode = MaterialMode.NONE
    private var currentWidthM: Double = 0.0
    private var currentSpreadMaterial: Material? = null

    /** Update identity/context; a change mid-pass breaks the segment. */
    fun setContext(ctx: Context) {
        if (ctx != context) flush()
        context = ctx
    }

    /**
     * Feed one GPS sample. [treating] is the already-evaluated treating rule;
     * false samples close any open segment.
     */
    fun onSample(
        lat: Double,
        lon: Double,
        headingDeg: Double,
        timeMs: Long,
        treating: Boolean,
        material: MaterialMode,
        widthM: Double,
        spreadMaterial: Material? = null
    ) {
        if (!treating) {
            flush()
            return
        }

        val last = buffer.lastOrNull()
        if (last != null) {
            val gapMs = timeMs - last.timeMs
            val jumpM = GeoMath.distanceMeters(last.lat, last.lon, lat, lon)
            if (gapMs > config.maxGapMs || gapMs < 0 || jumpM > config.maxJumpM ||
                material != currentMaterial || widthM != currentWidthM ||
                spreadMaterial != currentSpreadMaterial
            ) {
                flush()
            }
        }

        if (buffer.isEmpty()) {
            currentMaterial = material
            currentWidthM = widthM
            currentSpreadMaterial = spreadMaterial
            buffer.add(TrackPoint(lat, lon, timeMs, headingDeg))
            return
        }

        val kept = buffer.last()
        if (GeoMath.distanceMeters(kept.lat, kept.lon, lat, lon) < config.minPointSpacingM) {
            return // jitter — thin it out
        }

        buffer.add(TrackPoint(lat, lon, timeMs, headingDeg))

        if (buffer.size >= config.maxPointsPerSegment) {
            // Emit and chain: the last point seeds the next segment so the
            // rendered swath stays continuous.
            val tail = buffer.last()
            flush()
            buffer.add(tail)
        }
    }

    /** Close and emit the open segment, if it has enough points to matter. */
    fun flush() {
        if (buffer.size >= 2) {
            val simplified = simplify(buffer.toList(), config.simplifyToleranceM)
            if (simplified.size >= 2) {
                onSegment(
                    TreatSegment(
                        id = TreatSegment.makeId(context.vehicleUid, simplified.first().timeMs),
                        vehicleUid = context.vehicleUid,
                        callsign = context.callsign,
                        stormId = context.stormId,
                        operatorId = context.operatorId,
                        material = currentMaterial,
                        widthM = currentWidthM,
                        points = simplified,
                        startTimeMs = simplified.first().timeMs,
                        endTimeMs = simplified.last().timeMs,
                        spreadMaterial = currentSpreadMaterial
                    )
                )
            }
        }
        buffer.clear()
    }

    companion object {
        /**
         * Douglas-Peucker-lite (iterative). Keeps endpoints and any point
         * farther than [toleranceM] from the chord.
         */
        fun simplify(points: List<TrackPoint>, toleranceM: Double): List<TrackPoint> {
            if (points.size <= 2 || toleranceM <= 0.0) return points

            val keep = BooleanArray(points.size)
            keep[0] = true
            keep[points.size - 1] = true

            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to points.size - 1)

            while (stack.isNotEmpty()) {
                val (first, last) = stack.removeLast()
                var maxDist = 0.0
                var index = -1
                for (i in first + 1 until last) {
                    val d = GeoMath.crossTrackMeters(
                        points[i].lat, points[i].lon,
                        points[first].lat, points[first].lon,
                        points[last].lat, points[last].lon
                    )
                    if (d > maxDist) {
                        maxDist = d
                        index = i
                    }
                }
                if (index >= 0 && maxDist > toleranceM) {
                    keep[index] = true
                    stack.addLast(first to index)
                    stack.addLast(index to last)
                }
            }

            return points.filterIndexed { i, _ -> keep[i] }
        }
    }
}
