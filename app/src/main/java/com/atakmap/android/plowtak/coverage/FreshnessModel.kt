package com.atakmap.android.plowtak.coverage

/** Freshness buckets for coverage coloring. */
enum class Freshness {
    /** Treated recently — within green-until. */
    GREEN,
    /** Aging; still before red-after (includes yellow-until through red). */
    YELLOW,
    /** Overdue — at/after red-after. */
    RED,
    /** Beyond the retention window — drop from map and store. */
    EXPIRED
}

/**
 * Maps segment age (now − last plow) to a freshness bucket using absolute
 * storm timers: green until / yellow until / red after / remove after.
 *
 * [classify] with an override uses that value as a **red-after** cap
 * (priority / zone), keeping green/yellow from the storm model.
 */
class FreshnessModel(
    var greenUntilMinutes: Int = 30,
    var yellowUntilMinutes: Int = 50,
    var redAfterMinutes: Int = 60,
    /** Drop segments older than this many hours; 0 = never expire (stay RED). */
    var retentionHours: Double = 8.0
) {

    /** Alias for [redAfterMinutes] (legacy cycle / P1–P3 override hook). */
    var cycleTimeMinutes: Int
        get() = redAfterMinutes
        set(value) {
            redAfterMinutes = value.coerceAtLeast(1)
        }

    fun classify(segmentEndTimeMs: Long, nowMs: Long): Freshness =
        classify(segmentEndTimeMs, nowMs, redAfterMinutes)

    /**
     * @param redAfterOverride minutes; when > 0, effective red is
     *   `min(storm redAfter, override)` so zones/priorities can only tighten.
     */
    fun classify(segmentEndTimeMs: Long, nowMs: Long, redAfterOverride: Int): Freshness {
        val ageMs = nowMs - segmentEndTimeMs
        if (ageMs < 0) return Freshness.GREEN // clock skew — be generous

        if (retentionHours > 0) {
            val retentionMs = (retentionHours * 3_600_000L).toLong()
            if (ageMs > retentionMs) return Freshness.EXPIRED
        }

        val stormRed = redAfterMinutes.coerceAtLeast(1)
        val red = if (redAfterOverride > 0) {
            minOf(stormRed, redAfterOverride.coerceAtLeast(1))
        } else {
            stormRed
        }
        val green = greenUntilMinutes.coerceIn(1, red)
        // yellowUntil is stored for UI/sync; paint stays yellow until redAfter.
        @Suppress("UNUSED_VARIABLE")
        val yellow = yellowUntilMinutes.coerceIn(green, red)

        return when {
            ageMs < green * 60_000L -> Freshness.GREEN
            ageMs < red * 60_000L -> Freshness.YELLOW
            else -> Freshness.RED
        }
    }

    companion object {
        /** Map a legacy single cycle into green / yellow / red timers. */
        fun fromLegacyCycle(
            cycleMinutes: Int,
            retentionHours: Double = StormDefaults.RETENTION_HOURS
        ): FreshnessModel {
            val red = cycleMinutes.coerceAtLeast(5)
            val yellow = maxOf(1, (red * 0.75).toInt())
            val green = minOf(30, yellow).coerceAtLeast(1)
            return FreshnessModel(
                greenUntilMinutes = green,
                yellowUntilMinutes = maxOf(green, yellow),
                redAfterMinutes = red,
                retentionHours = retentionHours
            )
        }
    }
}

/** New-storm timer defaults. */
object StormDefaults {
    const val GREEN_UNTIL_MIN = 30
    const val YELLOW_UNTIL_MIN = 50
    const val RED_AFTER_MIN = 60
    const val RETENTION_HOURS = 8.0
}
