package com.atakmap.android.plowtak.cot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCotFilterTest {

    @Test
    fun exactSelfUidIsEcho() {
        assertTrue(SelfCotFilter.isSelfEcho("PLOWTAK-T-1", "PLOWTAK-T-1"))
    }

    @Test
    fun peerWithSharedPrefixIsNotEcho() {
        // Regression: startsWith(selfUid) incorrectly dropped PLOWTAK-T-10
        // when self was PLOWTAK-T-1.
        assertFalse(SelfCotFilter.isSelfEcho("PLOWTAK-T-10", "PLOWTAK-T-1"))
        assertFalse(SelfCotFilter.isSelfEcho("PLOWTAK-T-10-cov-1", "PLOWTAK-T-1"))
        assertFalse(SelfCotFilter.isSelfEcho("PLOWTAK-T-10-distress", "PLOWTAK-T-1"))
    }

    @Test
    fun derivedSelfEventsAreEcho() {
        assertTrue(SelfCotFilter.isSelfEcho("PLOWTAK-T-1-cov-1736951234000", "PLOWTAK-T-1"))
        assertTrue(SelfCotFilter.isSelfEcho("PLOWTAK-T-1-distress", "PLOWTAK-T-1"))
        // Hazard/condition event uids use a product prefix, not "$self-…".
        assertFalse(SelfCotFilter.isSelfEcho("plowtak-hz-PLOWTAK-T-1-1", "PLOWTAK-T-1"))
    }

    @Test
    fun emptyInputsAreNotEcho() {
        assertFalse(SelfCotFilter.isSelfEcho(null, "PLOWTAK-T-1"))
        assertFalse(SelfCotFilter.isSelfEcho("PLOWTAK-T-1", ""))
    }
}
