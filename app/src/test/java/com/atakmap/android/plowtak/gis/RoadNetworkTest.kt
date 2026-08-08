package com.atakmap.android.plowtak.gis

import com.atakmap.android.plowtak.model.RoutePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class RoadNetworkTest {

    // ~meters per degree at the test latitude.
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(36.0))

    /** North-south 4-lane P1 road on route R-9 along lon -86. */
    private val mainSt = Road(
        id = "main",
        name = "Main St",
        lanes = 4,
        priority = RoutePriority.P1,
        routeId = "R-9",
        oneway = false,
        points = listOf(RoadPoint(36.0, -86.0), RoadPoint(36.01, -86.0))
    )

    /** Parallel 2-lane road ~500 m east, same route. */
    private val elmSt = Road(
        id = "elm",
        name = "Elm St",
        lanes = 2,
        priority = RoutePriority.P3,
        routeId = "R-9",
        oneway = false,
        points = listOf(
            RoadPoint(36.0, -86.0 + 500.0 / mPerDegLon),
            RoadPoint(36.01, -86.0 + 500.0 / mPerDegLon)
        )
    )

    private val network = RoadNetwork(listOf(mainSt, elmSt))

    @Test
    fun roadLengthMatchesGeodesy() {
        // 0.01 deg of latitude is ~1113 m.
        assertEquals(1113.2, mainSt.lengthM, 5.0)
    }

    @Test
    fun nearestFindsClosestRoad() {
        val eastOffsetM = 3.5
        val match = network.nearest(36.005, -86.0 + eastOffsetM / mPerDegLon)!!
        assertEquals("main", match.road.id)
        assertEquals(eastOffsetM, match.distanceM, 0.5)
    }

    @Test
    fun lateralOffsetSignedRightOfDigitizedDirection() {
        // Road digitized south->north; east of centerline = right = positive.
        val east = network.nearest(36.005, -86.0 + 3.5 / mPerDegLon)!!
        assertTrue("east should be positive", east.lateralOffsetM > 0)
        val west = network.nearest(36.005, -86.0 - 3.5 / mPerDegLon)!!
        assertTrue("west should be negative", west.lateralOffsetM < 0)
        assertEquals(0.0, east.roadBearingDeg, 1.0) // northbound bearing
    }

    @Test
    fun nearestRespectsMaxDistance() {
        // 100 m east of Main, max 30 m -> no match (Elm is 400 m farther).
        assertNull(network.nearest(36.005, -86.0 + 100.0 / mPerDegLon, maxDistM = 30.0))
    }

    @Test
    fun nearestPicksTheCloserOfTwoRoads() {
        // 100 m west of Elm = 400 m east of Main.
        val match = network.nearest(
            36.005, -86.0 + 400.0 / mPerDegLon, maxDistM = 200.0
        )!!
        assertEquals("elm", match.road.id)
    }

    @Test
    fun routeQueries() {
        assertEquals(listOf("R-9"), network.routeIds())
        assertEquals(2, network.roadsForRoute("R-9").size)
        assertEquals(
            mainSt.lengthM + elmSt.lengthM,
            network.routeLengthM("R-9"),
            0.001
        )
        assertTrue(network.roadsForRoute("nope").isEmpty())
    }

    @Test
    fun priorityAtPoint() {
        assertEquals(RoutePriority.P1, network.priorityAt(36.005, -86.0))
        assertNull(network.priorityAt(36.5, -86.0)) // far away
    }

    @Test
    fun lanesPerDirection() {
        assertEquals(2, mainSt.lanesPerDirection) // 4 two-way -> 2 each way
        assertEquals(1, elmSt.lanesPerDirection)
        assertEquals(
            3,
            mainSt.copy(lanes = 3, oneway = true).lanesPerDirection
        )
        assertEquals(2, mainSt.copy(lanes = 3).lanesPerDirection) // odd rounds up
        assertEquals(0, mainSt.copy(lanes = 0).lanesPerDirection)
    }

    @Test
    fun emptyNetworkIsSafe() {
        assertTrue(RoadNetwork.EMPTY.isEmpty())
        assertNull(RoadNetwork.EMPTY.nearest(36.0, -86.0))
        assertTrue(RoadNetwork.EMPTY.routeIds().isEmpty())
    }
}
