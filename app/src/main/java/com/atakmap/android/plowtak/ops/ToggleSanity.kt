package com.atakmap.android.plowtak.ops

/**
 * Forgot-to-toggle heuristics for manual equipment buttons at 3 AM. Fed one
 * evaluation per GPS tick; produces at most one [Prompt] per tick, with
 * per-rule cooldowns so drivers are nudged, not nagged.
 *
 * Rules (all PROMPTS — never auto-flips; the driver stays authoritative
 * until Bluetooth sensors exist):
 *  - NUDGE_NOT_TREATING: moving for N minutes during an active storm with
 *    no treating state -> "Are you treating?"
 *  - CONFIRM_SPEED: blade down above the max plowing speed (sustained) ->
 *    "Still plowing at this speed?"
 *  - CONFIRM_FACILITY: treating while inside a facility geofence ->
 *    "Treating inside the yard?"
 */
class ToggleSanity(
    private val config: Config = Config()
) {

    data class Config(
        /** Continuous movement without treating before the nudge fires. */
        val nudgeAfterMovingMs: Long = 10 * 60_000L,
        /** Max plausible plowing speed, m/s (default 35 mph). */
        val maxPlowSpeedMps: Double = 15.6,
        /** Overspeed must hold this long before the confirm fires. */
        val speedSustainMs: Long = 10_000L,
        /** Minimum quiet time between prompts of the same type. */
        val promptCooldownMs: Long = 5 * 60_000L
    )

    enum class PromptType { NUDGE_NOT_TREATING, CONFIRM_SPEED, CONFIRM_FACILITY }

    data class Prompt(val type: PromptType, val timeMs: Long, val message: String)

    /** Everything the rules need for one tick, already evaluated upstream. */
    data class Input(
        val timeMs: Long,
        val moving: Boolean,
        val speedMps: Double,
        val treating: Boolean,
        val bladeDown: Boolean,
        val stormActive: Boolean,
        val insideFacility: Boolean,
        val onShift: Boolean
    )

    private var movingSinceMs = -1L
    private var overspeedSinceMs = -1L
    private var wasInsideFacilityTreating = false
    private val lastPromptMs = HashMap<PromptType, Long>()

    fun reset() {
        movingSinceMs = -1L
        overspeedSinceMs = -1L
        wasInsideFacilityTreating = false
        lastPromptMs.clear()
    }

    /** Evaluate one tick; null when nothing needs the driver's attention. */
    fun onTick(input: Input): Prompt? {
        if (!input.onShift) {
            reset()
            return null
        }

        val speedPrompt = evaluateSpeed(input)
        val facilityPrompt = evaluateFacility(input)
        val nudgePrompt = evaluateNudge(input)

        // One prompt per tick; safety-relevant confirms outrank the nudge.
        // Only the *shown* prompt is consumed (cooldown / timer re-arm) —
        // a rule suppressed this tick stays eligible for the next one.
        val chosen = speedPrompt ?: facilityPrompt ?: nudgePrompt ?: return null
        markPrompted(chosen.type, input.timeMs)
        if (chosen.type == PromptType.NUDGE_NOT_TREATING) {
            movingSinceMs = input.timeMs // re-arm the timer after the nudge
        }
        return chosen
    }

    private fun evaluateSpeed(input: Input): Prompt? {
        if (!input.bladeDown || input.speedMps <= config.maxPlowSpeedMps) {
            overspeedSinceMs = -1L
            return null
        }
        if (overspeedSinceMs < 0) overspeedSinceMs = input.timeMs
        if (input.timeMs - overspeedSinceMs < config.speedSustainMs) return null
        if (onCooldown(PromptType.CONFIRM_SPEED, input.timeMs)) return null

        return Prompt(
            PromptType.CONFIRM_SPEED, input.timeMs,
            "Blade is DOWN above plowing speed — still plowing?"
        )
    }

    private fun evaluateFacility(input: Input): Prompt? {
        val active = input.treating && input.insideFacility
        if (!active) {
            wasInsideFacilityTreating = false
            return null
        }
        // Fire on entering the condition, then respect the cooldown.
        if (wasInsideFacilityTreating) return null
        wasInsideFacilityTreating = true
        if (onCooldown(PromptType.CONFIRM_FACILITY, input.timeMs)) return null

        return Prompt(
            PromptType.CONFIRM_FACILITY, input.timeMs,
            "Still treating inside the facility — toggles left on?"
        )
    }

    private fun evaluateNudge(input: Input): Prompt? {
        val armed = input.stormActive && input.moving && !input.treating
        if (!armed) {
            movingSinceMs = -1L
            return null
        }
        if (movingSinceMs < 0) movingSinceMs = input.timeMs
        if (input.timeMs - movingSinceMs < config.nudgeAfterMovingMs) return null
        if (onCooldown(PromptType.NUDGE_NOT_TREATING, input.timeMs)) return null

        return Prompt(
            PromptType.NUDGE_NOT_TREATING, input.timeMs,
            "Moving during an active storm with no treating state — are you treating?"
        )
    }

    private fun onCooldown(type: PromptType, nowMs: Long): Boolean {
        val last = lastPromptMs[type] ?: return false
        return nowMs - last < config.promptCooldownMs
    }

    private fun markPrompted(type: PromptType, nowMs: Long) {
        lastPromptMs[type] = nowMs
    }
}
