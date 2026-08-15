package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.coverage.Freshness
import com.atakmap.android.plowtak.coverage.FreshnessModel
import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskState
import com.atakmap.android.plowtak.model.TreatSegment

/**
 * Builds the mine-first needs-treated list for the Tasks screen.
 * Pure Kotlin — no Android.
 */
object TaskingListBuilder {

    const val OVERDUE_RADIUS_M = 25_000.0
    const val MAX_OVERDUE_ITEMS = 50

    data class RouteInput(
        val routeId: String,
        val lat: Double,
        val lon: Double,
        /** 0–100; null means unknown (still show as unfinished). */
        val coveragePercent: Int?
    )

    data class Input(
        val selfUid: String,
        val selfLat: Double,
        val selfLon: Double,
        val nowMs: Long,
        val escalateAfterMs: Long,
        val cycleMinutes: Int,
        val tasks: List<TaskEvent>,
        val myRoute: RouteInput?,
        val segments: List<TreatSegment>,
        val snoozes: Map<String, Long>,
        /** Optional override; null uses [FreshnessModel] with [cycleMinutes]. */
        val classify: ((TreatSegment) -> Freshness)? = null,
        /**
         * Per-segment cycle minutes (zones / priority). Must match [classify]
         * so RED rows are not hidden by a flatter due clock.
         */
        val cycleMinutesFor: ((TreatSegment) -> Int)? = null,
        /** Skip overdue rows already covered by an assigned task/route. */
        val suppressOverdue: ((TreatSegment) -> Boolean)? = null,
        /** When true, skip the 25 km radius filter (no GPS fix yet). */
        val hasSelfFix: Boolean = true
    )

    fun build(input: Input): List<TaskingItem> {
        val cycle = input.cycleMinutes.coerceAtLeast(1)
        val model = FreshnessModel(cycleTimeMinutes = cycle)
        val classify = input.classify ?: { seg ->
            model.classify(seg.endTimeMs, input.nowMs, cycle)
        }
        // Task refIds only (segment / hazard ids) — route ids never match TreatSegment.id.
        val mineTaskRefs = HashSet<String>()
        val items = ArrayList<TaskingItem>()

        for (t in input.tasks) {
            if (t.targetVehicleUid != input.selfUid) continue
            if (t.state != TaskState.PENDING) continue
            val id = TaskingItem.taskId(t.uid)
            val baseDue = t.timeMs + input.escalateAfterMs.coerceAtLeast(60_000L)
            val due = effectiveDue(id, baseDue, input.snoozes)
            // Assigned tasks stay visible until snoozed past now.
            val snoozedUntil = input.snoozes[id]
            if (snoozedUntil != null && snoozedUntil > input.nowMs) continue
            if (t.refId.isNotEmpty()) mineTaskRefs.add(t.refId)
            items.add(
                TaskingItem(
                    id = id,
                    kind = TaskingItem.Kind.TASK,
                    title = t.description.ifBlank { t.kind.label },
                    lat = t.lat,
                    lon = t.lon,
                    dueByMs = due,
                    distanceM = distance(input, t.lat, t.lon),
                    mine = true,
                    refId = t.refId,
                    detail = "Assigned by ${t.assignedBy.ifBlank { "?" }}"
                )
            )
        }

        input.myRoute?.let { route ->
            val pct = route.coveragePercent
            if (pct != null && pct >= 100) return@let
            val id = TaskingItem.routeId(route.routeId)
            val due = effectiveDue(id, input.nowMs, input.snoozes)
            if (due > input.nowMs) return@let
            val detail = when {
                pct == null -> "Assigned route"
                else -> "$pct% covered"
            }
            items.add(
                TaskingItem(
                    id = id,
                    kind = TaskingItem.Kind.ROUTE,
                    title = "Route ${route.routeId}",
                    lat = route.lat,
                    lon = route.lon,
                    dueByMs = due,
                    distanceM = distance(input, route.lat, route.lon),
                    mine = true,
                    refId = route.routeId,
                    detail = detail
                )
            )
        }

        val overdue = ArrayList<TaskingItem>()
        for (seg in input.segments) {
            if (classify(seg) != Freshness.RED) continue
            if (seg.id in mineTaskRefs) continue
            if (input.suppressOverdue?.invoke(seg) == true) continue
            val mid = midpoint(seg) ?: continue
            val dist = distance(input, mid.first, mid.second)
            if (input.hasSelfFix && dist > OVERDUE_RADIUS_M) continue
            val id = TaskingItem.overdueId(seg.id)
            val segCycle = (input.cycleMinutesFor?.invoke(seg) ?: cycle).coerceAtLeast(1)
            val baseDue = seg.endTimeMs + segCycle * 60_000L
            val due = effectiveDue(id, baseDue, input.snoozes)
            // RED is authoritative; only an active snooze hides the row.
            val snoozedUntil = input.snoozes[id]
            if (snoozedUntil != null && snoozedUntil > input.nowMs) continue
            val label = seg.callsign.ifBlank { "Coverage" }
            overdue.add(
                TaskingItem(
                    id = id,
                    kind = TaskingItem.Kind.OVERDUE,
                    title = "$label · needs re-treat",
                    lat = mid.first,
                    lon = mid.second,
                    dueByMs = due,
                    distanceM = if (input.hasSelfFix) dist else Double.MAX_VALUE / 4,
                    mine = false,
                    refId = seg.id,
                    detail = "Overdue"
                )
            )
        }
        overdue.sortBy { it.distanceM }
        items.addAll(overdue.take(MAX_OVERDUE_ITEMS))

        return items.sortedWith(
            compareByDescending<TaskingItem> { it.mine }
                .thenBy { it.distanceM }
        )
    }

    fun formatDistanceMiles(distanceM: Double): String {
        val mi = distanceM / 1609.344
        return when {
            mi < 0.1 -> String.format("%.0f ft", distanceM * 3.28084)
            mi < 10.0 -> String.format("%.1f mi", mi)
            else -> String.format("%.0f mi", mi)
        }
    }

    private fun effectiveDue(id: String, baseDueMs: Long, snoozes: Map<String, Long>): Long {
        val snooze = snoozes[id] ?: return baseDueMs
        return maxOf(baseDueMs, snooze)
    }

    private fun distance(input: Input, lat: Double, lon: Double): Double {
        if (input.selfLat == 0.0 && input.selfLon == 0.0) return Double.MAX_VALUE / 4
        return GeoMath.distanceMeters(input.selfLat, input.selfLon, lat, lon)
    }

    private fun midpoint(seg: TreatSegment): Pair<Double, Double>? {
        val pts = seg.points
        if (pts.isEmpty()) return null
        val mid = pts[pts.size / 2]
        return mid.lat to mid.lon
    }
}
