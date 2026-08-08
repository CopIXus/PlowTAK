package com.atakmap.android.ideaplow.coverage

/** Freshness buckets for coverage coloring. */
enum class Freshness {
    /** Treated within the cycle time — good. */
    GREEN,
    /** Aging; due soon (past [FreshnessModel.dueSoonFraction] of cycle). */
    YELLOW,
    /** Overdue — older than the cycle time. */
    RED,
    /** Beyond the retention window — drop from map and store. */
    EXPIRED
}

/**
 * Maps segment age to a freshness bucket relative to the cycle-time setting.
 * "Never treated this storm" is the *absence* of segments, which renderers
 * treat as RED-equivalent; this model only classifies segments that exist.
 */
class FreshnessModel(
    /** Target revisit interval, minutes (per-priority overrides in Phase 2). */
    var cycleTimeMinutes: Int = 45,
    /** Fraction of the cycle after which a segment shows as due soon. */
    var dueSoonFraction: Double = 0.75,
    /** Drop segments older than this many hours. */
    var retentionHours: Double = 12.0
) {

    fun classify(segmentEndTimeMs: Long, nowMs: Long): Freshness {
        val ageMs = nowMs - segmentEndTimeMs
        if (ageMs < 0) return Freshness.GREEN // clock skew — be generous

        val cycleMs = cycleTimeMinutes * 60_000L
        val retentionMs = (retentionHours * 3_600_000L).toLong()

        return when {
            ageMs > retentionMs -> Freshness.EXPIRED
            ageMs >= cycleMs -> Freshness.RED
            ageMs >= (cycleMs * dueSoonFraction).toLong() -> Freshness.YELLOW
            else -> Freshness.GREEN
        }
    }
}
