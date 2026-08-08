package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.TaskState
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagerTest {

    private fun task(uid: String = "plowtak-task-SUP-1-1000", timeMs: Long = 1000L) = TaskEvent(
        uid = uid,
        targetVehicleUid = "PLOWTAK-T-1", targetCallsign = "Plow-1",
        assignedBy = "Sup-1", kind = TaskKind.SEGMENT, refId = "seg-9",
        lat = 36.0, lon = -86.0, description = "Route 9 overdue", timeMs = timeMs
    )

    private class Recorder : TaskManager.Listener {
        val broadcasts = mutableListOf<TaskEvent>()
        val escalations = mutableListOf<TaskEvent>()
        override fun onTasksChanged(tasks: List<TaskEvent>) {}
        override fun onLocalTransition(task: TaskEvent) { broadcasts.add(task) }
        override fun onEscalated(task: TaskEvent) { escalations.add(task) }
    }

    @Test
    fun `create ack workflow broadcasts transitions`() {
        val mgr = TaskManager()
        val rec = Recorder()
        mgr.addListener(rec)

        mgr.createLocal(task())
        assertEquals(TaskState.PENDING, rec.broadcasts.last().state)
        assertEquals(1, mgr.pendingFor("PLOWTAK-T-1").size)

        mgr.ack(task().uid, "Plow-1", 2000L)
        assertEquals(TaskState.ACKED, rec.broadcasts.last().state)
        assertEquals("Plow-1", rec.broadcasts.last().stateBy)
        assertTrue(mgr.pendingFor("PLOWTAK-T-1").isEmpty())
    }

    @Test
    fun `terminal states cannot regress`() {
        val mgr = TaskManager()
        mgr.createLocal(task())
        mgr.decline(task().uid, "Plow-1", 2000L)

        // Ack after decline is refused.
        assertNull(mgr.ack(task().uid, "Plow-1", 3000L))
        assertEquals(TaskState.DECLINED, mgr.get(task().uid)?.state)

        // A stale remote PENDING refresh cannot resurrect it.
        mgr.onRemote(task())
        assertEquals(TaskState.DECLINED, mgr.get(task().uid)?.state)
    }

    @Test
    fun `remote convergence adopts newer state`() {
        val mgr = TaskManager()
        mgr.onRemote(task())
        assertEquals(TaskState.PENDING, mgr.get(task().uid)?.state)

        mgr.onRemote(task().copy(state = TaskState.ACKED, stateTimeMs = 5000L, stateBy = "Plow-1"))
        assertEquals(TaskState.ACKED, mgr.get(task().uid)?.state)
    }

    @Test
    fun `escalation fires once after the timer`() {
        val mgr = TaskManager(escalateAfterMs = 60_000L)
        val rec = Recorder()
        mgr.addListener(rec)
        mgr.createLocal(task(timeMs = 1000L))

        assertTrue(mgr.tick(30_000L).isEmpty())
        assertEquals(1, mgr.tick(62_000L).size)
        assertTrue(rec.escalations.single().escalated)
        // Never twice.
        assertTrue(mgr.tick(120_000L).isEmpty())
    }

    @Test
    fun `acked task does not escalate`() {
        val mgr = TaskManager(escalateAfterMs = 60_000L)
        mgr.createLocal(task(timeMs = 1000L))
        mgr.ack(task().uid, "Plow-1", 2000L)
        assertTrue(mgr.tick(120_000L).isEmpty())
    }

    @Test
    fun `remote refresh preserves local escalation flag`() {
        val mgr = TaskManager(escalateAfterMs = 60_000L)
        mgr.onRemote(task(timeMs = 1000L))
        mgr.tick(62_000L)
        // Duplicate broadcast of the same pending task arrives again.
        mgr.onRemote(task(timeMs = 1000L))
        assertTrue(mgr.tick(120_000L).isEmpty())
    }

    @Test
    fun `prune drops old terminal tasks only`() {
        val mgr = TaskManager()
        mgr.createLocal(task(uid = "t1"))
        mgr.createLocal(task(uid = "t2", timeMs = 2000L))
        mgr.cancel("t1", "Sup-1", 3000L)

        mgr.pruneTerminal(nowMs = 3000L + 7 * 3_600_000L)
        assertNull(mgr.get("t1"))
        assertEquals(TaskState.PENDING, mgr.get("t2")?.state)
    }

    // ------------------------------------------------- nearest suggestion

    private fun vehicle(
        uid: String, lat: Double, status: VehicleStatus = VehicleStatus.TREATING,
        type: VehicleType = VehicleType.PLOW
    ) = PlowVehicle(
        uid = uid, callsign = uid, type = type, status = status,
        lat = lat, lon = -86.0, headingDeg = 0.0, lastUpdateMs = 0L
    )

    @Test
    fun `nearest treating truck is suggested`() {
        val fleet = listOf(
            vehicle("far-treating", 36.5),
            vehicle("near-treating", 36.01),
            vehicle("nearest-deadhead", 36.001, status = VehicleStatus.DEADHEAD)
        )
        // Treating beats closer-but-deadhead; among treating, distance wins.
        assertEquals(
            "near-treating",
            TaskManager.suggestNearest(fleet, 36.0, -86.0)?.uid
        )
    }

    @Test
    fun `non-dispatchable and non-treat types are excluded`() {
        val fleet = listOf(
            vehicle("oos", 36.001, status = VehicleStatus.OUT_OF_SERVICE),
            vehicle("supervisor", 36.001, type = VehicleType.SUPERVISOR),
            vehicle("salt", 36.1, status = VehicleStatus.DEADHEAD, type = VehicleType.SALT_ONLY)
        )
        assertEquals("salt", TaskManager.suggestNearest(fleet, 36.0, -86.0)?.uid)
        assertNull(TaskManager.suggestNearest(emptyList(), 36.0, -86.0))
    }

    @Test
    fun `loading trucks are skipped for send-nearest`() {
        val fleet = listOf(
            vehicle("at-dome", 36.001, status = VehicleStatus.LOADING),
            vehicle("rolling", 36.05, status = VehicleStatus.DEADHEAD)
        )
        assertEquals("rolling", TaskManager.suggestNearest(fleet, 36.0, -86.0)?.uid)
    }
}
