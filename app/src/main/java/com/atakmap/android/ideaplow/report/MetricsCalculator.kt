package com.atakmap.android.ideaplow.report

import com.atakmap.android.ideaplow.coverage.CycleResolver
import com.atakmap.android.ideaplow.coverage.CycleTimes
import com.atakmap.android.ideaplow.coverage.GeoMath
import com.atakmap.android.ideaplow.model.PlowVehicle
import com.atakmap.android.ideaplow.model.RoutePriority
import com.atakmap.android.ideaplow.model.SpecialZone
import com.atakmap.android.ideaplow.model.TreatSegment

/**
 * Live supervisor metrics over the coverage picture. Pure functions over
 * store snapshots — call on the shared timer, render in the supervisor
 * panel, reuse in the post-storm export summary.
 */
object MetricsCalculator {

    const val METERS_PER_MILE = 1609.344

    data class StormMetrics(
        /** Centerline miles treated in the window (all trucks). */
        val laneMilesTreated: Double,
        /** Lane-miles per hour over the window (0 when window is empty). */
        val laneMilesPerHour: Double,
        /** Fraction (0–1) of retained coverage still within its cycle time. */
        val coverageWithinCycle: Double,
        /** Reload counts per truck: callsign -> count (from fleet PLI). */
        val reloadsByTruck: Map<String, Int>,
        val segmentCount: Int,
        val activeTruckCount: Int
    )

    /** Centerline length of one segment in meters. */
    fun segmentLengthM(segment: TreatSegment): Double {
        var total = 0.0
        val pts = segment.points
        for (i in 0 until pts.size - 1) {
            total += GeoMath.distanceMeters(
                pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon
            )
        }
        return total
    }

    /** Total centerline miles across segments whose pass ended in the window. */
    fun laneMiles(segments: List<TreatSegment>, sinceMs: Long, untilMs: Long): Double =
        segments
            .filter { it.endTimeMs in sinceMs..untilMs }
            .sumOf { segmentLengthM(it) } / METERS_PER_MILE

    /**
     * Fraction of retained coverage (by length) whose age is still inside
     * its resolved cycle time. 1.0 when there is no coverage — "nothing
     * overdue", not "nothing done"; pair with [StormMetrics.segmentCount].
     */
    fun coverageWithinCycle(
        segments: List<TreatSegment>,
        cycles: CycleTimes,
        zones: List<SpecialZone>,
        nowMs: Long,
        priority: RoutePriority = RoutePriority.DEFAULT
    ): Double {
        var totalM = 0.0
        var freshM = 0.0
        for (seg in segments) {
            val lengthM = segmentLengthM(seg)
            totalM += lengthM
            val cycleMs = CycleResolver.resolveForSegment(cycles, priority, zones, seg) * 60_000L
            if (nowMs - seg.endTimeMs <= cycleMs) freshM += lengthM
        }
        return if (totalM <= 0.0) 1.0 else freshM / totalM
    }

    /**
     * One-call rollup for the supervisor panel. [windowMs] is the trailing
     * window for the lane-miles-per-hour rate (default: last hour).
     */
    fun calculate(
        segments: List<TreatSegment>,
        vehicles: List<PlowVehicle>,
        cycles: CycleTimes,
        zones: List<SpecialZone>,
        nowMs: Long,
        windowMs: Long = 3_600_000L
    ): StormMetrics {
        val sinceMs = nowMs - windowMs
        val milesInWindow = laneMiles(segments, sinceMs, nowMs)
        val hours = windowMs / 3_600_000.0

        val reloads = vehicles
            .filter { it.reloadCount > 0 }
            .associate { it.callsign to it.reloadCount }

        return StormMetrics(
            laneMilesTreated = segments.sumOf { segmentLengthM(it) } / METERS_PER_MILE,
            laneMilesPerHour = if (hours > 0.0) milesInWindow / hours else 0.0,
            coverageWithinCycle = coverageWithinCycle(segments, cycles, zones, nowMs),
            reloadsByTruck = reloads,
            segmentCount = segments.size,
            activeTruckCount = vehicles.count { it.stormId.isNotEmpty() }
        )
    }
}
