package com.atakmap.android.ideaplow.coverage

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
    fun `garbage lines decode to null`() {
        assertNull(SegmentCodec.decode(""))
        assertNull(SegmentCodec.decode("not a segment"))
        assertNull(SegmentCodec.decode("2|wrong|version|line|x|x|salt|3|0|1,2,0,;3,4,5,"))
        // Right shape but single point — invalid segment.
        assertNull(SegmentCodec.decode("1|id|uid|cs|storm|op|salt|3|1000|36.0,-86.0,0,"))
    }
}
