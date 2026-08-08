package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.RoutePriority
import com.atakmap.android.ideaplow.model.SpecialZone
import com.atakmap.android.ideaplow.model.TreatSegment
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per-priority cycle-time settings, minutes. A value <= 0 means "use the
 * default" so supervisors only override the classes they care about.
 */
data class CycleTimes(
    val defaultMinutes: Int = 45,
    val p1Minutes: Int = 0,
    val p2Minutes: Int = 0,
    val p3Minutes: Int = 0
) {
    fun forPriority(priority: RoutePriority): Int {
        val override = when (priority) {
            RoutePriority.P1 -> p1Minutes
            RoutePriority.P2 -> p2Minutes
            RoutePriority.P3 -> p3Minutes
            RoutePriority.DEFAULT -> 0
        }
        return if (override > 0) override else defaultMinutes
    }
}

/**
 * Resolves the effective cycle time for a location or segment: the
 * per-priority base, tightened by the strictest special zone (bridge / ramp
 * / hill / school) containing it. Pure so it is testable and identical on
 * every client.
 */
object CycleResolver {

    /** Effective cycle minutes at a point. */
    fun resolveMinutes(
        cycles: CycleTimes,
        priority: RoutePriority,
        zones: List<SpecialZone>,
        lat: Double,
        lon: Double
    ): Int {
        val base = cycles.forPriority(priority)
        val multiplier = zones
            .filter { it.contains(lat, lon) }
            .minOfOrNull { it.cycleMultiplier.coerceIn(0.05, 1.0) }
            ?: 1.0
        return max(1, (base * multiplier).roundToInt())
    }

    /**
     * Effective cycle minutes for a segment: the strictest (smallest) cycle
     * across its points, so a pass that clips a bridge zone inherits the
     * bridge's tighter revisit requirement.
     */
    fun resolveForSegment(
        cycles: CycleTimes,
        priority: RoutePriority,
        zones: List<SpecialZone>,
        segment: TreatSegment
    ): Int {
        val base = cycles.forPriority(priority)
        if (zones.isEmpty()) return max(1, base)
        var strictest = max(1, base)
        for (p in segment.points) {
            val resolved = resolveMinutes(cycles, priority, zones, p.lat, p.lon)
            if (resolved < strictest) strictest = resolved
        }
        return strictest
    }
}
