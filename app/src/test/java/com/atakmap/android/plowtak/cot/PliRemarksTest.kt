package com.atakmap.android.plowtak.cot

import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.model.WidthPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PliRemarksTest {

    private val plowCap = VehicleCapability(
        type = VehicleType.PLOW,
        hasBlade = true,
        hasSalt = true,
        canTreat = true,
        canManageStorm = true,
        canSendDistress = true,
        publishPresence = true,
        plowWidthM = VehicleCapability.feetToMeters(11.0),
        wingLeftWidthM = VehicleCapability.feetToMeters(8.0),
        wingRightWidthM = VehicleCapability.feetToMeters(8.0),
        towWidthM = 0.0,
        callsign = "Plow-12",
        vehicleId = "T-12"
    )

    @Test
    fun treatingOnShiftWithWingsAndSpreader() {
        val eq = EquipmentState(
            bladeDown = true,
            saltOn = true,
            material = Material.SALT,
            wingLeftExtended = true,
            wingRightExtended = false,
            widthPreset = WidthPreset.STANDARD
        )
        val text = PliRemarks.format(
            statusLabel = "Treating",
            onShift = true,
            stormName = "I-81 North overnight",
            capability = plowCap,
            equipment = eq
        )
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertEquals("Treating | Shift on | Storm I-81 North overnight", lines[0])
        assertTrue(lines[1].contains("plow 11ft"))
        assertTrue(lines[1].contains("L wing 8ft"))
        assertTrue(lines[1].contains("tow not fitted"))
        assertTrue(lines[1].contains("spreader yes"))
        assertTrue(lines[2].contains("blade down"))
        assertTrue(lines[2].contains("L wing out"))
        assertTrue(lines[2].contains("R wing up"))
        assertTrue(lines[2].contains("spreader on (Salt)"))
        assertTrue(!lines[2].contains("tow"))
    }

    @Test
    fun offShiftNoStormUnfittedTow() {
        val eq = EquipmentState(bladeDown = false, saltOn = false)
        val text = PliRemarks.format(
            statusLabel = "Off duty",
            onShift = false,
            stormName = null,
            capability = plowCap,
            equipment = eq
        )
        val lines = text.lines()
        assertEquals("Off duty | Shift off | Storm No storm", lines[0])
        assertTrue(lines[2].contains("blade up"))
        assertTrue(lines[2].contains("spreader off"))
    }

    @Test
    fun towDownWhenFittedAndDeployed() {
        val cap = plowCap.copy(towWidthM = VehicleCapability.feetToMeters(26.0))
        val eq = EquipmentState(widthPreset = WidthPreset.TOW, bladeDown = true)
        val text = PliRemarks.format(
            statusLabel = "Treating",
            onShift = true,
            stormName = "Storm A",
            capability = cap,
            equipment = eq
        )
        assertTrue(text.contains("tow 26ft"))
        assertTrue(text.contains("tow down"))
    }
}
