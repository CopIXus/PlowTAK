package com.atakmap.android.ideaplow.model

/** What a supervisor task points at. */
enum class TaskKind(val wireName: String, val label: String) {
    SEGMENT("segment", "Overdue segment"),
    HAZARD("hazard", "Hazard");

    companion object {
        fun fromWireName(name: String?): TaskKind? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** Task lifecycle. Terminal states never regress to PENDING. */
enum class TaskState(val wireName: String, val label: String) {
    PENDING("pending", "Pending"),
    ACKED("acked", "Acknowledged"),
    DECLINED("declined", "Declined"),
    CANCELLED("cancelled", "Cancelled");

    val isTerminal: Boolean get() = this != PENDING

    companion object {
        fun fromWireName(name: String?): TaskState? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/**
 * A supervisor-assigned task ("go treat Route 9" / "check that hazard").
 * [uid] is the CoT event uid; state transitions re-send under the same uid
 * so every client converges on one record.
 */
data class TaskEvent(
    val uid: String,
    /** Vehicle the task is assigned to. */
    val targetVehicleUid: String,
    val targetCallsign: String,
    /** Supervisor callsign that created it. */
    val assignedBy: String,
    val kind: TaskKind,
    /** Segment id or hazard uid the task refers to; may be empty. */
    val refId: String,
    val lat: Double,
    val lon: Double,
    val description: String,
    val timeMs: Long,
    val state: TaskState = TaskState.PENDING,
    /** When the current state was entered (ordering for convergence). */
    val stateTimeMs: Long = timeMs,
    /** Callsign that acked / declined / cancelled. */
    val stateBy: String = "",
    /** Escalation already raised for this task (local bookkeeping). */
    val escalated: Boolean = false
) {
    companion object {
        fun makeUid(assignerUid: String, nowMs: Long) = "ideaplow-task-$assignerUid-$nowMs"
    }
}
