package com.atakmap.android.ideaplow.model

/**
 * Central capability-gating rules. Pure functions so both the send side
 * (should *my* GPS paint coverage?) and the receive side (should I merge
 * coverage from *that* unit?) apply identical logic — and so the rules are
 * unit-testable without ATAK.
 */
object CapabilityRules {

    /**
     * Whether a unit of this type may ever contribute "treated" paint.
     * Supervisors and observers never do, no matter how good their GPS is.
     */
    fun paintsCoverage(type: VehicleType): Boolean =
        type == VehicleType.PLOW || type == VehicleType.SALT_ONLY

    /**
     * Evaluates the configurable treating rule against the current equipment
     * state, honoring capability gates: a channel the vehicle does not have
     * can never satisfy the rule.
     */
    fun isTreating(
        cap: VehicleCapability,
        rule: TreatRule,
        bladeDown: Boolean,
        saltOn: Boolean
    ): Boolean {
        if (!cap.canTreat || !paintsCoverage(cap.type)) return false
        val blade = cap.hasBlade && bladeDown
        val salt = cap.hasSalt && saltOn
        return when (rule) {
            TreatRule.BLADE_DOWN_ONLY -> blade
            TreatRule.SALT_ON_ONLY -> salt
            TreatRule.EITHER -> blade || salt
            TreatRule.BOTH ->
                // A single-channel vehicle satisfies BOTH with its one channel;
                // otherwise a salt-only truck could never treat under BOTH.
                when {
                    cap.hasBlade && cap.hasSalt -> blade && salt
                    cap.hasBlade -> blade
                    cap.hasSalt -> salt
                    else -> false
                }
        }
    }

    /** Material mode recorded on a swath segment given the active equipment. */
    fun materialMode(cap: VehicleCapability, bladeDown: Boolean, saltOn: Boolean): MaterialMode {
        val blade = cap.hasBlade && bladeDown
        val salt = cap.hasSalt && saltOn
        return when {
            blade && salt -> MaterialMode.PLOW_AND_SALT
            blade -> MaterialMode.PLOW_ONLY
            salt -> MaterialMode.SALT
            else -> MaterialMode.NONE
        }
    }

    /**
     * Units pulled from "nearest available" suggestions. LOADING is also
     * excluded (Phase 3): a truck at the salt dome can't respond promptly.
     */
    fun isDispatchable(status: VehicleStatus): Boolean =
        status != VehicleStatus.OUT_OF_SERVICE &&
                status != VehicleStatus.OFF_DUTY &&
                status != VehicleStatus.LOADING
}
