package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.model.TaskEvent
import com.atakmap.android.ideaplow.model.TaskKind
import com.atakmap.android.ideaplow.model.TaskState

/**
 * Detail codec for supervisor tasking. The task and every state transition
 * (ack / decline / cancel) re-send under the same event uid so all clients
 * converge; escalation is local bookkeeping and does not ride the wire.
 *
 * ```
 * <__ideaplow>
 *   <task target= targetCallsign= by= kind= ref= desc=
 *         state= stateBy= stateTime= time=/>
 * </__ideaplow>
 * ```
 */
object TaskCotCodec {

    const val TASK_EVENT_TYPE = "b-i-x-ideaplow-task"

    fun encode(task: TaskEvent): DetailNode =
        DetailNode(
            DetailNode.IDEAPLOW, emptyMap(),
            listOf(
                DetailNode(
                    "task", mapOf(
                        "target" to task.targetVehicleUid,
                        "targetCallsign" to task.targetCallsign,
                        "by" to task.assignedBy,
                        "kind" to task.kind.wireName,
                        "ref" to task.refId,
                        "desc" to task.description,
                        "state" to task.state.wireName,
                        "stateBy" to task.stateBy,
                        "stateTime" to task.stateTimeMs.toString(),
                        "time" to task.timeMs.toString()
                    )
                )
            )
        )

    /** [eventUid], [lat], [lon] come from the CoT event envelope. */
    fun decode(node: DetailNode, eventUid: String, lat: Double, lon: Double): TaskEvent? {
        val ideaplow = if (node.name == DetailNode.IDEAPLOW) node
        else node.firstChild(DetailNode.IDEAPLOW) ?: return null
        val taskNode = ideaplow.firstChild("task") ?: return null
        val target = taskNode.attr("target") ?: return null
        val kind = TaskKind.fromWireName(taskNode.attr("kind")) ?: return null
        val time = taskNode.attrLong("time", -1L)
        if (time < 0) return null

        return TaskEvent(
            uid = eventUid,
            targetVehicleUid = target,
            targetCallsign = taskNode.attr("targetCallsign") ?: "",
            assignedBy = taskNode.attr("by") ?: "",
            kind = kind,
            refId = taskNode.attr("ref") ?: "",
            lat = lat,
            lon = lon,
            description = taskNode.attr("desc") ?: "",
            timeMs = time,
            state = TaskState.fromWireName(taskNode.attr("state")) ?: TaskState.PENDING,
            stateTimeMs = taskNode.attrLong("stateTime", time),
            stateBy = taskNode.attr("stateBy") ?: ""
        )
    }
}
