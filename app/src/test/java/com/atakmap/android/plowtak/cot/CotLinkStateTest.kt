package com.atakmap.android.plowtak.cot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CotLinkStateTest {
    @Test
    fun `no servers is disconnected`() {
        assertFalse(CotLinkState.isUp(null, true))
        assertFalse(CotLinkState.isUp(0, true))
    }

    @Test
    fun `a connected server is up`() {
        assertTrue(CotLinkState.isUp(1, true))
        assertFalse(CotLinkState.isUp(2, false))
    }
}
