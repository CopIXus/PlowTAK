package com.atakmap.android.plowtak.coverage

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshnessModelTest {

    private val model = FreshnessModel(
        greenUntilMinutes = 30,
        yellowUntilMinutes = 50,
        redAfterMinutes = 60,
        retentionHours = 8.0
    )

    private val now = 1_700_000_000_000L
    private fun ageMin(minutes: Double): Long = now - (minutes * 60_000L).toLong()

    @Test
    fun `fresh segment is green`() {
        assertEquals(Freshness.GREEN, model.classify(ageMin(0.0), now))
        assertEquals(Freshness.GREEN, model.classify(ageMin(20.0), now))
        assertEquals(Freshness.GREEN, model.classify(ageMin(29.9), now))
    }

    @Test
    fun `aging segment is yellow through red-after`() {
        assertEquals(Freshness.YELLOW, model.classify(ageMin(30.0), now))
        assertEquals(Freshness.YELLOW, model.classify(ageMin(50.0), now))
        assertEquals(Freshness.YELLOW, model.classify(ageMin(59.9), now))
    }

    @Test
    fun `overdue segment is red`() {
        assertEquals(Freshness.RED, model.classify(ageMin(60.0), now))
        assertEquals(Freshness.RED, model.classify(ageMin(120.0), now))
        assertEquals(Freshness.RED, model.classify(ageMin(7.9 * 60), now))
    }

    @Test
    fun `beyond retention is expired`() {
        assertEquals(Freshness.EXPIRED, model.classify(ageMin(8.1 * 60), now))
        assertEquals(Freshness.EXPIRED, model.classify(ageMin(48.0 * 60), now))
    }

    @Test
    fun `retention zero never expires`() {
        val keep = FreshnessModel(
            greenUntilMinutes = 30,
            yellowUntilMinutes = 50,
            redAfterMinutes = 60,
            retentionHours = 0.0
        )
        assertEquals(Freshness.RED, keep.classify(ageMin(60.0), now))
        assertEquals(Freshness.RED, keep.classify(ageMin(12.1 * 60), now))
        assertEquals(Freshness.RED, keep.classify(ageMin(100.0 * 60), now))
    }

    @Test
    fun `future timestamp (clock skew) is green`() {
        assertEquals(Freshness.GREEN, model.classify(now + 60_000L, now))
    }

    @Test
    fun `red-after override can only tighten`() {
        // Zone red-after 45 → red sooner; green still 30.
        assertEquals(Freshness.GREEN, model.classify(ageMin(20.0), now, 45))
        assertEquals(Freshness.YELLOW, model.classify(ageMin(40.0), now, 45))
        assertEquals(Freshness.RED, model.classify(ageMin(45.0), now, 45))
    }

    @Test
    fun `legacy cycle migrates to absolute timers`() {
        val legacy = FreshnessModel.fromLegacyCycle(45, retentionHours = 12.0)
        assertEquals(30, legacy.greenUntilMinutes)
        assertEquals(33, legacy.yellowUntilMinutes)
        assertEquals(45, legacy.redAfterMinutes)
    }
}
