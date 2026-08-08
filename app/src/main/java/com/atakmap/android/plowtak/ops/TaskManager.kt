package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.model.CapabilityRules
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskState
import com.atakmap.android.plowtak.model.VehicleStatus

/**
 * Supervisor tasking book-keeping: local create/ack/decline/cancel, remote
 * convergence, and the escalation timer (a task unacked after
 * [escalateAfterMs] re-alerts the supervisor once). Pure Kotlin state
 * machine; the CoT layer observes [Listener.onLocalTransition] to broadcast
 * and calls [onRemote] for inbound task events.
 */
class TaskManager(
    var escalateAfterMs: Long = 5 * 60_000L
) {

    interface Listener {
        fun onTasksChanged(tasks: List<TaskEvent>)
        /** A local action (create/ack/decline/cancel) to broadcast over CoT. */
        fun onLocalTransition(task: TaskEvent)
        /** A pending task blew the escalation timer — re-alert supervisor. */
        fun onEscalated(task: TaskEvent)
    }

    private val tasks = LinkedHashMap<String, TaskEvent>()
    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun all(): List<TaskEvent> = tasks.values.toList()

    fun get(uid: String): TaskEvent? = tasks[uid]

    /** Tasks needing attention (pending) assigned to the given vehicle. */
    fun pendingFor(vehicleUid: String): List<TaskEvent> =
        tasks.values.filter { it.targetVehicleUid == vehicleUid && it.state == TaskState.PENDING }

    /** Supervisor creates and broadcasts a task. */
    fun createLocal(task: TaskEvent) {
        tasks[task.uid] = task.copy(state = TaskState.PENDING, stateTimeMs = task.timeMs)
        notifyChanged()
        notifyLocal(tasks.getValue(task.uid))
    }

    /** Driver taps ACK. */
    fun ack(uid: String, byCallsign: String, nowMs: Long): TaskEvent? =
        transition(uid, TaskState.ACKED, byCallsign, nowMs)

    /** Driver taps DECLINE. */
    fun decline(uid: String, byCallsign: String, nowMs: Long): TaskEvent? =
        transition(uid, TaskState.DECLINED, byCallsign, nowMs)

    /** Supervisor withdraws the task. */
    fun cancel(uid: String, byCallsign: String, nowMs: Long): TaskEvent? =
        transition(uid, TaskState.CANCELLED, byCallsign, nowMs)

    /**
     * Apply a task update received over CoT (no re-broadcast). Convergence:
     * unknown tasks are adopted; known tasks take the newer state, and a
     * terminal state always beats PENDING regardless of clock skew.
     */
    fun onRemote(task: TaskEvent) {
        val existing = tasks[task.uid]
        val accept = when {
            existing == null -> true
            existing.state.isTerminal && task.state == TaskState.PENDING -> false
            !existing.state.isTerminal && task.state.isTerminal -> true
            else -> task.stateTimeMs >= existing.stateTimeMs
        }
        if (!accept) return
        // Preserve local escalation bookkeeping across remote refreshes.
        tasks[task.uid] = task.copy(escalated = existing?.escalated ?: false)
        notifyChanged()
    }

    /**
     * Escalation sweep — call from the shared periodic timer. Marks and
     * reports tasks pending longer than [escalateAfterMs] (once each).
     */
    fun tick(nowMs: Long): List<TaskEvent> {
        val escalated = mutableListOf<TaskEvent>()
        for ((uid, task) in tasks) {
            if (task.state == TaskState.PENDING && !task.escalated &&
                nowMs - task.timeMs >= escalateAfterMs
            ) {
                val updated = task.copy(escalated = true)
                tasks[uid] = updated
                escalated.add(updated)
            }
        }
        if (escalated.isNotEmpty()) {
            notifyChanged()
            escalated.forEach { task -> listeners.toList().forEach { it.onEscalated(task) } }
        }
        return escalated
    }

    /** Drop terminal tasks older than [maxAgeMs] to keep the list tidy. */
    fun pruneTerminal(nowMs: Long, maxAgeMs: Long = 6 * 3_600_000L) {
        val dead = tasks.values
            .filter { it.state.isTerminal && nowMs - it.stateTimeMs > maxAgeMs }
            .map { it.uid }
        if (dead.isEmpty()) return
        dead.forEach { tasks.remove(it) }
        notifyChanged()
    }

    private fun transition(uid: String, to: TaskState, by: String, nowMs: Long): TaskEvent? {
        val existing = tasks[uid] ?: return null
        if (existing.state == to) return existing
        if (existing.state.isTerminal) return null // no resurrecting decided tasks
        val updated = existing.copy(state = to, stateBy = by, stateTimeMs = nowMs)
        tasks[uid] = updated
        notifyChanged()
        notifyLocal(updated)
        return updated
    }

    private fun notifyChanged() {
        val snapshot = all()
        listeners.toList().forEach { it.onTasksChanged(snapshot) }
    }

    private fun notifyLocal(task: TaskEvent) {
        listeners.toList().forEach { it.onLocalTransition(task) }
    }

    companion object {

        /**
         * Nearest truck suggestion for a task location: dispatchable
         * treat-capable units only, actively TREATING trucks preferred,
         * then plain distance. Stale filtering is the caller's concern.
         */
        fun suggestNearest(
            vehicles: List<PlowVehicle>,
            lat: Double,
            lon: Double
        ): PlowVehicle? = vehicles
            .filter { CapabilityRules.paintsCoverage(it.type) && CapabilityRules.isDispatchable(it.status) }
            .minWithOrNull(
                compareBy(
                    { if (it.status == VehicleStatus.TREATING) 0 else 1 },
                    { GeoMath.distanceMeters(it.lat, it.lon, lat, lon) }
                )
            )
    }
}
