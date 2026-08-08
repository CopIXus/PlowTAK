package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.RoutePriority
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.ZoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleResolverTest {

    private val cycles = CycleTimes(
        defaultMinutes = 120, p1Minutes = 45, p2Minutes = 90, p3Minutes = 0
    )

    private val bridge = SpecialZone(
        id = "z1", name = "River bridge", type = ZoneType.BRIDGE,
        cycleMultiplier = 0.5, centerLat = 36.0, centerLon = -86.0, radiusM = 200.0
    )

    private val schoolPolygon = SpecialZone(
        id = "z2", name = "Elm school", type = ZoneType.SCHOOL,
        cycleMultiplier = 0.25, centerLat = 36.1, centerLon = -86.1, radiusM = 0.0,
        polygon = listOf(
            36.09 to -86.11, 36.11 to -86.11, 36.11 to -86.09, 36.09 to -86.09
        )
    )

    // ------------------------------------------------------ cycle times

    @Test
    fun `priority overrides apply and zero falls back to default`() {
        assertEquals(45, cycles.forPriority(RoutePriority.P1))
        assertEquals(90, cycles.forPriority(RoutePriority.P2))
        assertEquals(120, cycles.forPriority(RoutePriority.P3)) // 0 => default
        assertEquals(120, cycles.forPriority(RoutePriority.DEFAULT))
    }

    // -------------------------------------------------- zone containment

    @Test
    fun `circle zone containment`() {
        assertTrue(bridge.contains(36.0005, -86.0))   // ~55 m from center
        assertFalse(bridge.contains(36.01, -86.0))    // ~1.1 km away
    }

    @Test
    fun `polygon zone containment`() {
        assertTrue(schoolPolygon.isPolygon)
        assertTrue(schoolPolygon.contains(36.10, -86.10))   // inside square
        assertFalse(schoolPolygon.contains(36.12, -86.10))  // north of it
        assertFalse(schoolPolygon.contains(36.10, -86.12))  // west of it
    }

    @Test
    fun `degenerate polygon falls back to nothing`() {
        val degenerate = schoolPolygon.copy(polygon = listOf(36.0 to -86.0))
        assertFalse(degenerate.isPolygon)
        // Falls back to the radius-0 circle: only the exact center matches.
        assertFalse(degenerate.contains(36.105, -86.10))
    }

    // ------------------------------------------------------- resolution

    @Test
    fun `point outside zones uses base cycle`() {
        assertEquals(
            120,
            CycleResolver.resolveMinutes(cycles, RoutePriority.DEFAULT, listOf(bridge), 37.0, -87.0)
        )
    }

    @Test
    fun `point inside zone gets multiplied cycle`() {
        assertEquals(
            60,
            CycleResolver.resolveMinutes(cycles, RoutePriority.DEFAULT, listOf(bridge), 36.0, -86.0)
        )
        // Priority override multiplies too: P1 45 min * 0.5 = 22.5 -> 23.
        assertEquals(
            23,
            CycleResolver.resolveMinutes(cycles, RoutePriority.P1, listOf(bridge), 36.0, -86.0)
        )
    }

    @Test
    fun `overlapping zones use the strictest multiplier`() {
        val overlappingSchool = schoolPolygon.copy(
            polygon = emptyList(), centerLat = 36.0, centerLon = -86.0, radiusM = 300.0
        )
        assertEquals(
            30, // 120 * 0.25
            CycleResolver.resolveMinutes(
                cycles, RoutePriority.DEFAULT, listOf(bridge, overlappingSchool), 36.0, -86.0
            )
        )
    }

    @Test
    fun `segment clipping a zone inherits the stricter cycle`() {
        // Segment starts far away, last point crosses the bridge zone.
        val seg = TreatSegment(
            id = "s1", vehicleUid = "P1", callsign = "Plow-1", stormId = "s",
            operatorId = "op", material = MaterialMode.PLOW_ONLY, widthM = 3.0,
            points = listOf(
                TrackPoint(35.9, -86.0, 0L),
                TrackPoint(35.95, -86.0, 1000L),
                TrackPoint(36.0001, -86.0, 2000L) // inside bridge zone
            ),
            startTimeMs = 0L, endTimeMs = 2000L
        )
        assertEquals(
            60,
            CycleResolver.resolveForSegment(cycles, RoutePriority.DEFAULT, listOf(bridge), seg)
        )
    }

    @Test
    fun `segment outside all zones keeps base cycle`() {
        val seg = TreatSegment(
            id = "s2", vehicleUid = "P1", callsign = "Plow-1", stormId = "s",
            operatorId = "op", material = MaterialMode.PLOW_ONLY, widthM = 3.0,
            points = listOf(TrackPoint(35.0, -85.0, 0L), TrackPoint(35.001, -85.0, 1000L)),
            startTimeMs = 0L, endTimeMs = 1000L
        )
        assertEquals(
            120,
            CycleResolver.resolveForSegment(cycles, RoutePriority.DEFAULT, listOf(bridge), seg)
        )
    }

    @Test
    fun `freshness model honors resolved cycle`() {
        val model = FreshnessModel(cycleTimeMinutes = 120, retentionHours = 12.0)
        val endMs = 0L
        val nowMs = 70 * 60_000L // 70 minutes later

        // Global cycle (120 min, due-soon at 90): still green.
        assertEquals(Freshness.GREEN, model.classify(endMs, nowMs))
        // Bridge-zone cycle (60 min): already overdue.
        assertEquals(Freshness.RED, model.classify(endMs, nowMs, cycleMinutes = 60))
    }
}
