package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.TaskState
import com.atakmap.android.plowtak.ops.RouteAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsMissionCodecTest {

    @Test
    fun `snoozes round trip in ops snapshot`() {
        val task = TaskEvent(
            uid = "plowtak-task-1",
            targetVehicleUid = "ME",
            targetCallsign = "Me",
            assignedBy = "Sup",
            kind = TaskKind.HAZARD,
            refId = "h1",
            lat = 36.1,
            lon = -86.7,
            description = "Check ice",
            timeMs = 1_700L,
            state = TaskState.PENDING
        )
        val route = RouteAssignment(
            vehicleUid = "ME",
            callsign = "Me",
            routeId = "RT-1",
            source = RouteAssignment.Source.GIS,
            assignedBy = "Sup",
            timeMs = 1_600L
        )
        val snoozes = mapOf(
            "task:plowtak-task-1" to 1_800_000L,
            "overdue:seg-9" to 2_000_000L
        )
        val bytes = OpsMissionCodec.encode(
            stormId = "storm-1",
            routes = listOf(route),
            zones = emptyList(),
            tasks = listOf(task),
            snoozes = snoozes
        )
        val json = bytes.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"snoozes\""))
        val snap = OpsMissionCodec.decode(bytes)!!
        assertEquals("storm-1", snap.stormId)
        assertEquals(1, snap.routes.size)
        assertEquals(1, snap.tasks.size)
        assertEquals(snoozes, snap.snoozes)
    }

    @Test
    fun `legacy snapshot without snoozes decodes empty map`() {
        val json = """
            {"stormId":"s","routes":[],"zones":[],"tasks":[]}
        """.trimIndent()
        val snap = OpsMissionCodec.decode(json.toByteArray())!!
        assertTrue(snap.snoozes.isEmpty())
    }
}
