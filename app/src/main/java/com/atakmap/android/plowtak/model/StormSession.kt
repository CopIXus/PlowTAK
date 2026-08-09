package com.atakmap.android.plowtak.model

/**
 * A storm ops session that trucks may join for coverage tagging and Data Sync.
 *
 * Multiple agencies may broadcast concurrent storms; each device explicitly
 * joins one reporting storm (see [com.atakmap.android.plowtak.ops.StormSessionManager]).
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
    val cycleMinutes: Int = 45
) {
    val isActive: Boolean get() = endTimeMs == 0L

    /** Short UI label: agency · designator · id (skipping blanks). */
    fun displayName(): String {
        val parts = listOf(agency, label, id).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.distinct().joinToString(" · ").ifEmpty { id }
    }
}
