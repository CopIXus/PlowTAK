package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TrackPoint
import com.atakmap.android.ideaplow.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentIndexTest {

    private fun seg(id: String, lat: Double, lon: Double, pts: Int = 3) = TreatSegment(
        id = id, vehicleUid = "P1", callsign = "Plow-1", stormId = "s", operatorId = "op",
        material = MaterialMode.SALT, widthM = 3.0,
        points = (0 until pts).map { TrackPoint(lat + it * 0.0001, lon, it * 1000L) },
        startTimeMs = 0L, endTimeMs = (pts - 1) * 1000L
    )

    @Test
    fun `nearby returns segments in range and skips far ones`() {
        val index = SegmentIndex()
        index.add(seg("near", 36.0, -86.0))
        index.add(seg("far", 36.5, -86.0)) // ~55 km away

        val hits = index.nearby(36.0, -86.0, 500.0)
        assertEquals(listOf("near"), hits.map { it.id })
    }

    @Test
    fun `nearSegment excludes the probe segment itself`() {
        val index = SegmentIndex()
        val a = seg("a", 36.0, -86.0)
        index.add(a)
        index.add(seg("b", 36.0002, -86.0))

        val hits = index.nearSegment(a, 100.0)
        assertEquals(listOf("b"), hits.map { it.id })
    }

    @Test
    fun `remove and clear drop segments from queries`() {
        val index = SegmentIndex()
        index.add(seg("a", 36.0, -86.0))
        index.add(seg("b", 36.0, -86.001))
        assertEquals(2, index.size())

        index.remove("a")
        assertEquals(listOf("b"), index.nearby(36.0, -86.0, 1000.0).map { it.id })

        index.clear()
        assertEquals(0, index.size())
        assertTrue(index.nearby(36.0, -86.0, 1000.0).isEmpty())
    }

    @Test
    fun `re-adding a segment does not duplicate`() {
        val index = SegmentIndex()
        index.add(seg("a", 36.0, -86.0))
        index.add(seg("a", 36.0, -86.0))
        assertEquals(1, index.nearby(36.0, -86.0, 1000.0).size)
    }

    @Test
    fun `long segment is found from any of its cells`() {
        val index = SegmentIndex()
        // ~5.5 km north-south segment spanning many cells.
        val long = TreatSegment(
            id = "long", vehicleUid = "P1", callsign = "Plow-1", stormId = "s",
            operatorId = "op", material = MaterialMode.SALT, widthM = 3.0,
            points = (0..50).map { TrackPoint(36.0 + it * 0.001, -86.0, it * 1000L) },
            startTimeMs = 0L, endTimeMs = 50_000L
        )
        index.add(long)
        assertEquals(1, index.nearby(36.0, -86.0, 200.0).size)   // south end
        assertEquals(1, index.nearby(36.05, -86.0, 200.0).size)  // north end
        assertEquals(1, index.nearby(36.025, -86.0, 200.0).size) // middle
    }

    @Test
    fun `query scales with locality not store size`() {
        val index = SegmentIndex()
        // 2000 segments spread over a wide area.
        for (i in 0 until 2000) {
            index.add(seg("s$i", 36.0 + (i % 50) * 0.01, -86.0 + (i / 50) * 0.01))
        }
        val hits = index.nearby(36.0, -86.0, 300.0)
        // Only the handful in the local cells come back, not thousands.
        assertTrue("expected few hits, got ${hits.size}", hits.size < 20)
    }
}
