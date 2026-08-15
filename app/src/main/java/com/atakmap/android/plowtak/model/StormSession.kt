package com.atakmap.android.plowtak.model

import com.atakmap.android.plowtak.coverage.CycleTimes

/**
 * A storm ops session that trucks may join for coverage tagging and Data Sync.
 *
 * Multiple agencies may broadcast concurrent storms; each device explicitly
 * joins one reporting storm (see [com.atakmap.android.plowtak.ops.StormSessionManager]).
 *
 * Cycle / retention / road-condition TTL are **storm-level**: every joined
 * device follows [storm-config.json] / CoT, not each tablet's Settings.
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
    /** Freshness cycle minutes for this storm (shared via storm-config.json). */
    val cycleMinutes: Int = 45,
    /** Per-priority cycle override minutes; 0 = use [cycleMinutes]. */
    val cycleP1Minutes: Int = 0,
    val cycleP2Minutes: Int = 0,
    val cycleP3Minutes: Int = 0,
    /**
     * Hours after which overdue coverage is cleared from the map / store.
     * **0 = never clear** (lines stay red after the cycle). Shared via
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
        /** 0 = keep overdue coverage on the map as red forever. */
        const val DEFAULT_COVERAGE_RETENTION_HOURS = 0.0
    }

    val isActive: Boolean get() = endTimeMs == 0L

    fun cycleTimes(): CycleTimes = CycleTimes(
        defaultMinutes = cycleMinutes,
        p1Minutes = cycleP1Minutes,
        p2Minutes = cycleP2Minutes,
        p3Minutes = cycleP3Minutes
    )

    /** Short UI label: agency · designator · id (skipping blanks). */
    fun displayName(): String {
        val parts = listOf(agency, label, id).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.distinct().joinToString(" · ").ifEmpty { id }
    }
}
