package com.atakmap.android.plowtak.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WidthPresetTest {

    private val plow = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-12", "T-1042")
        .copy(plowWidthM = 3.0, wingWidthM = 4.9, towWidthM = 7.9)

    @Test
    fun `presets map to configured widths`() {
        assertEquals(3.0, plow.widthFor(WidthPreset.STANDARD), 1e-9)
        assertEquals(4.9, plow.widthFor(WidthPreset.WING), 1e-9)
        assertEquals(7.9, plow.widthFor(WidthPreset.TOW), 1e-9)
        assertEquals(
            listOf(WidthPreset.STANDARD, WidthPreset.WING, WidthPreset.TOW),
            plow.availablePresets()
        )
    }

    @Test
    fun `unset preset widths fall back to standard`() {
        val noWing = plow.copy(wingWidthM = 0.0, towWidthM = 0.0)
        assertEquals(3.0, noWing.widthFor(WidthPreset.WING), 1e-9)
        assertEquals(3.0, noWing.widthFor(WidthPreset.TOW), 1e-9)
        assertEquals(listOf(WidthPreset.STANDARD), noWing.availablePresets())
    }

    @Test
    fun `sanitize keeps preset widths for treat types and zeroes others`() {
        val sanitized = VehicleCapability.sanitize(plow)
        assertEquals(4.9, sanitized.wingWidthM, 1e-9)

        val supervisor = VehicleCapability.defaultsFor(VehicleType.SUPERVISOR)
            .copy(wingWidthM = 4.9, towWidthM = 7.9)
        val cleaned = VehicleCapability.sanitize(supervisor)
        assertEquals(0.0, cleaned.wingWidthM, 1e-9)
        assertEquals(0.0, cleaned.towWidthM, 1e-9)
    }

    @Test
    fun `wire names round-trip`() {
        for (p in WidthPreset.entries) {
            assertEquals(p, WidthPreset.fromWireName(p.wireName))
        }
        assertEquals(null, WidthPreset.fromWireName("bogus"))
    }
}
