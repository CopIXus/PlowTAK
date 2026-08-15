package com.atakmap.android.plowtak.ops

/**
 * One row on the driver Tasks / needs-treated screen.
 * Pure data — UI formats distance and due labels.
 */
data class TaskingItem(
    val id: String,
    val kind: Kind,
    val title: String,
    val lat: Double,
    val lon: Double,
    /** When this item becomes due (or went due). Snooze raises this. */
    val dueByMs: Long,
    val distanceM: Double,
    /** Assigned to this unit (task or route). */
    val mine: Boolean,
    /** Segment / hazard / route id when applicable. */
    val refId: String = "",
    /** Optional subtitle (e.g. route coverage %). */
    val detail: String = ""
) {
    enum class Kind(val wireName: String, val label: String) {
        TASK("task", "Task"),
        ROUTE("route", "Route"),
        OVERDUE("overdue", "Overdue");

        companion object {
            fun fromWireName(name: String?): Kind? =
                entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
        }
    }

    companion object {
        fun taskId(taskUid: String) = "task:$taskUid"
        fun routeId(routeId: String) = "route:$routeId"
        fun overdueId(segmentId: String) = "overdue:$segmentId"
    }
}
