package com.atakmap.android.plowtak.gis

import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class LaneModelTest {

    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(36.0))

    /** 4-lane two-way north-south road (2 lanes each direction). */
    private val road = Road(
        id = "main",
        lanes = 4,
        points = listOf(RoadPoint(36.0, -86.0), RoadPoint(36.01, -86.0))
    )
    private val network = RoadNetwork(listOf(road))

    private val now = 1_700_000_000_000L
    private val freshMs = 45 * 60_000L

    /**
     * A pass along the road at a signed east offset ([offsetM] > 0 = east)
     * with the given heading. Runs the middle stretch of the road.
     */
    private fun pass(
        offsetM: Double,
        headingDeg: Double,
        widthM: Double = 3.0,
        endTimeMs: Long = now
    ): TreatSegment {
        val lon = -86.0 + offsetM / mPerDegLon
        val lats = if (headingDeg < 90.0 || headingDeg > 270.0)
            listOf(36.002, 36.005, 36.008) // northbound
        else
            listOf(36.008, 36.005, 36.002) // southbound
        val points = lats.mapIndexed { i, lat ->
            TrackPoint(lat, lon, endTimeMs - (lats.size - 1 - i) * 30_000L, headingDeg)
        }
        return TreatSegment(
            id = "seg-$offsetM-$headingDeg-$endTimeMs",
            vehicleUid = "V1",
            callsign = "Plow-1",
            stormId = "storm",
            operatorId = "op",
            material = MaterialMode.PLOW_ONLY,
            widthM = widthM,
            points = points,
            startTimeMs = points.first().timeMs,
            endTimeMs = points.last().timeMs
        )
    }

    // ------------------------------------------------------------ matching

    @Test
    fun matchesSegmentToParallelRoad() {
        val match = LaneModel.matchSegment(network, pass(1.75, 0.0))!!
        assertEquals("main", match.road.id)
        assertTrue(match.distanceM < 5.0)
    }

    @Test
    fun rejectsCrossStreetBySkew() {
        // Eastbound pass crossing the north-south road: parallel test fails.
        val lat = 36.005
        val points = listOf(
            TrackPoint(lat, -86.0 - 10.0 / mPerDegLon, now - 10_000, 90.0),
            TrackPoint(lat, -86.0 + 10.0 / mPerDegLon, now, 90.0)
        )
        val crossing = pass(0.0, 0.0).copy(id = "cross", points = points)
        assertNull(LaneModel.matchSegment(network, crossing))
    }

    @Test
    fun rejectsSegmentFarFromAnyRoad() {
        assertNull(LaneModel.matchSegment(network, pass(500.0, 0.0)))
    }

    // ------------------------------------------------------- lane estimate

    @Test
    fun northboundEastOffsetIsForwardLane0() {
        val seg = pass(1.75, 0.0)
        val match = LaneModel.matchSegment(network, seg)!!
        val est = LaneModel.estimateLanes(seg, match)!!
        assertEquals(LaneModel.TravelDirection.FORWARD, est.direction)
        assertEquals(
            setOf(LaneModel.LaneSlot(LaneModel.TravelDirection.FORWARD, 0)),
            est.lanes
        )
    }

    @Test
    fun outerLaneFromLargerOffset() {
        val seg = pass(5.25, 0.0) // 1.5 lane widths east -> lane index 1
        val match = LaneModel.matchSegment(network, seg)!!
        val est = LaneModel.estimateLanes(seg, match)!!
        assertEquals(
            setOf(LaneModel.LaneSlot(LaneModel.TravelDirection.FORWARD, 1)),
            est.lanes
        )
    }

    @Test
    fun southboundWestOffsetIsReverse() {
        val seg = pass(-1.75, 180.0)
        val match = LaneModel.matchSegment(network, seg)!!
        val est = LaneModel.estimateLanes(seg, match)!!
        assertEquals(LaneModel.TravelDirection.REVERSE, est.direction)
        assertEquals(
            setOf(LaneModel.LaneSlot(LaneModel.TravelDirection.REVERSE, 0)),
            est.lanes
        )
    }

    @Test
    fun widePassCoversAdjacentLanes() {
        val seg = pass(1.75, 0.0, widthM = 7.9) // tow plow ~2.3 lanes
        val match = LaneModel.matchSegment(network, seg)!!
        val est = LaneModel.estimateLanes(seg, match)!!
        assertEquals(
            setOf(
                LaneModel.LaneSlot(LaneModel.TravelDirection.FORWARD, 0),
                LaneModel.LaneSlot(LaneModel.TravelDirection.FORWARD, 1)
            ),
            est.lanes
        )
    }

    @Test
    fun onewayRoadNeverEstimatesReverse() {
        val oneway = Road(
            id = "ramp", lanes = 2, oneway = true,
            points = listOf(RoadPoint(36.0, -86.0), RoadPoint(36.01, -86.0))
        )
        val net = RoadNetwork(listOf(oneway))
        val seg = pass(-1.75, 180.0) // "wrong way" heading
        val match = LaneModel.matchSegment(net, seg)!!
        val est = LaneModel.estimateLanes(seg, match)!!
        assertEquals(LaneModel.TravelDirection.FORWARD, est.direction)
    }

    @Test
    fun noLaneEstimateWithoutLaneAttribute() {
        val bare = Road(
            id = "bare",
            points = listOf(RoadPoint(36.0, -86.0), RoadPoint(36.01, -86.0))
        )
        val net = RoadNetwork(listOf(bare))
        val seg = pass(1.75, 0.0)
        val match = LaneModel.matchSegment(net, seg)!!
        assertNull(LaneModel.estimateLanes(seg, match))
    }

    // ------------------------------------------------------------ coverage

    @Test
    fun singlePassOnFourLaneRoadIsQuarterTreated() {
        val coverage = LaneModel.roadLaneCoverage(
            network, listOf(pass(1.75, 0.0)), now, freshMs
        )
        val c = coverage.getValue("main")
        assertEquals(1, c.lanesTreated)
        assertEquals(0.25, c.fraction, 1e-9)
        assertTrue(c.partiallyTreated)
        assertTrue(!c.fullyTreated)
    }

    @Test
    fun allLanesTreatedIsFull() {
        val segments = listOf(
            pass(1.75, 0.0, widthM = 7.9),   // forward lanes 0+1
            pass(-1.75, 180.0, widthM = 7.9) // reverse lanes 0+1
        )
        val c = LaneModel.roadLaneCoverage(network, segments, now, freshMs)
            .getValue("main")
        assertEquals(4, c.lanesTreated)
        assertEquals(1.0, c.fraction, 1e-9)
        assertTrue(c.fullyTreated)
    }

    @Test
    fun stalePassesAreIgnored() {
        val old = pass(1.75, 0.0, endTimeMs = now - freshMs - 60_000L)
        val coverage = LaneModel.roadLaneCoverage(network, listOf(old), now, freshMs)
        assertTrue(coverage.isEmpty())
    }

    @Test
    fun laneMetricsRollup() {
        val metrics = LaneModel.laneMetrics(
            network, listOf(pass(1.75, 0.0)), now, freshMs
        )
        val roadMiles = road.lengthM / 1609.344
        assertEquals(roadMiles * 4, metrics.totalLaneMiles, 0.01)
        assertEquals(roadMiles * 1, metrics.treatedLaneMiles, 0.01)
        assertEquals(0.25, metrics.fraction, 0.01)
        assertEquals(1, metrics.roadsPartial)
        assertEquals(0, metrics.roadsFull)
    }

    @Test
    fun laneMetricsEmptyNetwork() {
        val metrics = LaneModel.laneMetrics(
            RoadNetwork.EMPTY, listOf(pass(1.75, 0.0)), now, freshMs
        )
        assertEquals(0.0, metrics.totalLaneMiles, 0.0)
        assertEquals(0.0, metrics.fraction, 0.0)
    }
}
