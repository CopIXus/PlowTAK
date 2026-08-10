package com.atakmap.android.plowtak.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakServerTargetsUrlTest {

    @Test
    fun hostHasExplicitPortDetectsPort() {
        assertTrue(TakServerTargets.hostHasExplicitPort("https://tak.example.com:8443"))
        assertTrue(TakServerTargets.hostHasExplicitPort("http://10.0.0.5:8080"))
        assertFalse(TakServerTargets.hostHasExplicitPort("https://tak.example.com"))
        assertFalse(TakServerTargets.hostHasExplicitPort("http://10.0.0.5"))
        assertTrue(TakServerTargets.hostHasExplicitPort("https://[2001:db8::1]:8443"))
        assertFalse(TakServerTargets.hostHasExplicitPort("https://[2001:db8::1]"))
    }

    @Test
    fun normalizeHostBaseStripsApiPort() {
        assertEquals(
            "https://tak.example.com",
            TakServerTargets.normalizeHostBase("https://tak.example.com:8443")
        )
        assertEquals(
            "https://tak.example.com",
            TakServerTargets.normalizeHostBase("https://tak.example.com/")
        )
        assertEquals(
            "https://tak.example.com",
            TakServerTargets.normalizeHostBase("https://tak.example.com")
        )
        assertEquals(
            "https://[2001:db8::1]",
            TakServerTargets.normalizeHostBase("https://[2001:db8::1]:8443")
        )
        assertNull(TakServerTargets.normalizeHostBase("  "))
    }

    @Test
    fun isValidHttpUrlRequiresHostWithoutPort() {
        // GetHttpClient host base must be scheme://host (no port).
        assertTrue(TakServerTargets.isValidHttpUrl("https://tak.example.com"))
        assertFalse(TakServerTargets.isValidHttpUrl("https://tak.example.com:8443"))
        assertFalse(TakServerTargets.isValidHttpUrl("https://tak.example.com:"))
        assertFalse(TakServerTargets.isValidHttpUrl("ssl://tak.example.com:8089"))
        assertFalse(TakServerTargets.isValidHttpUrl(""))
    }
}
