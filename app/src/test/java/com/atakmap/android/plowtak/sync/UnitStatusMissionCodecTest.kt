package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitStatusMissionCodecTest {

    @Test
    fun statusRoundTripPreservesBladeAndSpread() {
        val v = PlowVehicle(
            uid = "PLOW-1",
            callsign = "Plow-1",
            type = VehicleType.PLOW,
            status = VehicleStatus.TREATING,
            lat = 36.1,
            lon = -86.7,
            headingDeg = 90.0,
            lastUpdateMs = 1_700_000_000_000L,
            hasBlade = true,
            hasSalt = true,
            bladeDown = true,
            saltOn = true,
            stormId = "storm-A"
        )
        val decoded = UnitStatusMissionCodec.decodeStatus(
            UnitStatusMissionCodec.encodeStatus(v, "storm-A")
        )!!
        assertEquals(true, decoded.bladeDown)
        assertEquals(true, decoded.saltOn)
        assertEquals(VehicleStatus.TREATING, decoded.status)
    }
}
