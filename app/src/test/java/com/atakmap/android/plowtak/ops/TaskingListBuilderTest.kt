package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.Freshness
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.TaskState
import com.atakmap.android.plowtak.model.TrackPoint
import com.atakmap.android.plowtak.model.TreatSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskingListBuilderTest {

    private val selfUid = "ME"
    private val now = 1_700_000_000_000L
    private val selfLat = 36.1600
    private val selfLon = -86.7850

    private fun task(
        uid: String,
        target: String,
        lat: Double,
        lon: Double,
        description: String = "Task $uid",
        refId: String = ""
    ) = TaskEvent(
        uid = uid,
        targetVehicleUid = target,
        targetCallsign = "CS",
        assignedBy = "Sup",
        kind = TaskKind.SEGMENT,
        refId = refId,
        lat = lat,
        lon = lon,
        description = description,
        timeMs = now - 60_000L,
        state = TaskState.PENDING
    )

    private fun redSegment(
        id: String,
        lat: Double,
        lon: Double,
        endAgeMs: Long = 40 * 60_000L
    ): TreatSegment {
        val end = now - endAgeMs
        return TreatSegment(
            id = id,
            vehicleUid = "OTHER",
            callsign = "Other",
            stormId = "s1",
            operatorId = "op",
            material = MaterialMode.SALT,
            widthM = 3.0,
            points = listOf(
                TrackPoint(lat, lon, end - 30_000L),
                TrackPoint(lat + 0.0001, lon + 0.0001, end)
            ),
            startTimeMs = end - 30_000L,
            endTimeMs = end
        )
    }

    @Test
    fun `mine tasks and routes sort before overdue gaps`() {
        val nearOverdue = redSegment("seg-near", selfLat + 0.001, selfLon)
        val farTask = task("t-far", selfUid, selfLat + 0.05, selfLon, "Far mine")
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = listOf(farTask),
                myRoute = TaskingListBuilder.RouteInput("RT-1", selfLat + 0.02, selfLon, 40),
                segments = listOf(nearOverdue),
                snoozes = emptyMap(),
                classify = { Freshness.RED }
            )
        )
        assertTrue(items.size >= 3)
        assertTrue(items[0].mine)
        assertTrue(items[1].mine)
        assertEquals(TaskingItem.Kind.OVERDUE, items.last().kind)
        assertTrue(items.takeWhile { it.mine }.all { it.mine })
        assertTrue(items.dropWhile { it.mine }.none { it.mine })
    }

    @Test
    fun `mine items sort by distance among themselves`() {
        val near = task("t-near", selfUid, selfLat + 0.001, selfLon, "Near")
        val far = task("t-far", selfUid, selfLat + 0.04, selfLon, "Far")
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = listOf(far, near),
                myRoute = null,
                segments = emptyList(),
                snoozes = emptyMap()
            )
        )
        assertEquals(2, items.size)
        assertEquals("Near", items[0].title)
        assertEquals("Far", items[1].title)
        assertTrue(items[0].distanceM < items[1].distanceM)
    }

    @Test
    fun `snoozed items are hidden until due`() {
        val t = task("t1", selfUid, selfLat, selfLon)
        val id = TaskingItem.taskId("t1")
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = listOf(t),
                myRoute = null,
                segments = emptyList(),
                snoozes = mapOf(id to now + 30 * 60_000L)
            )
        )
        assertTrue(items.none { it.id == id })
    }

    @Test
    fun `completed route is omitted`() {
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = emptyList(),
                myRoute = TaskingListBuilder.RouteInput("RT-1", selfLat, selfLon, 100),
                segments = emptyList(),
                snoozes = emptyMap()
            )
        )
        assertTrue(items.none { it.kind == TaskingItem.Kind.ROUTE })
    }

    @Test
    fun `overdue capped and distance sorted`() {
        val segs = (0 until 60).map { i ->
            redSegment("seg-$i", selfLat + i * 0.0002, selfLon)
        }
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = emptyList(),
                myRoute = null,
                segments = segs,
                snoozes = emptyMap(),
                classify = { Freshness.RED }
            )
        )
        assertEquals(TaskingListBuilder.MAX_OVERDUE_ITEMS, items.size)
        for (i in 1 until items.size) {
            assertTrue(items[i - 1].distanceM <= items[i].distanceM)
        }
    }

    @Test
    fun `zone-tightened red segments are not hidden by flat cycle due`() {
        val near = redSegment("bridge-1", selfLat + 0.001, selfLon, endAgeMs = 20 * 60_000L)
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 45,
                tasks = emptyList(),
                myRoute = null,
                segments = listOf(near),
                snoozes = emptyMap(),
                classify = { Freshness.RED },
                cycleMinutesFor = { 15 }
            )
        )
        assertEquals(1, items.size)
        assertEquals(TaskingItem.Kind.OVERDUE, items[0].kind)
    }

    @Test
    fun `task refId suppresses matching overdue segment`() {
        val seg = redSegment("seg-mine", selfLat + 0.001, selfLon)
        val t = task("t1", selfUid, selfLat, selfLon, refId = "seg-mine")
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = listOf(t),
                myRoute = null,
                segments = listOf(seg),
                snoozes = emptyMap(),
                classify = { Freshness.RED }
            )
        )
        assertTrue(items.none { it.kind == TaskingItem.Kind.OVERDUE })
        assertTrue(items.any { it.kind == TaskingItem.Kind.TASK })
    }

    @Test
    fun `without GPS overdue still listed when hasSelfFix false`() {
        val far = redSegment("far", selfLat + 1.0, selfLon)
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = 0.0,
                selfLon = 0.0,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = emptyList(),
                myRoute = null,
                segments = listOf(far),
                snoozes = emptyMap(),
                classify = { Freshness.RED },
                hasSelfFix = false
            )
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `suppressOverdue drops route-adjacent segments`() {
        val seg = redSegment("on-route", selfLat + 0.001, selfLon)
        val items = TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid,
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = 15 * 60_000L,
                cycleMinutes = 30,
                tasks = emptyList(),
                myRoute = TaskingListBuilder.RouteInput("RT-1", selfLat, selfLon, 40),
                segments = listOf(seg),
                snoozes = emptyMap(),
                classify = { Freshness.RED },
                suppressOverdue = { it.id == "on-route" }
            )
        )
        assertTrue(items.none { it.kind == TaskingItem.Kind.OVERDUE })
        assertTrue(items.any { it.kind == TaskingItem.Kind.ROUTE })
    }
}
