package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionModelTest {

    // ------------------------------------------------------------- binning

    @Test
    fun `bearing bins wrap and center on heading`() {
        assertEquals(0, DirectionModel.bearingBin(0.0))
        assertEquals(0, DirectionModel.bearingBin(359.9)) // wraps into bin 0
        assertEquals(0, DirectionModel.bearingBin(11.0))  // within half-bin
        assertEquals(1, DirectionModel.bearingBin(22.5))
        assertEquals(8, DirectionModel.bearingBin(180.0))
        assertEquals(-1, DirectionModel.bearingBin(Double.NaN))
    }

    @Test
    fun `opposite bins detected within one bin of 180`() {
        val north = DirectionModel.bearingBin(0.0)
        val south = DirectionModel.bearingBin(180.0)
        val southish = DirectionModel.bearingBin(200.0)
        val east = DirectionModel.bearingBin(90.0)
        assertTrue(DirectionModel.binsOpposite(north, south))
        assertTrue(DirectionModel.binsOpposite(north, southish))
        assertFalse(DirectionModel.binsOpposite(north, east))
        assertFalse(DirectionModel.binsOpposite(-1, south))
    }

    @Test
    fun `opposite heading tolerance`() {
        assertTrue(DirectionModel.isOppositeHeading(0.0, 180.0))
        assertTrue(DirectionModel.isOppositeHeading(0.0, 130.0)) // within 60 deg tolerance
        assertFalse(DirectionModel.isOppositeHeading(0.0, 90.0))
        assertFalse(DirectionModel.isOppositeHeading(0.0, Double.NaN))
        // Tight tolerance rejects a diagonal.
        assertFalse(DirectionModel.isOppositeHeading(0.0, 150.0, toleranceDeg = 20.0))
    }

    // -------------------------------------------------------------- side

    @Test
    fun `side of road from heading under right-hand traffic`() {
        // Northbound / eastbound travel along the axis paints the right side.
        assertEquals(RoadSide.RIGHT, DirectionModel.sideOfRoad(0.0))
        assertEquals(RoadSide.RIGHT, DirectionModel.sideOfRoad(90.0))
        assertEquals(RoadSide.RIGHT, DirectionModel.sideOfRoad(179.0))
        // Southbound / westbound is the reverse direction: left of the axis.
        assertEquals(RoadSide.LEFT, DirectionModel.sideOfRoad(180.0))
        assertEquals(RoadSide.LEFT, DirectionModel.sideOfRoad(270.0))
        assertEquals(RoadSide.UNKNOWN, DirectionModel.sideOfRoad(Double.NaN))
    }

    @Test
    fun `opposite passes land on opposite sides`() {
        val nb = DirectionModel.sideOfRoad(10.0)
        val sb = DirectionModel.sideOfRoad(190.0)
        assertTrue(nb != sb)
    }

    // -------------------------------------------------- direction pairing

    /** Straight north-south road at lon -86: northbound pass. */
    private fun northboundSeg(id: String = "nb-1", endMs: Long = 100_000L) = TreatSegment(
        id = id, vehicleUid = "P1", callsign = "Plow-1", stormId = "s", operatorId = "op",
        material = MaterialMode.PLOW_ONLY, widthM = 3.0,
        points = (0..10).map { TrackPoint(36.0 + it * 0.0005, -86.0, it * 10_000L, 0.0) },
        startTimeMs = 0L, endTimeMs = endMs
    )

    /** Same corridor, southbound (offset ~11 m east — the other lane). */
    private fun southboundSeg(id: String = "sb-1", endMs: Long = 100_000L) = TreatSegment(
        id = id, vehicleUid = "P2", callsign = "Plow-2", stormId = "s", operatorId = "op",
        material = MaterialMode.PLOW_ONLY, widthM = 3.0,
        points = (0..10).map { TrackPoint(36.005 - it * 0.0005, -85.9999, it * 10_000L, 180.0) },
        startTimeMs = 0L, endTimeMs = endMs
    )

    /** A parallel road ~1.1 km east — NOT the same corridor. */
    private fun farParallelSeg() = TreatSegment(
        id = "far-1", vehicleUid = "P3", callsign = "Plow-3", stormId = "s", operatorId = "op",
        material = MaterialMode.PLOW_ONLY, widthM = 3.0,
        points = (0..10).map { TrackPoint(36.005 - it * 0.0005, -85.99, it * 10_000L, 180.0) },
        startTimeMs = 0L, endTimeMs = 100_000L
    )

    @Test
    fun `northbound-only road is ONE_WAY_ONLY`() {
        val status = DirectionModel.directionStatus(
            northboundSeg(), emptyList(), nowMs = 200_000L, freshWithinMs = 3_600_000L
        )
        assertEquals(DirectionStatus.ONE_WAY_ONLY, status)
    }

    @Test
    fun `fresh southbound pass pairs the northbound segment`() {
        val status = DirectionModel.directionStatus(
            northboundSeg(), listOf(southboundSeg()),
            nowMs = 200_000L, freshWithinMs = 3_600_000L
        )
        assertEquals(DirectionStatus.PAIRED, status)
    }

    @Test
    fun `stale southbound pass does not pair`() {
        val status = DirectionModel.directionStatus(
            northboundSeg(), listOf(southboundSeg(endMs = 100_000L)),
            nowMs = 10_000_000L, freshWithinMs = 1_000_000L
        )
        assertEquals(DirectionStatus.ONE_WAY_ONLY, status)
    }

    @Test
    fun `parallel road a kilometer away does not pair`() {
        val status = DirectionModel.directionStatus(
            northboundSeg(), listOf(farParallelSeg()),
            nowMs = 200_000L, freshWithinMs = 3_600_000L
        )
        assertEquals(DirectionStatus.ONE_WAY_ONLY, status)
    }

    @Test
    fun `same-direction second pass does not pair`() {
        val second = northboundSeg(id = "nb-2")
        val status = DirectionModel.directionStatus(
            northboundSeg(), listOf(second),
            nowMs = 200_000L, freshWithinMs = 3_600_000L
        )
        assertEquals(DirectionStatus.ONE_WAY_ONLY, status)
    }

    @Test
    fun `segment itself is excluded from candidates`() {
        val seg = northboundSeg()
        val status = DirectionModel.directionStatus(
            seg, listOf(seg), nowMs = 200_000L, freshWithinMs = 3_600_000L
        )
        assertEquals(DirectionStatus.ONE_WAY_ONLY, status)
    }

    @Test
    fun `heading falls back to point-to-point bearing`() {
        // No stored headings at all — bearing between points must drive it.
        val noHeadings = northboundSeg().let { seg ->
            seg.copy(points = seg.points.map { it.copy(headingDeg = Double.NaN) })
        }
        val status = DirectionModel.directionStatus(
            noHeadings, listOf(southboundSeg()),
            nowMs = 200_000L, freshWithinMs = 3_600_000L
        )
        assertEquals(DirectionStatus.PAIRED, status)
    }
}
