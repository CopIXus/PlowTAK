package com.atakmap.android.ideaplow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRulesTest {

    private val plowWithSalt = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-1", "P1")
        .copy(hasSalt = true)
    private val plowOnly = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-2", "P2")
    private val saltOnly = VehicleCapability.defaultsFor(VehicleType.SALT_ONLY, "Salt-1", "S1")
    private val supervisor = VehicleCapability.defaultsFor(VehicleType.SUPERVISOR, "Sup-1", "SU1")
    private val observer = VehicleCapability.defaultsFor(VehicleType.OBSERVER, "Fire-Obs", "O1")

    @Test
    fun `defaults match capability table`() {
        assertTrue(plowOnly.hasBlade); assertFalse(plowOnly.hasSalt); assertTrue(plowOnly.canTreat)
        assertFalse(saltOnly.hasBlade); assertTrue(saltOnly.hasSalt); assertTrue(saltOnly.canTreat)
        assertFalse(supervisor.canTreat); assertTrue(supervisor.canManageStorm)
        assertTrue(supervisor.publishPresence)
        assertFalse(observer.canTreat); assertFalse(observer.canManageStorm)
        assertFalse(observer.publishPresence)
    }

    @Test
    fun `only treat types paint coverage`() {
        assertTrue(CapabilityRules.paintsCoverage(VehicleType.PLOW))
        assertTrue(CapabilityRules.paintsCoverage(VehicleType.SALT_ONLY))
        assertFalse(CapabilityRules.paintsCoverage(VehicleType.SUPERVISOR))
        assertFalse(CapabilityRules.paintsCoverage(VehicleType.OBSERVER))
    }

    @Test
    fun `supervisor never treats even with toggles somehow on`() {
        for (rule in TreatRule.entries) {
            assertFalse(CapabilityRules.isTreating(supervisor, rule, bladeDown = true, saltOn = true))
            assertFalse(CapabilityRules.isTreating(observer, rule, bladeDown = true, saltOn = true))
        }
    }

    @Test
    fun `blade rule gates on blade channel`() {
        assertTrue(CapabilityRules.isTreating(plowOnly, TreatRule.BLADE_DOWN_ONLY, true, false))
        assertFalse(CapabilityRules.isTreating(plowOnly, TreatRule.BLADE_DOWN_ONLY, false, true))
        // Salt-only truck can never satisfy a blade rule.
        assertFalse(CapabilityRules.isTreating(saltOnly, TreatRule.BLADE_DOWN_ONLY, true, true))
    }

    @Test
    fun `salt rule gates on salt channel`() {
        assertTrue(CapabilityRules.isTreating(saltOnly, TreatRule.SALT_ON_ONLY, false, true))
        assertFalse(CapabilityRules.isTreating(saltOnly, TreatRule.SALT_ON_ONLY, true, false))
        // Plow without spreader can never satisfy a salt rule.
        assertFalse(CapabilityRules.isTreating(plowOnly, TreatRule.SALT_ON_ONLY, true, true))
    }

    @Test
    fun `either rule accepts any equipped active channel`() {
        assertTrue(CapabilityRules.isTreating(plowWithSalt, TreatRule.EITHER, true, false))
        assertTrue(CapabilityRules.isTreating(plowWithSalt, TreatRule.EITHER, false, true))
        assertFalse(CapabilityRules.isTreating(plowWithSalt, TreatRule.EITHER, false, false))
    }

    @Test
    fun `both rule requires both channels on dual-equipped truck`() {
        assertTrue(CapabilityRules.isTreating(plowWithSalt, TreatRule.BOTH, true, true))
        assertFalse(CapabilityRules.isTreating(plowWithSalt, TreatRule.BOTH, true, false))
        assertFalse(CapabilityRules.isTreating(plowWithSalt, TreatRule.BOTH, false, true))
    }

    @Test
    fun `both rule degrades to single channel for single-equipped trucks`() {
        // A salt-only truck must still be able to treat under BOTH.
        assertTrue(CapabilityRules.isTreating(saltOnly, TreatRule.BOTH, false, true))
        assertTrue(CapabilityRules.isTreating(plowOnly, TreatRule.BOTH, true, false))
    }

    @Test
    fun `deadhead does not treat`() {
        assertFalse(CapabilityRules.isTreating(plowWithSalt, TreatRule.EITHER, false, false))
    }

    @Test
    fun `material mode reflects active equipped channels`() {
        assertEquals(MaterialMode.PLOW_AND_SALT,
            CapabilityRules.materialMode(plowWithSalt, true, true))
        assertEquals(MaterialMode.PLOW_ONLY,
            CapabilityRules.materialMode(plowWithSalt, true, false))
        assertEquals(MaterialMode.SALT,
            CapabilityRules.materialMode(saltOnly, false, true))
        // Unequipped channels can't contribute even if flag is somehow set.
        assertEquals(MaterialMode.NONE,
            CapabilityRules.materialMode(supervisor, true, true))
    }

    @Test
    fun `sanitize repairs illegal combinations`() {
        val badObserver = VehicleCapability.defaultsFor(VehicleType.OBSERVER).copy(
            hasBlade = true, hasSalt = true, canTreat = true, canManageStorm = true,
            plowWidthM = 5.0
        )
        val fixed = VehicleCapability.sanitize(badObserver)
        assertFalse(fixed.hasBlade)
        assertFalse(fixed.hasSalt)
        assertFalse(fixed.canTreat)
        assertFalse(fixed.canManageStorm)
        assertEquals(0.0, fixed.plowWidthM, 1e-9)

        val saltOnlyFixed = VehicleCapability.sanitize(
            VehicleCapability.defaultsFor(VehicleType.SALT_ONLY).copy(hasBlade = true)
        )
        assertFalse(saltOnlyFixed.hasBlade)
        assertTrue(saltOnlyFixed.hasSalt)
        assertTrue(saltOnlyFixed.canTreat)
    }

    @Test
    fun `out of service and off duty are not dispatchable`() {
        assertFalse(CapabilityRules.isDispatchable(VehicleStatus.OUT_OF_SERVICE))
        assertFalse(CapabilityRules.isDispatchable(VehicleStatus.OFF_DUTY))
        assertTrue(CapabilityRules.isDispatchable(VehicleStatus.TREATING))
        assertTrue(CapabilityRules.isDispatchable(VehicleStatus.LOADING))
    }
}
