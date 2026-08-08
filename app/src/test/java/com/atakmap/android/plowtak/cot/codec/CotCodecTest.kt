package com.atakmap.android.plowtak.cot.codec

import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CotCodecTest {

    // ------------------------------------------------------ PlowTakDetail

    @Test
    fun `pli detail round-trips`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.PLOW, "Plow-12", "P12")
            .copy(hasSalt = true, plowWidthM = 3.7)
        val detail = PlowTakDetail.fromLocalState(
            cap = cap,
            status = VehicleStatus.TREATING,
            bladeDown = true,
            saltOn = false,
            material = Material.BRINE,
            headingDeg = 87.2,
            stormId = "2026-01-15-1736951234",
            operatorId = "op-77",
            operatorName = "J. Smith"
        )

        val node = detail.toNode()
        assertEquals(DetailNode.PLOWTAK, node.name)
        assertEquals("treating", node.firstChild("vehicle")?.attr("role"))
        assertEquals("down", node.firstChild("status")?.attr("blade"))
        assertEquals("off", node.firstChild("status")?.attr("salt"))

        val decoded = PlowTakDetail.fromNode(node)
        assertNotNull(decoded)
        assertEquals(detail, decoded)
    }

    @Test
    fun `salt-only detail reports blade none`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.SALT_ONLY, "Salt-3", "S3")
        val node = PlowTakDetail.fromLocalState(
            cap, VehicleStatus.TREATING, bladeDown = false, saltOn = true,
            material = Material.SALT, headingDeg = 10.0,
            stormId = "", operatorId = "", operatorName = ""
        ).toNode()

        assertEquals("none", node.firstChild("status")?.attr("blade"))
        assertEquals("on", node.firstChild("status")?.attr("salt"))
        // Empty ops/operator elements are omitted entirely.
        assertNull(node.firstChild("ops"))
        assertNull(node.firstChild("operator"))
    }

    @Test
    fun `observer detail decodes as viewer role`() {
        val cap = VehicleCapability.defaultsFor(VehicleType.OBSERVER, "Fire-Obs", "O1")
        val node = PlowTakDetail.fromLocalState(
            cap, VehicleStatus.OFF_DUTY, false, false,
            Material.SALT, Double.NaN, "", "", ""
        ).toNode()
        assertEquals("viewer", node.firstChild("vehicle")?.attr("role"))
        val decoded = PlowTakDetail.fromNode(node)!!
        assertEquals(VehicleType.OBSERVER, decoded.vehicleType)
        assertTrue(decoded.headingDeg.isNaN())
    }

    @Test
    fun `invalid detail returns null`() {
        assertNull(PlowTakDetail.fromNode(DetailNode("something_else")))
        assertNull(PlowTakDetail.fromNode(DetailNode(DetailNode.PLOWTAK))) // no vehicle child
    }

    // ---------------------------------------------------- CoverageCotCodec

    private fun segment(
        uid: String = "PLOW-12",
        start: Long = 1_700_000_000_000L,
        pointCount: Int = 5
    ): TreatSegment {
        val points = (0 until pointCount).map {
            TrackPoint(36.0 + it * 0.0002, -86.0 + it * 0.0001, start + it * 2000L, 45.0)
        }
        return TreatSegment(
            id = TreatSegment.makeId(uid, start),
            vehicleUid = uid,
            callsign = "Plow-12",
            stormId = "storm-A",
            operatorId = "op-9",
            material = MaterialMode.PLOW_AND_SALT,
            widthM = 3.7,
            points = points,
            startTimeMs = start,
            endTimeMs = points.last().timeMs
        )
    }

    @Test
    fun `coverage batch round-trips`() {
        val segs = listOf(segment(), segment("SALT-3", 1_700_000_100_000L, 8))
        val node = CoverageCotCodec.encode("storm-A", segs)
        val decoded = CoverageCotCodec.decode(node)

        assertEquals(2, decoded.size)
        for ((orig, back) in segs.zip(decoded)) {
            assertEquals(orig.id, back.id)
            assertEquals(orig.vehicleUid, back.vehicleUid)
            assertEquals(orig.stormId, back.stormId)
            assertEquals(orig.operatorId, back.operatorId)
            assertEquals(orig.material, back.material)
            assertEquals(orig.widthM, back.widthM, 0.05)
            assertEquals(orig.points.size, back.points.size)
            assertEquals(orig.startTimeMs, back.startTimeMs)
            assertEquals(orig.endTimeMs, back.endTimeMs)
            for ((p, q) in orig.points.zip(back.points)) {
                assertEquals(p.lat, q.lat, 1e-6)
                assertEquals(p.lon, q.lon, 1e-6)
                assertEquals(p.timeMs, q.timeMs)
            }
        }
    }

    @Test
    fun `oversized segment is thinned on the wire`() {
        val big = segment(pointCount = 150)
        val node = CoverageCotCodec.encode("storm-A", listOf(big))
        val decoded = CoverageCotCodec.decode(node)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].points.size <= CoverageCotCodec.MAX_POINTS_PER_WIRE_SEGMENT)
        assertTrue(decoded[0].points.size >= 2)
    }

    @Test
    fun `decode ignores malformed segments`() {
        val good = CoverageCotCodec.encode("s", listOf(segment()))
        val withJunk = good.copy(
            children = listOf(
                good.children[0].copy(
                    children = good.children[0].children +
                            DetailNode("segment", mapOf("id" to "junk")) // no uid/start/points
                )
            )
        )
        assertEquals(1, CoverageCotCodec.decode(withJunk).size)
    }

    @Test
    fun `non-coverage detail decodes empty`() {
        assertTrue(CoverageCotCodec.decode(DetailNode(DetailNode.PLOWTAK)).isEmpty())
    }

    // ------------------------------------------------------- AlertCotCodec

    @Test
    fun `alert round-trips through ack workflow`() {
        val alert = AlertEvent(
            uid = AlertEvent.makeUid("PLOW-12"),
            vehicleUid = "PLOW-12",
            callsign = "Plow-12",
            vehicleType = VehicleType.PLOW,
            lat = 36.16, lon = -86.78,
            timeMs = 1_700_000_000_000L,
            state = AlertState.ACKNOWLEDGED,
            handledBy = "Sup-1",
            bladeDown = true, saltOn = false
        )
        val node = AlertCotCodec.encode(alert)
        val decoded = AlertCotCodec.decode(node, alert.uid, alert.lat, alert.lon)
        assertEquals(alert, decoded)
    }

    // ------------------------------------------------------- StormCotCodec

    @Test
    fun `storm session round-trips`() {
        val session = StormSession("2026-01-15-1736951234", 1_736_951_234_000L, 0L, "Sup-1")
        assertEquals(session, StormCotCodec.decode(StormCotCodec.encode(session)))

        val ended = session.copy(endTimeMs = 1_736_999_999_000L)
        assertEquals(ended, StormCotCodec.decode(StormCotCodec.encode(ended)))
    }

    // ------------------------------------------------------ HazardCotCodec

    @Test
    fun `hazard round-trips`() {
        val hazard = HazardEvent(
            uid = "hz-1",
            type = HazardType.TREE_WIRES_DOWN,
            reporterUid = "PLOW-12",
            reporterCallsign = "Plow-12",
            lat = 36.1, lon = -86.7,
            timeMs = 1_700_000_000_000L,
            stormId = "storm-A"
        )
        val decoded = HazardCotCodec.decode(
            HazardCotCodec.encode(hazard), hazard.uid, hazard.lat, hazard.lon
        )
        assertEquals(hazard, decoded)
    }
}
