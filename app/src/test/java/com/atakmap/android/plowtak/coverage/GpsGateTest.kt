package com.atakmap.android.plowtak.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsGateTest {

    private val gate = GpsGate(
        GpsGate.Config(
            maxSpeedMps = 50.0,
            rebaseAfterMs = 60_000L,
            stationaryRadiusM = 8.0,
            stationaryWindowMs = 20_000L
        )
    )

    /** ~0.0001 deg latitude ≈ 11.1 m. */
    private fun step(n: Int) = 36.0 + n * 0.0001

    @Test
    fun `normal driving passes`() {
        for (i in 0 until 10) {
            val v = gate.evaluate(step(i), -86.0, i * 1000L, 5.0, 25.0)
            assertTrue(v.accepted)
            assertTrue(v.moving)
            assertEquals(GpsGate.Reason.OK, v.reason)
        }
    }

    @Test
    fun `bad CE fix is rejected without moving anchors`() {
        gate.evaluate(step(0), -86.0, 0L, 5.0, 25.0)
        val bad = gate.evaluate(step(1), -86.0, 1000L, 80.0, 25.0)
        assertFalse(bad.accepted)
        assertEquals(GpsGate.Reason.BAD_CE, bad.reason)

        // Next good fix continues from the last good one, not the bad one.
        val good = gate.evaluate(step(1), -86.0, 2000L, 5.0, 25.0)
        assertTrue(good.accepted)
    }

    @Test
    fun `teleport jump is rejected`() {
        gate.evaluate(36.0, -86.0, 0L, 5.0, 25.0)
        // 0.1 deg (~11 km) in one second — implausible.
        val v = gate.evaluate(36.1, -86.0, 1000L, 5.0, 25.0)
        assertFalse(v.accepted)
        assertEquals(GpsGate.Reason.TELEPORT, v.reason)
    }

    @Test
    fun `consistent fixes after a jump re-base the track`() {
        gate.evaluate(36.0, -86.0, 0L, 5.0, 25.0)
        // GPS re-acquires 11 km away (e.g. stale first fix). First fix at the
        // new location is rejected...
        assertFalse(gate.evaluate(36.1, -86.0, 1000L, 5.0, 25.0).accepted)
        // ...but the next fix agreeing with it is accepted (re-base).
        val v = gate.evaluate(36.1001, -86.0, 2000L, 5.0, 25.0)
        assertTrue(v.accepted)
        assertEquals(GpsGate.Reason.OK, v.reason)
    }

    @Test
    fun `second teleport to a different place stays rejected`() {
        gate.evaluate(36.0, -86.0, 0L, 5.0, 25.0)
        assertFalse(gate.evaluate(36.1, -86.0, 1000L, 5.0, 25.0).accepted)
        // A different implausible location — still garbage.
        assertFalse(gate.evaluate(36.5, -86.0, 2000L, 5.0, 25.0).accepted)
    }

    @Test
    fun `long outage accepts the jump`() {
        gate.evaluate(36.0, -86.0, 0L, 5.0, 25.0)
        // 90 s later, 11 km away: tunnel / parking garage. Accept and re-base.
        val v = gate.evaluate(36.1, -86.0, 90_000L, 5.0, 25.0)
        assertTrue(v.accepted)
    }

    @Test
    fun `sitting at a red light goes stationary`() {
        gate.evaluate(36.0, -86.0, 0L, 5.0, 25.0)
        // Jitter within ~2 m for 30 s.
        var lastVerdict: GpsGate.Verdict? = null
        for (i in 1..30) {
            lastVerdict = gate.evaluate(36.0 + (i % 2) * 0.000_01, -86.0, i * 1000L, 5.0, 25.0)
            assertTrue(lastVerdict.accepted)
        }
        assertFalse(lastVerdict!!.moving)
        assertEquals(GpsGate.Reason.STATIONARY, lastVerdict.reason)
    }

    @Test
    fun `pulling away from a light resumes moving`() {
        gate.evaluate(36.0, -86.0, 0L, 5.0, 25.0)
        for (i in 1..30) {
            gate.evaluate(36.0, -86.0, i * 1000L, 5.0, 25.0)
        }
        // Real movement (~11 m) resets the anchor immediately.
        val v = gate.evaluate(36.0001, -86.0, 31_000L, 5.0, 25.0)
        assertTrue(v.accepted)
        assertTrue(v.moving)
    }

    @Test
    fun `reset clears all state`() {
        gate.evaluate(36.0, -86.0, 0L, 5.0, 25.0)
        gate.reset()
        // A far-away fix right after reset is a fresh start, not a teleport.
        val v = gate.evaluate(38.0, -80.0, 1000L, 5.0, 25.0)
        assertTrue(v.accepted)
    }

    @Test
    fun `NaN CE passes the quality gate`() {
        val v = gate.evaluate(36.0, -86.0, 0L, Double.NaN, 25.0)
        assertTrue(v.accepted)
    }
}
