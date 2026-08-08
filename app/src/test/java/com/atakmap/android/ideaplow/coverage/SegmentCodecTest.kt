package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.Material
import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TrackPoint
import com.atakmap.android.ideaplow.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCodecTest {

    private fun segment(callsign: String = "Plow-12"): TreatSegment {
        val start = 1_700_000_000_000L
        val points = listOf(
            TrackPoint(36.1627001, -86.7816002, start, 87.2),
            TrackPoint(36.1630000, -86.7810000, start + 3000, Double.NaN),
            TrackPoint(36.1635500, -86.7801100, start + 7000, 92.0)
        )
        return TreatSegment(
            id = TreatSegment.makeId("PLOW-12", start),
            vehicleUid = "PLOW-12",
            callsign = callsign,
            stormId = "2026-01-15-1736951234",
            operatorId = "op-9",
            material = MaterialMode.SALT,
            widthM = 3.0,
            points = points,
            startTimeMs = start,
            endTimeMs = start + 7000
        )
    }

    @Test
    fun `segment round-trips through line codec`() {
        val orig = segment()
        val line = SegmentCodec.encode(orig)
        assertTrue(!line.contains("\n"))

        val back = SegmentCodec.decode(line)!!
        assertEquals(orig.id, back.id)
        assertEquals(orig.vehicleUid, back.vehicleUid)
        assertEquals(orig.callsign, back.callsign)
        assertEquals(orig.stormId, back.stormId)
        assertEquals(orig.operatorId, back.operatorId)
        assertEquals(orig.material, back.material)
        assertEquals(orig.widthM, back.widthM, 1e-9)
        assertEquals(orig.startTimeMs, back.startTimeMs)
        assertEquals(orig.endTimeMs, back.endTimeMs)
        assertEquals(orig.points.size, back.points.size)
        for ((p, q) in orig.points.zip(back.points)) {
            assertEquals(p.lat, q.lat, 1e-6)
            assertEquals(p.lon, q.lon, 1e-6)
            assertEquals(p.timeMs, q.timeMs)
            assertEquals(p.headingDeg.isNaN(), q.headingDeg.isNaN())
        }
    }

    @Test
    fun `pipe characters in fields are escaped`() {
        val orig = segment(callsign = "Weird|Callsign")
        val back = SegmentCodec.decode(SegmentCodec.encode(orig))!!
        assertEquals("Weird|Callsign", back.callsign)
    }

    @Test
    fun `spread material round-trips in v2`() {
        val orig = segment().copy(spreadMaterial = Material.BRINE)
        val back = SegmentCodec.decode(SegmentCodec.encode(orig))!!
        assertEquals(Material.BRINE, back.spreadMaterial)

        // Absent material stays null.
        val plain = SegmentCodec.decode(SegmentCodec.encode(segment()))!!
        assertNull(plain.spreadMaterial)
    }

    @Test
    fun `v1 lines from an older install still decode`() {
        val v1 = "1|PLOW-12-1700000000000|PLOW-12|Plow-12|storm|op-9|salt|3|1700000000000|" +
                "36.1627001,-86.7816002,0,87.2;36.1630000,-86.7810000,3000,"
        val back = SegmentCodec.decode(v1)!!
        assertEquals("PLOW-12", back.vehicleUid)
        assertEquals(MaterialMode.SALT, back.material)
        assertNull(back.spreadMaterial)
        assertEquals(2, back.points.size)
    }

    @Test
    fun `contractor and telemetry round-trip in v3`() {
        val orig = segment().copy(
            contractor = true,
            applicationRateLbsPerMi = 250.0,
            roadTempF = 28.4
        )
        val line = SegmentCodec.encode(orig)
        assertTrue(line.startsWith("3|"))
        val back = SegmentCodec.decode(line)!!
        assertTrue(back.contractor)
        assertEquals(250.0, back.applicationRateLbsPerMi!!, 1e-9)
        assertEquals(28.4, back.roadTempF!!, 1e-9)
    }

    @Test
    fun `v3 defaults stay empty for a plain municipal segment`() {
        val back = SegmentCodec.decode(SegmentCodec.encode(segment()))!!
        assertTrue(!back.contractor)
        assertNull(back.applicationRateLbsPerMi)
        assertNull(back.roadTempF)
    }

    @Test
    fun `v2 lines from an older install still decode`() {
        val v2 = "2|PLOW-12-1700000000000|PLOW-12|Plow-12|storm|op-9|salt|3|1700000000000|" +
                "36.1627001,-86.7816002,0,87.2;36.1630000,-86.7810000,3000,|brine"
        val back = SegmentCodec.decode(v2)!!
        assertEquals(Material.BRINE, back.spreadMaterial)
        assertTrue(!back.contractor)
        assertNull(back.applicationRateLbsPerMi)
        assertNull(back.roadTempF)
    }

    @Test
    fun `garbage lines decode to null`() {
        assertNull(SegmentCodec.decode(""))
        assertNull(SegmentCodec.decode("not a segment"))
        assertNull(SegmentCodec.decode("2|wrong|version|line|x|x|salt|3|0|1,2,0,;3,4,5,"))
        // Right shape but single point — invalid segment.
        assertNull(SegmentCodec.decode("1|id|uid|cs|storm|op|salt|3|1000|36.0,-86.0,0,"))
    }
}
