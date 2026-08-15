package com.atakmap.android.plowtak.model

import com.atakmap.android.plowtak.coverage.CycleTimes
import com.atakmap.android.plowtak.coverage.StormDefaults

/**
 * A storm ops session that trucks may join for coverage tagging and Data Sync.
 *
 * Multiple agencies may broadcast concurrent storms; each device explicitly
 * joins one reporting storm (see [com.atakmap.android.plowtak.ops.StormSessionManager]).
 *
 * Plow-track color timers are **storm-level**: every joined device follows
 * [storm-config.json] / CoT, not each tablet's Settings.
 */
data class StormSession(
    /** Stable id, e.g. "2026-01-15-1736951234". */
    val id: String,
    val startTimeMs: Long,
    /** 0 while the storm is active. */
    val endTimeMs: Long = 0L,
    /** Callsign of the unit that started it. */
    val startedBy: String = "",
    /** Human designator shown in pickers, e.g. "I-81 North overnight". */
    val label: String = "",
    /** Agency / org tag, e.g. "VDOT" or "City of Roanoke". */
    val agency: String = "",
    /**
     * Optional Data Sync mission name override. Empty means the default
     * `plowtak-coverage-{id}` naming.
     */
    val missionName: String = "",
    /** CoT channel the Data Sync mission belongs to (access control). */
    val channel: String = "",
    /** Minutes after plow that track stays green. */
    val greenUntilMinutes: Int = StormDefaults.GREEN_UNTIL_MIN,
    /** Minutes after plow that yellow phase is labeled through (paint stays yellow until red). */
    val yellowUntilMinutes: Int = StormDefaults.YELLOW_UNTIL_MIN,
    /**
     * Minutes after plow when track goes red (needs plow again).
     * Wire name remains `cycleMinutes` in storm-config / CoT for compatibility.
     * P1/P2/P3 override this red-after only.
     */
    val cycleMinutes: Int = StormDefaults.RED_AFTER_MIN,
    /** Per-priority red-after override minutes; 0 = use [cycleMinutes]. */
    val cycleP1Minutes: Int = 0,
    val cycleP2Minutes: Int = 0,
    val cycleP3Minutes: Int = 0,
    /**
     * Hours after which overdue coverage is cleared from the map / store.
     * **0 = never clear** (lines stay red after red-after). Shared via
     * storm-config.json.
     */
    val coverageRetentionHours: Double = DEFAULT_COVERAGE_RETENTION_HOURS,
    /**
     * How long road-condition reports remain in Data Sync before they are
     * dropped from the mission upload (and locally). Default 2 hours.
     */
    val roadConditionTtlMinutes: Int = DEFAULT_ROAD_CONDITION_TTL_MINUTES
) {
    companion object {
        const val DEFAULT_ROAD_CONDITION_TTL_MINUTES = 120
        /** New storms default to 8h remove-after; 0 = keep red forever. */
        const val DEFAULT_COVERAGE_RETENTION_HOURS = StormDefaults.RETENTION_HOURS

        fun sanitizeTimers(
            green: Int,
            yellow: Int,
            red: Int,
            retentionHours: Double
        ): Quad {
            var g = green.coerceIn(1, 24 * 60)
            var y = yellow.coerceIn(1, 24 * 60)
            var r = red.coerceIn(5, 24 * 60)
            if (y < g) y = g
            if (r < y) r = y
            var retain = retentionHours.coerceIn(0.0, 72.0)
            if (retain > 0 && retain < r / 60.0) {
                retain = (r / 60.0).coerceAtLeast(0.1)
            }
            return Quad(g, y, r, retain)
        }

        data class Quad(
            val green: Int,
            val yellow: Int,
            val red: Int,
            val retentionHours: Double
        )
    }

    val isActive: Boolean get() = endTimeMs == 0L

    /** Red-after minutes (alias for [cycleMinutes]). */
    val redAfterMinutes: Int get() = cycleMinutes

    fun cycleTimes(): CycleTimes = CycleTimes(
        defaultMinutes = cycleMinutes,
        p1Minutes = cycleP1Minutes,
        p2Minutes = cycleP2Minutes,
        p3Minutes = cycleP3Minutes
    )

    fun sanitized(): StormSession {
        val t = sanitizeTimers(
            greenUntilMinutes, yellowUntilMinutes, cycleMinutes, coverageRetentionHours
        )
        return copy(
            greenUntilMinutes = t.green,
            yellowUntilMinutes = t.yellow,
            cycleMinutes = t.red,
            coverageRetentionHours = t.retentionHours
        )
    }

    /** Short UI label: agency · designator · id (skipping blanks). */
    fun displayName(): String {
        val parts = listOf(agency, label, id).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.distinct().joinToString(" · ").ifEmpty { id }
    }
}
