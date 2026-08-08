package com.atakmap.android.ideaplow.equipment

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun `delays double until the cap`() {
        val b = ReconnectBackoff(baseDelayMs = 1_000, maxDelayMs = 60_000)
        assertEquals(1_000L, b.nextDelayMs())
        assertEquals(2_000L, b.nextDelayMs())
        assertEquals(4_000L, b.nextDelayMs())
        assertEquals(8_000L, b.nextDelayMs())
        assertEquals(16_000L, b.nextDelayMs())
        assertEquals(32_000L, b.nextDelayMs())
        assertEquals(60_000L, b.nextDelayMs()) // capped
        assertEquals(60_000L, b.nextDelayMs()) // stays capped
    }

    @Test
    fun `reset returns to the base delay`() {
        val b = ReconnectBackoff(baseDelayMs = 500, maxDelayMs = 10_000)
        repeat(5) { b.nextDelayMs() }
        b.reset()
        assertEquals(500L, b.nextDelayMs())
    }

    @Test
    fun `stays capped even after very many attempts`() {
        val b = ReconnectBackoff(baseDelayMs = 1_000, maxDelayMs = 60_000)
        repeat(100) { b.nextDelayMs() } // would overflow a naive shl
        assertEquals(60_000L, b.nextDelayMs())
    }
}
