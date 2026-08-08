package com.atakmap.android.ideaplow.report

import com.atakmap.android.ideaplow.coverage.Freshness
import com.atakmap.android.ideaplow.coverage.FreshnessModel
import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.AlertState
import com.atakmap.android.ideaplow.model.HazardEvent
import com.atakmap.android.ideaplow.model.HazardType
import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TrackPoint
import com.atakmap.android.ideaplow.model.TreatSegment
import com.atakmap.android.ideaplow.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StormReplayTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun segment(id: String, startMs: Long, endMs: Long) = TreatSegment(
        id = id,
        vehicleUid = "PLOW-12",
        callsign = "Plow-12",
        stormId = "storm-1",
        operatorId = "op-1",
        material = MaterialMode.SALT,
        widthM = 3.0,
        points = listOf(
            TrackPoint(36.16, -86.78, startMs, 90.0),
            TrackPoint(36.16, -86.77, endMs, 90.0)
        ),
        startTimeMs = startMs,
        endTimeMs = endMs
    )

    private fun data() = StormExportData(
        stormId = "storm-1",
        generatedAtMs = t0 + 300 * min,
        vehicleUid = "SUP-1",
        callsign = "Supervisor-1",
        segments = listOf(
            segment("s1", t0, t0 + 5 * min),
            segment("s2", t0 + 60 * min, t0 + 65 * min),
            segment("s3", t0 + 120 * min, t0 + 125 * min)
        ),
        alerts = listOf(
            AlertEvent(
                uid = "PLOW-13-distress", vehicleUid = "PLOW-13",
                callsign = "Plow-13", vehicleType = VehicleType.PLOW,
                lat = 36.2, lon = -86.8, timeMs = t0 + 30 * min,
                state = AlertState.CLEARED, handledBy = "Supervisor-1"
            )
        ),
        hazards = listOf(
            HazardEvent(
                uid = "hz-1", type = HazardType.TREE_WIRES_DOWN,
                reporterUid = "PLOW-12", reporterCallsign = "Plow-12",
                lat = 36.3, lon = -86.9, timeMs = t0 + 90 * min, stormId = "storm-1"
            )
        )
    )

    private fun replay() = StormReplay(
        data(),
        FreshnessModel(cycleTimeMinutes = 45, retentionHours = 12.0)
    )

    @Test
    fun `range spans first segment start to last event`() {
        val r = replay()
        assertEquals(t0, r.startMs)
        assertEquals(t0 + 125 * min, r.endMs)
        assertTrue(!r.isEmpty)
    }

    @Test
    fun `segments appear only once their pass finished`() {
        val r = replay()
        // During s1's recording: nothing visible yet.
        assertEquals(0, r.stateAt(t0 + 2 * min).segmentCount)
        // Right after s1 completes.
        assertEquals(listOf("s1"), r.stateAt(t0 + 5 * min).segments.map { it.id })
        // Before s2 completes.
        assertEquals(1, r.stateAt(t0 + 62 * min).segmentCount)
        // At the end everything is there.
        assertEquals(3, r.stateAt(t0 + 200 * min).segmentCount)
    }

    @Test
    fun `freshness is computed as of the replay time not now`() {
        val r = replay()
        // Just after s1 finished: green.
        assertEquals(
            Freshness.GREEN,
            r.stateAt(t0 + 6 * min).freshnessById["s1"]
        )
        // 50 minutes after s1 finished (cycle 45): red.
        assertEquals(
            Freshness.RED,
            r.stateAt(t0 + 55 * min).freshnessById["s1"]
        )
        // But s2, finishing at +65, is green at +70.
        val frame = r.stateAt(t0 + 70 * min)
        assertEquals(Freshness.GREEN, frame.freshnessById["s2"])
        assertEquals(Freshness.RED, frame.freshnessById["s1"])
    }

    @Test
    fun `alerts replay as active then resolve`() {
        val r = replay()
        // Before the alert: nothing.
        assertTrue(r.stateAt(t0 + 20 * min).activeAlerts.isEmpty())
        // Shortly after: active even though final state is CLEARED.
        val during = r.stateAt(t0 + 35 * min)
        assertEquals(1, during.activeAlerts.size)
        assertTrue(during.resolvedAlerts.isEmpty())
        // Long after: resolved.
        val after = r.stateAt(t0 + 120 * min)
        assertTrue(after.activeAlerts.isEmpty())
        assertEquals(1, after.resolvedAlerts.size)
    }

    @Test
    fun `hazards appear at their report time`() {
        val r = replay()
        assertTrue(r.stateAt(t0 + 89 * min).hazards.isEmpty())
        assertEquals(1, r.stateAt(t0 + 90 * min).hazards.size)
    }

    @Test
    fun `time is clamped to the record range`() {
        val r = replay()
        assertEquals(r.startMs, r.stateAt(0L).timeMs)
        assertEquals(r.endMs, r.stateAt(Long.MAX_VALUE).timeMs)
        assertEquals(3, r.stateAt(Long.MAX_VALUE).segmentCount)
    }

    @Test
    fun `timeline covers the storm with the requested steps`() {
        val r = replay()
        val ticks = r.timeline(5)
        assertEquals(5, ticks.size)
        assertEquals(r.startMs, ticks.first())
        assertEquals(r.endMs, ticks.last())
        assertTrue(ticks.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `empty storm yields an empty but safe replay`() {
        val r = StormReplay(
            StormExportData("storm-x", 0L, "SUP-1", "Supervisor-1")
        )
        assertTrue(r.isEmpty)
        val frame = r.stateAt(12345L)
        assertEquals(0, frame.segmentCount)
        assertTrue(frame.activeAlerts.isEmpty())
        assertEquals(listOf(0L), r.timeline(4))
    }
}
