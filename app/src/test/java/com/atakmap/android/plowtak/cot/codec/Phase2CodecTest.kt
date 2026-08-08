package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.coverage.RoadSide
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.TaskState
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.model.WidthPreset
import com.atakmap.android.plowtak.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneCotCodecTest {

    private val zone = SpecialZone(
        id = "z1", name = "River bridge", type = ZoneType.BRIDGE,
        cycleMultiplier = 0.5, centerLat = 36.1627, centerLon = -86.7816, radiusM = 250.0
    )

    @Test
    fun `circle zone round-trips`() {
        val node = ZoneCotCodec.encode(zone, removed = false, byCallsign = "Sup-1", timeMs = 1000L)
        val update = ZoneCotCodec.decode(node)!!
        assertEquals("z1", update.zone.id)
        assertEquals(ZoneType.BRIDGE, update.zone.type)
        assertEquals(0.5, update.zone.cycleMultiplier, 1e-9)
        assertEquals(250.0, update.zone.radiusM, 1e-6)
        assertEquals(36.1627, update.zone.centerLat, 1e-6)
        assertFalse(update.removed)
        assertEquals("Sup-1", update.by)
    }

    @Test
    fun `polygon zone round-trips`() {
        val poly = zone.copy(
            polygon = listOf(36.0 to -86.0, 36.1 to -86.0, 36.1 to -85.9)
        )
        val update = ZoneCotCodec.decode(
            ZoneCotCodec.encode(poly, removed = false, byCallsign = "Sup-1", timeMs = 1000L)
        )!!
        assertTrue(update.zone.isPolygon)
        assertEquals(3, update.zone.polygon.size)
        assertEquals(36.1 to -85.9, update.zone.polygon[2])
    }

    @Test
    fun `removal flag round-trips`() {
        val update = ZoneCotCodec.decode(
            ZoneCotCodec.encode(zone, removed = true, byCallsign = "Sup-1", timeMs = 1000L)
        )!!
        assertTrue(update.removed)
    }

    @Test
    fun `invalid zone detail returns null`() {
        assertNull(ZoneCotCodec.decode(DetailNode(DetailNode.PLOWTAK)))
        val missingKind = DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
            listOf(DetailNode("zone", mapOf("id" to "z1", "lat" to "36", "lon" to "-86")))
        )
        assertNull(ZoneCotCodec.decode(missingKind))
    }
}

class TaskCotCodecTest {

    private val task = TaskEvent(
        uid = "plowtak-task-SUP-1-1000",
        targetVehicleUid = "PLOWTAK-T-1", targetCallsign = "Plow-1",
        assignedBy = "Sup-1", kind = TaskKind.HAZARD, refId = "hz-4",
        lat = 36.0, lon = -86.0, description = "Stranded car on Rt 9",
        timeMs = 1000L, state = TaskState.ACKED, stateTimeMs = 2000L, stateBy = "Plow-1"
    )

    @Test
    fun `task round-trips with state`() {
        val back = TaskCotCodec.decode(TaskCotCodec.encode(task), task.uid, 36.0, -86.0)!!
        assertEquals(task.uid, back.uid)
        assertEquals("PLOWTAK-T-1", back.targetVehicleUid)
        assertEquals(TaskKind.HAZARD, back.kind)
        assertEquals("hz-4", back.refId)
        assertEquals(TaskState.ACKED, back.state)
        assertEquals(2000L, back.stateTimeMs)
        assertEquals("Plow-1", back.stateBy)
        assertEquals("Stranded car on Rt 9", back.description)
        assertEquals(1000L, back.timeMs)
        // Escalation never rides the wire.
        assertFalse(back.escalated)
    }

    @Test
    fun `invalid task detail returns null`() {
        assertNull(TaskCotCodec.decode(DetailNode(DetailNode.PLOWTAK), "uid", 0.0, 0.0))
        val missingTarget = DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
            listOf(DetailNode("task", mapOf("kind" to "segment", "time" to "1000")))
        )
        assertNull(TaskCotCodec.decode(missingTarget, "uid", 0.0, 0.0))
    }
}

class RoadConditionCotCodecTest {

    @Test
    fun `condition report round-trips`() {
        val report = RoadConditionReport(
            uid = "plowtak-cond-PLOWTAK-T-1-1000",
            condition = RoadCondition.ICE,
            reporterUid = "PLOWTAK-T-1", reporterCallsign = "Plow-1",
            lat = 36.0, lon = -86.0, timeMs = 1000L, stormId = "storm-A"
        )
        val back = RoadConditionCotCodec.decode(
            RoadConditionCotCodec.encode(report), report.uid, 36.0, -86.0
        )!!
        assertEquals(RoadCondition.ICE, back.condition)
        assertEquals("Plow-1", back.reporterCallsign)
        assertEquals("storm-A", back.stormId)
        assertEquals(1000L, back.timeMs)
    }

    @Test
    fun `unknown condition returns null`() {
        val bogus = DetailNode(
            DetailNode.PLOWTAK, emptyMap(),
            listOf(DetailNode("condition", mapOf("state" to "lava")))
        )
        assertNull(RoadConditionCotCodec.decode(bogus, "uid", 0.0, 0.0))
    }
}

class Phase2DetailExtensionsTest {

    @Test
    fun `hazard photo attribute round-trips`() {
        val hazard = HazardEvent(
            uid = "hz-1", type = HazardType.TREE_WIRES_DOWN,
            reporterUid = "PLOWTAK-T-1", reporterCallsign = "Plow-1",
            lat = 36.0, lon = -86.0, timeMs = 1000L, stormId = "s",
            photoFile = "hz-1-photo.jpg"
        )
        val back = HazardCotCodec.decode(HazardCotCodec.encode(hazard), "hz-1", 36.0, -86.0)!!
        assertTrue(back.hasPhoto)
        assertEquals("hz-1-photo.jpg", back.photoFile)

        // No photo — attribute omitted, decodes empty.
        val plain = hazard.copy(photoFile = "")
        val backPlain = HazardCotCodec.decode(HazardCotCodec.encode(plain), "hz-1", 36.0, -86.0)!!
        assertFalse(backPlain.hasPhoto)
    }

    @Test
    fun `coverage segment spread material round-trips`() {
        val seg = TreatSegment(
            id = "s1", vehicleUid = "PLOWTAK-T-1", callsign = "Plow-1",
            stormId = "storm", operatorId = "op", material = MaterialMode.PLOW_AND_SALT,
            widthM = 3.0,
            points = listOf(TrackPoint(36.0, -86.0, 1000L, 45.0), TrackPoint(36.001, -86.0, 2000L, 45.0)),
            startTimeMs = 1000L, endTimeMs = 2000L, spreadMaterial = Material.PREWET
        )
        val back = CoverageCotCodec.decode(CoverageCotCodec.encode("storm", listOf(seg))).single()
        assertEquals(Material.PREWET, back.spreadMaterial)
    }

    @Test
    fun `pli detail carries width preset and side`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-12", "T-1042")
            .copy(hasSalt = true, plowWidthM = 3.0, wingWidthM = 4.9, towWidthM = 7.9)
        val detail = PlowTakDetail.fromLocalState(
            cap = cap, status = VehicleStatus.TREATING,
            bladeDown = true, saltOn = false, material = Material.SALT,
            headingDeg = 190.0, stormId = "s", operatorId = "op", operatorName = "Jane",
            widthPreset = WidthPreset.TOW
        )
        // Preset drives the advertised effective width.
        assertEquals(7.9, detail.plowWidthM, 1e-9)
        assertEquals(RoadSide.LEFT, detail.side)

        val node = detail.toNode()
        val status = node.firstChild("status")!!
        assertEquals("tow", status.attr("preset"))
        val geom = node.firstChild("geom")!!
        assertEquals("left", geom.attr("side"))

        val back = PlowTakDetail.fromNode(node)!!
        assertEquals(WidthPreset.TOW, back.widthPreset)
        assertEquals(RoadSide.LEFT, back.side)
    }

    @Test
    fun `non-treating pli omits side`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-12", "T-1042")
        val detail = PlowTakDetail.fromLocalState(
            cap = cap, status = VehicleStatus.DEADHEAD,
            bladeDown = false, saltOn = false, material = Material.SALT,
            headingDeg = 45.0, stormId = "", operatorId = "", operatorName = ""
        )
        assertNull(detail.toNode().firstChild("geom")!!.attr("side"))
    }

    @Test
    fun `legacy pli without preset defaults to standard`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-12", "T-1042")
        val node = PlowTakDetail.fromLocalState(
            cap, VehicleStatus.DEADHEAD, false, false, Material.SALT,
            Double.NaN, "", "", ""
        ).toNode()
        // Strip the preset attribute to simulate a Phase 1 sender.
        val stripped = DetailNode(
            node.name, node.attributes,
            node.children.map { child ->
                if (child.name == "status") DetailNode(
                    child.name, child.attributes - "preset", child.children
                ) else child
            }
        )
        assertEquals(WidthPreset.STANDARD, PlowTakDetail.fromNode(stripped)!!.widthPreset)
    }
}
