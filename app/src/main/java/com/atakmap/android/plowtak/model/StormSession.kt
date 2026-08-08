package com.atakmap.android.plowtak.model

/**
 * A supervisor-scoped storm event. Coverage records are tagged with [id] so
 * "never treated this storm" has meaning, and retention/reporting is scoped
 * per storm rather than forever.
 */
data class StormSession(
    /** e.g. "2026-01-15-1736951234" — date prefix + start epoch for uniqueness. */
    val id: String,
    val startTimeMs: Long,
    /** 0 while the storm is active. */
    val endTimeMs: Long = 0L,
    /** Callsign of the supervisor that started it. */
    val startedBy: String = ""
) {
    val isActive: Boolean get() = endTimeMs == 0L
}
