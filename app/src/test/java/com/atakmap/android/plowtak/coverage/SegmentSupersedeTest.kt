package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class SegmentSupersedeTest {

    private val lat0 = 36.85
    private val lon0 = -75.98

    /** Eastbound line of [meters] at [offsetNorthM], heading stored on every point. */
    private fun east(
        id: String,
        meters: Double,
        endMs: Long,
        offsetNorthM: Double = 0.0,
        heading: Double = 90.0,
        material: MaterialMode = MaterialMode.PLOW_ONLY,
        widthM: Double = 3.0,
        nanHeading: Boolean = false
    ): TreatSegment {
        val n = 8
        val step = meters / (n - 1)
        val points = (0 until n).map { i ->
            val along = i * step
            val lat = lat0 + offsetNorthM / 111_320.0
            val lon = lon0 + along / (111_320.0 * cos(Math.toRadians(lat0)))
            TrackPoint(lat, lon, endMs - ((n - 1 - i) * 1000L), if (nanHeading) Double.NaN else heading)
        }
        return TreatSegment(
            id = id, vehicleUid = "V", callsign = "V", stormId = "s", operatorId = "op",
            material = material, widthM = widthM, points = points,
            startTimeMs = endMs - 20_000, endTimeMs = endMs
        )
    }

    /** Line that goes east then west (U-turn) over the same ground. */
    private fun uTurn(id: String, endMs: Long): TreatSegment {
        val meters = 80.0
        val n = 5
        val eastPts = (0 until n).map { i ->
            val along = i * (meters / (n - 1))
            val lon = lon0 + along / (111_320.0 * cos(Math.toRadians(lat0)))
            TrackPoint(lat0, lon, endMs - 20_000 + i * 1000L, 90.0)
        }
        val westPts = (1 until n).map { i ->
            val along = meters - i * (meters / (n - 1))
            val lon = lon0 + along / (111_320.0 * cos(Math.toRadians(lat0)))
            TrackPoint(lat0 + 4.0 / 111_320.0, lon, endMs - 10_000 + i * 1000L, 270.0)
        }
        val points = eastPts + westPts
        return TreatSegment(
            id = id, vehicleUid = "V", callsign = "V", stormId = "s", operatorId = "op",
            material = MaterialMode.PLOW_ONLY, widthM = 3.0, points = points,
            startTimeMs = points.first().timeMs, endTimeMs = endMs
        )
    }

    @Test
    fun `full overlap drops the older pass`() {
        val older = east("A", 200.0, 1_000)
        val newer = east("B", 200.0, 2_000)
        val result = SegmentSupersede.apply(older, newer, SegmentSupersede.DEFAULT_LANE_SLACK_M)
        assertEquals("A", result!!.droppedId)
        assertTrue(result.remnants.isEmpty())
    }

    @Test
    fun `partial overlap leaves remnants with original timestamps`() {
        val older = east("A", 400.0, 1_000)
        val newer = east("B", 120.0, 2_000)
        val result = SegmentSupersede.apply(older, newer, SegmentSupersede.DEFAULT_LANE_SLACK_M)!!
        assertEquals("A", result.droppedId)
        assertTrue(result.remnants.isNotEmpty())
        result.remnants.forEach { rem ->
            assertTrue(rem.id.startsWith("A~"))
            assertEquals(rem.points.first().timeMs, rem.startTimeMs)
            assertEquals(rem.points.last().timeMs, rem.endTimeMs)
            assertTrue(rem.endTimeMs <= older.endTimeMs)
        }
    }

    @Test
    fun `opposite direction is never trimmed`() {
        val eastbound = east("E", 200.0, 1_000, heading = 90.0)
        val westbound = east("W", 200.0, 2_000, heading = 270.0)
        assertNull(SegmentSupersede.apply(eastbound, westbound, SegmentSupersede.DEFAULT_LANE_SLACK_M))
        assertNull(SegmentSupersede.apply(westbound, eastbound, SegmentSupersede.DEFAULT_LANE_SLACK_M))
    }

    @Test
    fun `same direction trims regardless of insertion order`() {
        val older = east("A", 200.0, 1_000)
        val newer = east("B", 200.0, 2_000)
        val ab = SegmentSupersede.apply(older, newer, SegmentSupersede.DEFAULT_LANE_SLACK_M)
        val ba = SegmentSupersede.apply(newer, older, SegmentSupersede.DEFAULT_LANE_SLACK_M)
        assertEquals(ab!!.droppedId, ba!!.droppedId)
        assertEquals(ab.remnants.map { it.id }, ba.remnants.map { it.id })
    }

    @Test
    fun `u-turn trims only the matching half`() {
        val older = uTurn("U", 1_000)
        val newer = east("E", 80.0, 2_000, heading = 90.0)
        val result = SegmentSupersede.apply(older, newer, SegmentSupersede.DEFAULT_LANE_SLACK_M)
        assertEquals("U", result!!.droppedId)
        assertTrue(result.remnants.any { rem ->
            rem.points.count { GeoMath.angleDiffDeg(it.headingDeg, 270.0) < 30.0 } >= 2
        })
        assertTrue(result.remnants.none { rem ->
            rem.points.all { GeoMath.angleDiffDeg(it.headingDeg, 90.0) < 30.0 }
        })
    }

    @Test
    fun `strict lanes leaves a pass seven meters over untouched`() {
        val older = east("A", 200.0, 1_000)
        val newer = east("B", 200.0, 2_000, offsetNorthM = 7.0)
        assertNull(SegmentSupersede.apply(older, newer, SegmentSupersede.STRICT_LANE_SLACK_M))
        val loose = SegmentSupersede.apply(older, newer, SegmentSupersede.DEFAULT_LANE_SLACK_M)
        assertEquals("A", loose!!.droppedId)
    }

    @Test
    fun `salt does not trim plow and plow trims salt`() {
        val plow = east("P", 200.0, 1_000, material = MaterialMode.PLOW_ONLY)
        val salt = east("S", 200.0, 2_000, material = MaterialMode.SALT)
        assertNull(SegmentSupersede.apply(plow, salt, SegmentSupersede.DEFAULT_LANE_SLACK_M))
        val plowLater = east("P2", 200.0, 3_000, material = MaterialMode.PLOW_AND_SALT)
        val trimmed = SegmentSupersede.apply(salt, plowLater, SegmentSupersede.DEFAULT_LANE_SLACK_M)
        assertEquals("S", trimmed!!.droppedId)
    }

    @Test
    fun `nan headings still resolve direction from point order`() {
        val older = east("A", 200.0, 1_000, nanHeading = true)
        val newer = east("B", 200.0, 2_000, nanHeading = true)
        val result = SegmentSupersede.apply(older, newer, SegmentSupersede.DEFAULT_LANE_SLACK_M)
        assertEquals("A", result!!.droppedId)
        val reversed = older.copy(
            id = "R",
            points = older.points.reversed().map { it.copy(headingDeg = Double.NaN, timeMs = it.timeMs + 5_000) },
            startTimeMs = 5_000,
            endTimeMs = 9_000
        )
        assertNull(SegmentSupersede.apply(older, reversed, SegmentSupersede.DEFAULT_LANE_SLACK_M))
    }

    @Test
    fun `chained segments that share only an endpoint are not trimmed`() {
        val a = east("A", 100.0, 1_000)
        val last = a.points.last()
        val cont = a.points.drop(1).map {
            TrackPoint(it.lat, it.lon + 100.0 / (111_320.0 * cos(Math.toRadians(lat0))), it.timeMs + 30_000, 90.0)
        }
        val bPoints = listOf(last) + cont
        val b = a.copy(
            id = "B",
            points = bPoints,
            startTimeMs = bPoints.first().timeMs,
            endTimeMs = bPoints.last().timeMs
        )
        assertNull(SegmentSupersede.apply(a, b, SegmentSupersede.DEFAULT_LANE_SLACK_M))
    }
}
