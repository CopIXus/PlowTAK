package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-compat tests for the Phase 3 additions to existing codecs. */
class Phase3CodecTest {

    private fun segment() = TreatSegment(
        id = "PLOW-12-1000",
        vehicleUid = "PLOW-12",
        callsign = "Plow-12",
        stormId = "storm-1",
        operatorId = "op-1",
        material = MaterialMode.SALT,
        widthM = 3.0,
        points = listOf(
            TrackPoint(36.0, -86.0, 1000L, 45.0),
            TrackPoint(36.001, -86.0, 2000L, 45.0)
        ),
        startTimeMs = 1000L,
        endTimeMs = 2000L
    )

    // ------------------------------------------------- CoverageCotCodec

    @Test
    fun `telemetry rides the coverage detail`() {
        val seg = segment().copy(
            applicationRateLbsPerMi = 250.0,
            roadTempF = 28.4,
            spreadMaterial = Material.BRINE
        )
        val node = CoverageCotCodec.encode("storm-1", listOf(seg))
        assertNull(node.firstChild("coverage")!!.childrenNamed("segment").single().attr("contractor"))
        val back = CoverageCotCodec.decode(node).single()
        assertEquals(250.0, back.applicationRateLbsPerMi!!, 0.1)
        assertEquals(28.4, back.roadTempF!!, 0.1)
    }

    @Test
    fun `plain segments omit the new attributes entirely`() {
        val node = CoverageCotCodec.encode("storm-1", listOf(segment()))
        val segNode = node.firstChild("coverage")!!.childrenNamed("segment").single()
        assertNull(segNode.attr("contractor"))
        assertNull(segNode.attr("rate"))
        assertNull(segNode.attr("temp"))

        val back = CoverageCotCodec.decode(node).single()
        assertNull(back.applicationRateLbsPerMi)
        assertNull(back.roadTempF)
    }

    // --------------------------------------------------- PlowTakDetail

    @Test
    fun `units do not emit a contractor attribute`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-12", "T-1")
        val detail = PlowTakDetail.fromLocalState(
            cap = cap,
            status = VehicleStatus.DEADHEAD,
            bladeDown = false,
            saltOn = false,
            material = Material.SALT,
            headingDeg = Double.NaN,
            stormId = "",
            operatorId = "",
            operatorName = ""
        )
        val vehicleNode = detail.toNode().firstChild("vehicle")!!
        assertNull(vehicleNode.attr("contractor"))
        assertNotNull(PlowTakDetail.fromNode(detail.toNode()))
    }
}
