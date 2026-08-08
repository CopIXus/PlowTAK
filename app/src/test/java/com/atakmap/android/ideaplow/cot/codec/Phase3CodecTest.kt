package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.model.Material
import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TrackPoint
import com.atakmap.android.ideaplow.model.TreatSegment
import com.atakmap.android.ideaplow.model.VehicleCapability
import com.atakmap.android.ideaplow.model.VehicleStatus
import com.atakmap.android.ideaplow.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `contractor and telemetry ride the coverage detail`() {
        val seg = segment().copy(
            contractor = true,
            applicationRateLbsPerMi = 250.0,
            roadTempF = 28.4,
            spreadMaterial = Material.BRINE
        )
        val back = CoverageCotCodec.decode(
            CoverageCotCodec.encode("storm-1", listOf(seg))
        ).single()
        assertTrue(back.contractor)
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
        assertFalse(back.contractor)
        assertNull(back.applicationRateLbsPerMi)
        assertNull(back.roadTempF)
    }

    // --------------------------------------------------- IdeaPlowDetail

    @Test
    fun `contractor flag rides the PLI detail`() {
        val cap = VehicleCapability.sanitize(
            VehicleCapability.defaultsFor(VehicleType.PLOW, "CTR-Plow", "T-9")
                .copy(contractor = true)
        )
        val detail = IdeaPlowDetail.fromLocalState(
            cap = cap,
            status = VehicleStatus.TREATING,
            bladeDown = true,
            saltOn = false,
            material = Material.SALT,
            headingDeg = 90.0,
            stormId = "storm-1",
            operatorId = "op-1",
            operatorName = "Sam"
        )
        assertTrue(detail.contractor)
        val back = IdeaPlowDetail.fromNode(detail.toNode())!!
        assertTrue(back.contractor)
    }

    @Test
    fun `municipal units do not emit the contractor attribute`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-12", "T-1")
        val detail = IdeaPlowDetail.fromLocalState(
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
        assertFalse(IdeaPlowDetail.fromNode(detail.toNode())!!.contractor)
    }
}
