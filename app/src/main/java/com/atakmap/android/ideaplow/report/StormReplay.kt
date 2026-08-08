package com.atakmap.android.ideaplow.report

import com.atakmap.android.ideaplow.coverage.Freshness
import com.atakmap.android.ideaplow.coverage.FreshnessModel
import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.AlertState
import com.atakmap.android.ideaplow.model.HazardEvent
import com.atakmap.android.ideaplow.model.TreatSegment

/**
 * Storm replay timeline engine (Phase 3). Given a storm session's stored
 * record — segments, alerts, hazards (the same [StormExportData] snapshot
 * exports use) — produces the playback state at any timestamp T: which
 * segments existed *as of T*, their freshness *as of T*, and which
 * alerts/hazards were live. The supervisor replay panel drags a time
 * slider and re-renders the coverage overlay from [stateAt]; the engine
 * itself is pure and fully testable.
 *
 * Alert timelines: an alert exists from its [AlertEvent.timeMs]. Because
 * the stored record keeps only the final state (ACKED/CLEARED, with no
 * transition timestamps), replay shows an alert as ACTIVE from creation
 * until [alertActiveWindowMs] and as its final state after that — a
 * documented approximation that keeps the engine honest about what the
 * record actually contains.
 */
class StormReplay(
    data: StormExportData,
    private val freshness: FreshnessModel = FreshnessModel(),
    /** How long a later-resolved alert renders as ACTIVE during replay. */
    private val alertActiveWindowMs: Long = 15 * 60_000L
) {

    /** Everything the overlay needs to render one replay frame. */
    data class Frame(
        val timeMs: Long,
        /** Segments completed by this time, newest last. */
        val segments: List<TreatSegment>,
        /** Freshness per segment id, as of [timeMs]. */
        val freshnessById: Map<String, Freshness>,
        val activeAlerts: List<AlertEvent>,
        val resolvedAlerts: List<AlertEvent>,
        val hazards: List<HazardEvent>
    ) {
        val segmentCount: Int get() = segments.size
    }

    // Sorted once; stateAt() binary-searches instead of rescanning.
    private val segmentsByEnd: List<TreatSegment> =
        data.segments.sortedBy { it.endTimeMs }
    private val alerts: List<AlertEvent> = data.alerts.sortedBy { it.timeMs }
    private val hazards: List<HazardEvent> = data.hazards.sortedBy { it.timeMs }

    /** Earliest event in the record (slider minimum); 0 for empty storms. */
    val startMs: Long = listOfNotNull(
        segmentsByEnd.firstOrNull()?.startTimeMs,
        alerts.firstOrNull()?.timeMs,
        hazards.firstOrNull()?.timeMs
    ).minOrNull() ?: 0L

    /** Latest event in the record (slider maximum); 0 for empty storms. */
    val endMs: Long = listOfNotNull(
        segmentsByEnd.lastOrNull()?.endTimeMs,
        alerts.lastOrNull()?.timeMs,
        hazards.lastOrNull()?.timeMs
    ).maxOrNull() ?: 0L

    val isEmpty: Boolean get() = endMs == 0L

    /** Playback state as of time [tMs]. Clamped to the record's range. */
    fun stateAt(tMs: Long): Frame {
        val t = tMs.coerceIn(startMs, maxOf(startMs, endMs))

        // Segments whose pass had *finished* by t (a half-recorded pass
        // isn't on anyone's map yet).
        val visible = segmentsByEnd.subList(0, countEndingAtOrBefore(t))
        val fresh = HashMap<String, Freshness>(visible.size)
        for (seg in visible) {
            fresh[seg.id] = freshness.classify(seg.endTimeMs, t)
        }

        val active = mutableListOf<AlertEvent>()
        val resolved = mutableListOf<AlertEvent>()
        for (a in alerts) {
            if (a.timeMs > t) continue
            val stillActive = a.state == AlertState.ACTIVE ||
                    t - a.timeMs < alertActiveWindowMs
            if (stillActive) active.add(a) else resolved.add(a)
        }

        return Frame(
            timeMs = t,
            segments = visible,
            freshnessById = fresh,
            activeAlerts = active,
            resolvedAlerts = resolved,
            hazards = hazards.filter { it.timeMs <= t }
        )
    }

    /**
     * Even time steps across the storm for slider tick marks / scrubbing.
     * Always includes both endpoints; a single point for empty storms.
     */
    fun timeline(steps: Int): List<Long> {
        require(steps >= 2) { "timeline needs at least 2 steps" }
        if (isEmpty || endMs <= startMs) return listOf(startMs)
        return (0 until steps).map { i ->
            startMs + (endMs - startMs) * i / (steps - 1)
        }
    }

    private fun countEndingAtOrBefore(t: Long): Int {
        var lo = 0
        var hi = segmentsByEnd.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (segmentsByEnd[mid].endTimeMs <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
