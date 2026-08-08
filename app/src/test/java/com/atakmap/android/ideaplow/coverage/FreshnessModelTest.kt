package com.atakmap.android.ideaplow.coverage

import org.junit.Assert.assertEquals
import org.junit.Test

class FreshnessModelTest {

    private val model = FreshnessModel(
        cycleTimeMinutes = 45,
        dueSoonFraction = 0.75,
        retentionHours = 12.0
    )

    private val now = 1_700_000_000_000L
    private fun ageMin(minutes: Double): Long = now - (minutes * 60_000L).toLong()

    @Test
    fun `fresh segment is green`() {
        assertEquals(Freshness.GREEN, model.classify(ageMin(0.0), now))
        assertEquals(Freshness.GREEN, model.classify(ageMin(20.0), now))
        // Just below the due-soon threshold (45 * 0.75 = 33.75 min)
        assertEquals(Freshness.GREEN, model.classify(ageMin(33.7), now))
    }

    @Test
    fun `due-soon segment is yellow`() {
        assertEquals(Freshness.YELLOW, model.classify(ageMin(33.75), now))
        assertEquals(Freshness.YELLOW, model.classify(ageMin(40.0), now))
        assertEquals(Freshness.YELLOW, model.classify(ageMin(44.9), now))
    }

    @Test
    fun `overdue segment is red`() {
        assertEquals(Freshness.RED, model.classify(ageMin(45.0), now))
        assertEquals(Freshness.RED, model.classify(ageMin(120.0), now))
        assertEquals(Freshness.RED, model.classify(ageMin(11.9 * 60), now))
    }

    @Test
    fun `beyond retention is expired`() {
        assertEquals(Freshness.EXPIRED, model.classify(ageMin(12.1 * 60), now))
        assertEquals(Freshness.EXPIRED, model.classify(ageMin(48.0 * 60), now))
    }

    @Test
    fun `future timestamp (clock skew) is green`() {
        assertEquals(Freshness.GREEN, model.classify(now + 60_000L, now))
    }

    @Test
    fun `cycle time change moves thresholds`() {
        model.cycleTimeMinutes = 180 // residential route
        assertEquals(Freshness.GREEN, model.classify(ageMin(100.0), now))
        assertEquals(Freshness.YELLOW, model.classify(ageMin(140.0), now))
        assertEquals(Freshness.RED, model.classify(ageMin(181.0), now))
    }
}
