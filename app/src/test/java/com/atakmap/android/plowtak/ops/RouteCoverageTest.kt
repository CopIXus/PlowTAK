package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.SegmentIndex
import com.atakmap.android.plowtak.gis.Road
import com.atakmap.android.plowtak.gis.RoadNetwork
import com.atakmap.android.plowtak.gis.RoadPoint
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCoverageTest {

    // ~1 km of straight east-west road at 36.16 N. 0.001 deg lon ~ 90 m.
    private val routeRoad = Road(
        id = "r1", name = "Main St", lanes = 2, routeId = "RT-7",
        points = listOf(RoadPoint(36.1600, -86.7900), RoadPoint(36.1600, -86.7800))
    )
    private val network = RoadNetwork(listOf(routeRoad))

    private fun segmentAlong(
        lonFrom: Double,
        lonTo: Double,
        stormId: String = "storm-1"
    ): TreatSegment {
        val start = 1_700_000_000_000L
        return TreatSegment(
            id = TreatSegment.makeId("PLOW-12", start) + "-$lonFrom",
            vehicleUid = "PLOW-12",
            callsign = "Plow-12",
            stormId = stormId,
            operatorId = "op-1",
            material = MaterialMode.SALT,
            widthM = 3.0,
            points = listOf(
                TrackPoint(36.1600, lonFrom, start, 90.0),
                TrackPoint(36.1600, lonTo, start + 60_000, 90.0)
            ),
            startTimeMs = start,
            endTimeMs = start + 60_000
        )
    }

    @Test
    fun `untreated route reports zero coverage`() {
        val result = RouteCoverage.forRoute(network, "RT-7", SegmentIndex())
        assertEquals(0.0, result.coveredLengthM, 1e-9)
        assertEquals(0, result.percent)
        assertTrue(result.routeLengthM > 800.0) // ~900 m of road
    }

    @Test
    fun `fully driven route reports near-100 percent`() {
        val index = SegmentIndex()
        index.add(segmentAlong(-86.7900, -86.7800))
        val result = RouteCoverage.forRoute(network, "RT-7", index)
        assertTrue("expected >95%, got ${result.percent}", result.percent >= 95)
    }

    @Test
    fun `half driven route reports about half`() {
        val index = SegmentIndex()
        index.add(segmentAlong(-86.7900, -86.7850))
        val result = RouteCoverage.forRoute(network, "RT-7", index)
        assertTrue("expected ~50%, got ${result.percent}", result.percent in 40..60)
    }

    @Test
    fun `other storms do not count when filtered`() {
        val index = SegmentIndex()
        index.add(segmentAlong(-86.7900, -86.7800, stormId = "last-week"))
        val filtered = RouteCoverage.forRoute(network, "RT-7", index, stormId = "storm-1")
        assertEquals(0, filtered.percent)
        // Unfiltered still sees it.
        val all = RouteCoverage.forRoute(network, "RT-7", index)
        assertTrue(all.percent >= 95)
    }

    @Test
    fun `drawn polyline route works without a network`() {
        val index = SegmentIndex()
        index.add(segmentAlong(-86.7900, -86.7800))
        val result = RouteCoverage.forPolylines(
            listOf(listOf(RoadPoint(36.1600, -86.7900), RoadPoint(36.1600, -86.7800))),
            index
        )
        assertTrue(result.percent >= 95)
    }

    @Test
    fun `unknown route id yields empty result not crash`() {
        val result = RouteCoverage.forRoute(network, "NO-SUCH", SegmentIndex())
        assertEquals(0.0, result.routeLengthM, 1e-9)
        assertEquals(0.0, result.fraction, 1e-9)
    }
}
