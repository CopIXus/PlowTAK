package com.atakmap.android.plowtak.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToggleSanityTest {

    private val config = ToggleSanity.Config(
        nudgeAfterMovingMs = 60_000L,   // 1 min for tests
        maxPlowSpeedMps = 15.6,         // 35 mph
        speedSustainMs = 5_000L,
        promptCooldownMs = 120_000L
    )

    private fun input(
        timeMs: Long,
        moving: Boolean = true,
        speedMps: Double = 10.0,
        treating: Boolean = false,
        bladeDown: Boolean = false,
        stormActive: Boolean = true,
        insideFacility: Boolean = false,
        onShift: Boolean = true
    ) = ToggleSanity.Input(
        timeMs, moving, speedMps, treating, bladeDown, stormActive, insideFacility, onShift
    )

    // ---------------------------------------------------------- nudge

    @Test
    fun `nudge fires after sustained movement without treating`() {
        val sanity = ToggleSanity(config)
        assertNull(sanity.onTick(input(0L)))
        assertNull(sanity.onTick(input(30_000L)))
        val prompt = sanity.onTick(input(61_000L))
        assertEquals(ToggleSanity.PromptType.NUDGE_NOT_TREATING, prompt?.type)
    }

    @Test
    fun `treating resets the nudge timer`() {
        val sanity = ToggleSanity(config)
        sanity.onTick(input(0L))
        sanity.onTick(input(30_000L, treating = true)) // blade dropped — reset
        assertNull(sanity.onTick(input(61_000L)))      // clock restarted at 61 s
        assertNull(sanity.onTick(input(90_000L)))
        val prompt = sanity.onTick(input(122_000L))
        assertEquals(ToggleSanity.PromptType.NUDGE_NOT_TREATING, prompt?.type)
    }

    @Test
    fun `no nudge without an active storm`() {
        val sanity = ToggleSanity(config)
        sanity.onTick(input(0L, stormActive = false))
        assertNull(sanity.onTick(input(61_000L, stormActive = false)))
    }

    @Test
    fun `no nudge while stopped`() {
        val sanity = ToggleSanity(config)
        sanity.onTick(input(0L, moving = false))
        assertNull(sanity.onTick(input(61_000L, moving = false)))
    }

    @Test
    fun `nudge respects cooldown then re-fires`() {
        val sanity = ToggleSanity(config)
        sanity.onTick(input(0L))
        assertEquals(
            ToggleSanity.PromptType.NUDGE_NOT_TREATING,
            sanity.onTick(input(61_000L))?.type
        )
        // Still moving untreated: timer re-armed at 61 s, nudge due again at
        // 122 s, but the 120 s cooldown pushes it just past 181 s.
        assertNull(sanity.onTick(input(122_000L)))
        assertEquals(
            ToggleSanity.PromptType.NUDGE_NOT_TREATING,
            sanity.onTick(input(182_000L))?.type
        )
    }

    // ---------------------------------------------------------- speed

    @Test
    fun `blade down over max plow speed prompts after sustain`() {
        val sanity = ToggleSanity(config)
        assertNull(sanity.onTick(input(0L, bladeDown = true, speedMps = 20.0, treating = true)))
        assertNull(sanity.onTick(input(3_000L, bladeDown = true, speedMps = 20.0, treating = true)))
        val prompt = sanity.onTick(input(6_000L, bladeDown = true, speedMps = 20.0, treating = true))
        assertEquals(ToggleSanity.PromptType.CONFIRM_SPEED, prompt?.type)
    }

    @Test
    fun `momentary overspeed does not prompt`() {
        val sanity = ToggleSanity(config)
        assertNull(sanity.onTick(input(0L, bladeDown = true, speedMps = 20.0, treating = true)))
        // Slowed back down — sustain window reset.
        assertNull(sanity.onTick(input(3_000L, bladeDown = true, speedMps = 10.0, treating = true)))
        assertNull(sanity.onTick(input(6_000L, bladeDown = true, speedMps = 20.0, treating = true)))
    }

    @Test
    fun `overspeed with blade up is fine`() {
        val sanity = ToggleSanity(config)
        assertNull(sanity.onTick(input(0L, bladeDown = false, speedMps = 25.0)))
        assertNull(sanity.onTick(input(10_000L, bladeDown = false, speedMps = 25.0)))
    }

    // -------------------------------------------------------- facility

    @Test
    fun `treating inside a facility prompts once per entry`() {
        val sanity = ToggleSanity(config)
        val first = sanity.onTick(input(0L, treating = true, insideFacility = true))
        assertEquals(ToggleSanity.PromptType.CONFIRM_FACILITY, first?.type)
        // Still inside — no repeat.
        assertNull(sanity.onTick(input(5_000L, treating = true, insideFacility = true)))
        // Leaves and re-enters after cooldown — prompts again.
        sanity.onTick(input(10_000L, treating = true, insideFacility = false))
        val again = sanity.onTick(input(130_000L, treating = true, insideFacility = true))
        assertEquals(ToggleSanity.PromptType.CONFIRM_FACILITY, again?.type)
    }

    @Test
    fun `inside facility while not treating is fine`() {
        val sanity = ToggleSanity(config)
        assertNull(sanity.onTick(input(0L, treating = false, insideFacility = true)))
    }

    // ---------------------------------------------------------- misc

    @Test
    fun `off shift produces nothing and resets`() {
        val sanity = ToggleSanity(config)
        sanity.onTick(input(0L))
        assertNull(sanity.onTick(input(61_000L, onShift = false)))
        // Back on shift: timers restarted, no instant nudge.
        assertNull(sanity.onTick(input(62_000L)))
    }

    @Test
    fun `speed confirm outranks the nudge`() {
        val sanity = ToggleSanity(config)
        // Arm both on one tick, then let both come due on the next: the
        // speed confirm must win when both are eligible simultaneously.
        sanity.onTick(input(0L, bladeDown = true, speedMps = 20.0))
        val prompt = sanity.onTick(input(61_000L, bladeDown = true, speedMps = 20.0))
        assertEquals(ToggleSanity.PromptType.CONFIRM_SPEED, prompt?.type)
        // The nudge is still pending and fires on the following tick.
        assertEquals(
            ToggleSanity.PromptType.NUDGE_NOT_TREATING,
            sanity.onTick(input(62_000L, bladeDown = true, speedMps = 20.0))?.type
        )
    }
}
