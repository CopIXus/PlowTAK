package com.atakmap.android.ideaplow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractorIdTest {

    @Test
    fun `uid follows the CTR-storm-slot scheme`() {
        assertEquals(
            "CTR-2026-01-15-1736951234-104",
            ContractorId.uidFor("2026-01-15-1736951234", 104)
        )
    }

    @Test
    fun `storm ids with odd characters are sanitized`() {
        val uid = ContractorId.uidFor("storm with spaces|pipes", 100)
        assertTrue(ContractorId.isContractorUid(uid))
        assertFalse(uid.contains(' '))
        assertFalse(uid.contains('|'))
    }

    @Test
    fun `slot derivation is stable and in range`() {
        val a = ContractorId.slotFor("ANDROID-abc123")
        assertEquals(a, ContractorId.slotFor("ANDROID-abc123"))
        assertTrue(a in 100..999)
        // Different installs land on different slots (overwhelmingly).
        val b = ContractorId.slotFor("ANDROID-xyz789")
        assertTrue(b in 100..999)
    }

    @Test
    fun `contractor uids are recognized and parsed`() {
        val uid = ContractorId.uidFor("2026-01-15-1736951234", 104)
        assertTrue(ContractorId.isContractorUid(uid))
        assertEquals("2026-01-15-1736951234", ContractorId.stormOf(uid))

        assertFalse(ContractorId.isContractorUid("ANDROID-abc123"))
        assertFalse(ContractorId.isContractorUid("CTRL-storm-1"))
        assertNull(ContractorId.stormOf("ANDROID-abc123"))
    }

    @Test
    fun `empty storm id is rejected`() {
        try {
            ContractorId.uidFor("", 100)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // required
        }
    }
}
