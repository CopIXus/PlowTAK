package com.atakmap.android.plowtak.report

import com.atakmap.android.plowtak.coverage.CycleTimes
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.RoutePriority
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsCalculatorTest {

    /** ~1 km due-north segment ending at [endMs]. */
    private fun kmSegment(id: String, endMs: Long, startLat: Double = 36.0) = TreatSegment(
        id = id, vehicleUid = "PLOWTAK-T-1", callsign = "Plow-1",
        stormId = "s", operatorId = "op", material = MaterialMode.PLOW_ONLY, widthM = 3.0,
        points = listOf(
            TrackPoint(startLat, -86.0, endMs - 60_000L, 0.0),
            TrackPoint(startLat + 0.009, -86.0, endMs, 0.0)
        ),
        startTimeMs = endMs - 60_000L, endTimeMs = endMs
    )

    @Test
    fun `segment length is close to real geodesic length`() {
        val lengthM = MetricsCalculator.segmentLengthM(kmSegment("s1", 60_000L))
        assertTrue("was $lengthM", lengthM in 950.0..1050.0)
    }

    @Test
    fun `lane miles counts only segments ending in the window`() {
        val nowMs = 2 * 3_600_000L
        val segments = listOf(
            kmSegment("old", endMs = 30 * 60_000L),          // 1.5 h ago — outside
            kmSegment("in1", endMs = nowMs - 30 * 60_000L),
            kmSegment("in2", endMs = nowMs - 10 * 60_000L, startLat = 36.1)
        )
        val miles = MetricsCalculator.laneMiles(segments, nowMs - 3_600_000L, nowMs)
        // Two ~1 km segments = ~1.24 miles.
        assertTrue("was $miles", miles in 1.15..1.35)
    }

    @Test
    fun `coverage within cycle is length weighted`() {
        val nowMs = 3_600_000L
        val cycles = CycleTimes(defaultMinutes = 45)
        val fresh = kmSegment("fresh", endMs = nowMs - 10 * 60_000L)          // 10 min old
        val stale = kmSegment("stale", endMs = nowMs - 50 * 60_000L, startLat = 36.1) // 50 min old
        val ratio = MetricsCalculator.coverageWithinCycle(
            listOf(fresh, stale), cycles, emptyList(), nowMs
        )
        // Two equal-length segments, one fresh => ~0.5.
        assertEquals(0.5, ratio, 0.05)
    }

    @Test
    fun `zone tightening pushes coverage out of cycle`() {
        val nowMs = 3_600_000L
        val cycles = CycleTimes(defaultMinutes = 45)
        val seg = kmSegment("bridge-pass", endMs = nowMs - 30 * 60_000L) // 30 min old
        // Without a zone: 30 < 45 => within cycle.
        assertEquals(
            1.0,
            MetricsCalculator.coverageWithinCycle(listOf(seg), cycles, emptyList(), nowMs),
            1e-9
        )
        // Bridge zone (x0.5 => 22.5 min cycle) over the segment: now overdue.
        val zone = SpecialZone(
            "z", "Bridge", ZoneType.BRIDGE, 0.5, 36.0045, -86.0, radiusM = 2_000.0
        )
        assertEquals(
            0.0,
            MetricsCalculator.coverageWithinCycle(listOf(seg), cycles, listOf(zone), nowMs),
            1e-9
        )
    }

    @Test
    fun `empty coverage reports within-cycle as one`() {
        assertEquals(
            1.0,
            MetricsCalculator.coverageWithinCycle(emptyList(), CycleTimes(), emptyList(), 0L),
            1e-9
        )
    }

    @Test
    fun `rollup aggregates reloads and rates`() {
        val nowMs = 2 * 3_600_000L
        val segments = listOf(
            kmSegment("in1", endMs = nowMs - 30 * 60_000L),
            kmSegment("in2", endMs = nowMs - 10 * 60_000L, startLat = 36.1)
        )
        val fleet = listOf(
            vehicle("Plow-1", reloads = 3, stormId = "s"),
            vehicle("Plow-2", reloads = 0, stormId = "s"),
            vehicle("Sup-1", reloads = 0, stormId = "")
        )
        val m = MetricsCalculator.calculate(
            segments, fleet, CycleTimes(defaultMinutes = 45), emptyList(), nowMs
        )
        assertEquals(2, m.segmentCount)
        assertEquals(2, m.activeTruckCount)
        assertEquals(mapOf("Plow-1" to 3), m.reloadsByTruck)
        // ~1.24 lane-miles in the last hour => rate ~1.24 mi/h.
        assertTrue("was ${m.laneMilesPerHour}", m.laneMilesPerHour in 1.15..1.35)
        assertTrue(m.laneMilesTreated >= m.laneMilesPerHour) // 3 segments total? no — 2, equal
        assertEquals(1.0, m.coverageWithinCycle, 1e-9)
    }

    private fun vehicle(callsign: String, reloads: Int, stormId: String) = PlowVehicle(
        uid = callsign, callsign = callsign, type = VehicleType.PLOW,
        status = VehicleStatus.TREATING, lat = 36.0, lon = -86.0,
        headingDeg = 0.0, lastUpdateMs = 0L, stormId = stormId, reloadCount = reloads
    )
}
