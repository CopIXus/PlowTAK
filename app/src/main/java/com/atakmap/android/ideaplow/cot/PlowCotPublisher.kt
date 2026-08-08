package com.atakmap.android.ideaplow.cot

import android.util.Log
import com.atakmap.android.ideaplow.cot.codec.AlertCotCodec
import com.atakmap.android.ideaplow.cot.codec.CoverageCotCodec
import com.atakmap.android.ideaplow.cot.codec.IdeaPlowDetail
import com.atakmap.android.ideaplow.cot.codec.RoadConditionCotCodec
import com.atakmap.android.ideaplow.cot.codec.RouteAssignmentCotCodec
import com.atakmap.android.ideaplow.cot.codec.StormCotCodec
import com.atakmap.android.ideaplow.cot.codec.TaskCotCodec
import com.atakmap.android.ideaplow.cot.codec.ZoneCotCodec
import com.atakmap.android.ideaplow.equipment.EquipmentProvider
import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.AlertState
import com.atakmap.android.ideaplow.model.RoadConditionReport
import com.atakmap.android.ideaplow.model.SpecialZone
import com.atakmap.android.ideaplow.model.StormSession
import com.atakmap.android.ideaplow.model.TaskEvent
import com.atakmap.android.ideaplow.ops.RouteAssignment
import com.atakmap.android.ideaplow.ops.ShiftLog
import com.atakmap.android.ideaplow.ops.StatusManager
import com.atakmap.android.ideaplow.ops.StormSessionManager
import com.atakmap.android.ideaplow.prefs.IdeaPlowPreferences
import com.atakmap.android.ideaplow.prefs.VehicleCapabilityStore
import com.atakmap.android.ideaplow.coverage.CoverageStore
import com.atakmap.android.ideaplow.tracking.SelfTracker
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime

/**
 * Publishes this unit's CoT:
 *  - periodic self PLI with the `<__ideaplow>` detail (only when
 *    publishPresence), paced faster while moving;
 *  - batched coverage-segment events drained from [CoverageStore];
 *  - distress alerts + ack/clear transitions;
 *  - storm session start/end broadcasts.
 *
 * Driven by the 1 Hz [SelfTracker] tick — no timers of its own. All sends go
 * through [OutboundCotQueue] for offline queueing.
 */
class PlowCotPublisher(
    private val capabilityStore: VehicleCapabilityStore,
    private val prefs: IdeaPlowPreferences,
    private val statusManager: StatusManager,
    private val equipment: EquipmentProvider,
    private val shiftLog: ShiftLog,
    private val stormManager: StormSessionManager,
    private val coverageStore: CoverageStore,
    private val queue: OutboundCotQueue,
    /** Reloads logged this storm, carried in the PLI for supervisor metrics. */
    private val reloadCount: () -> Int = { 0 }
) : SelfTracker.Listener {

    private var lastPliMs = 0L
    private var lastCoverageShareMs = 0L
    private var moving = false

    override fun onPosition(sample: SelfTracker.PositionSample) {
        try {
            // Simple hysteresis-free movement estimate at the 1 Hz tick rate.
            moving = sample.movedM > 1.5

            val now = sample.timeMs
            val intervalMs = 1000L *
                    (if (moving) prefs.reportIntervalMovingS else prefs.reportIntervalStoppedS)
            if (now - lastPliMs >= intervalMs) {
                lastPliMs = now
                publishPli(sample)
            }
            if (now - lastCoverageShareMs >= COVERAGE_SHARE_INTERVAL_MS) {
                lastCoverageShareMs = now
                shareCoverage()
            }
            queue.onTick()
        } catch (e: Exception) {
            Log.e(TAG, "publish tick failed", e)
        }
    }

    // ----------------------------------------------------------------- PLI

    private fun publishPli(sample: SelfTracker.PositionSample) {
        val cap = capabilityStore.load()
        if (!cap.publishPresence) return

        val detail = IdeaPlowDetail.fromLocalState(
            cap = cap,
            status = statusManager.current,
            bladeDown = equipment.state.bladeDown,
            saltOn = equipment.state.saltOn,
            material = equipment.state.material,
            headingDeg = sample.headingDeg,
            stormId = stormManager.activeStormId,
            operatorId = shiftLog.currentShift?.operatorId ?: "",
            operatorName = shiftLog.currentShift?.operatorName ?: "",
            widthPreset = equipment.state.widthPreset,
            reloadCount = reloadCount()
        )

        // Contractor units publish under the per-storm CTR uid (Phase 3).
        val selfUid = capabilityStore.effectiveUid(stormManager.activeStormId)
        val event = newEvent(
            uid = selfUid,
            type = PLI_EVENT_TYPE,
            lat = sample.lat,
            lon = sample.lon,
            staleSeconds = (prefs.staleAfterS * 2).coerceAtLeast(30)
        )
        val root = CotDetail("detail")
        val contact = CotDetail("contact")
        contact.setAttribute("callsign", cap.callsign.ifEmpty { selfUid })
        root.addChild(contact)
        root.addChild(CotDetailAdapter.toCotDetail(detail.toNode()))
        event.detail = root

        queue.send(event, alsoInternal = false) // self marker already local
    }

    // ------------------------------------------------------------ coverage

    private fun shareCoverage() {
        val batch = coverageStore.drainPendingShare(CoverageCotCodec.MAX_SEGMENTS_PER_EVENT)
        if (batch.isEmpty()) return
        try {
            val stormId = stormManager.activeStormId
            val node = CoverageCotCodec.encode(stormId, batch)
            val first = batch.first().points.first()

            val event = newEvent(
                uid = "${capabilityStore.effectiveUid(stormId)}-cov-${batch.first().startTimeMs}",
                type = CoverageCotCodec.COVERAGE_EVENT_TYPE,
                lat = first.lat,
                lon = first.lon,
                staleSeconds = (prefs.retentionHours * 3600).toInt()
            )
            val root = CotDetail("detail")
            root.addChild(CotDetailAdapter.toCotDetail(node))
            event.detail = root

            queue.send(event, alsoInternal = false) // already in local store
        } catch (e: Exception) {
            Log.e(TAG, "coverage share failed; requeueing ${batch.size} segments", e)
            coverageStore.requeueForShare(batch)
        }
    }

    // ------------------------------------------------------------- alerts

    /** Broadcast a distress alert or an ack/clear transition. */
    fun publishAlert(alert: AlertEvent) {
        val type = if (alert.state == AlertState.CLEARED)
            AlertCotCodec.DISTRESS_CANCEL_TYPE
        else
            AlertCotCodec.DISTRESS_EVENT_TYPE

        val event = newEvent(
            uid = alert.uid,
            type = type,
            lat = alert.lat,
            lon = alert.lon,
            staleSeconds = ALERT_STALE_S
        )
        val root = CotDetail("detail")
        val contact = CotDetail("contact")
        contact.setAttribute("callsign", alert.callsign)
        root.addChild(contact)
        root.addChild(CotDetailAdapter.toCotDetail(AlertCotCodec.encode(alert)))
        event.detail = root

        queue.send(event)
    }

    // ------------------------------------------------------------- storm

    /** Broadcast a storm session start/end (supervisor only). */
    fun publishStormSession(session: StormSession, lat: Double, lon: Double) {
        val event = newEvent(
            uid = "ideaplow-storm-${session.id}",
            type = StormCotCodec.STORM_EVENT_TYPE,
            lat = lat,
            lon = lon,
            staleSeconds = STORM_STALE_S
        )
        val root = CotDetail("detail")
        root.addChild(CotDetailAdapter.toCotDetail(StormCotCodec.encode(session)))
        event.detail = root

        queue.send(event, alsoInternal = false)
    }

    // -------------------------------------------------------------- zones

    /** Broadcast a special-zone add/edit ([removed]=false) or removal. */
    fun publishZone(zone: SpecialZone, removed: Boolean) {
        val cap = capabilityStore.load()
        val now = System.currentTimeMillis()
        val event = newEvent(
            uid = "ideaplow-zone-${zone.id}",
            type = ZoneCotCodec.ZONE_EVENT_TYPE,
            lat = zone.centerLat,
            lon = zone.centerLon,
            staleSeconds = ZONE_STALE_S
        )
        val root = CotDetail("detail")
        root.addChild(
            CotDetailAdapter.toCotDetail(ZoneCotCodec.encode(zone, removed, cap.callsign, now))
        )
        event.detail = root
        queue.send(event, alsoInternal = false) // zone already in the local manager
    }

    // -------------------------------------------------------------- tasks

    /** Broadcast a task create or state transition (same uid re-send). */
    fun publishTask(task: TaskEvent) {
        val event = newEvent(
            uid = task.uid,
            type = TaskCotCodec.TASK_EVENT_TYPE,
            lat = task.lat,
            lon = task.lon,
            staleSeconds = TASK_STALE_S
        )
        val root = CotDetail("detail")
        root.addChild(CotDetailAdapter.toCotDetail(TaskCotCodec.encode(task)))
        event.detail = root
        queue.send(event, alsoInternal = false)

        // SDK-fixup: GeoChat companion message so the tasked driver also gets
        // a chat ping. The 5.8 chat API surface (ChatManagerMapComponent /
        // GeoChatService) needs verification against the real main.jar before
        // enabling; the task CoT + panel + TTS already cover the alerting.
        // ChatManagerMapComponent.getInstance().sendMessage(...)
    }

    // ---------------------------------------------------- route assignment

    /** Broadcast a route assignment (or its empty-route tombstone). */
    fun publishRouteAssignment(assignment: RouteAssignment, lat: Double, lon: Double) {
        val event = newEvent(
            uid = RouteAssignmentCotCodec.eventUidFor(assignment.vehicleUid),
            type = RouteAssignmentCotCodec.ROUTE_EVENT_TYPE,
            lat = lat,
            lon = lon,
            staleSeconds = ROUTE_STALE_S
        )
        val root = CotDetail("detail")
        root.addChild(
            CotDetailAdapter.toCotDetail(RouteAssignmentCotCodec.encode(assignment))
        )
        event.detail = root
        queue.send(event, alsoInternal = false) // already in the local manager
    }

    // --------------------------------------------------- road conditions

    /** Broadcast a road-condition quick report as a map-point marker. */
    fun publishRoadCondition(report: RoadConditionReport) {
        val event = newEvent(
            uid = report.uid,
            type = RoadConditionCotCodec.CONDITION_MARKER_TYPE,
            lat = report.lat,
            lon = report.lon,
            staleSeconds = CONDITION_STALE_S
        )
        event.how = "h-g-i-g-o" // human-observed
        val root = CotDetail("detail")
        val contact = CotDetail("contact")
        contact.setAttribute(
            "callsign", "${report.condition.label} (${report.reporterCallsign})"
        )
        root.addChild(contact)
        val remarks = CotDetail("remarks")
        remarks.innerText =
            "Road ${report.condition.label} reported by ${report.reporterCallsign}"
        root.addChild(remarks)
        root.addChild(CotDetailAdapter.toCotDetail(RoadConditionCotCodec.encode(report)))
        event.detail = root
        queue.send(event) // internal too — reporter sees their own marker
    }

    // ------------------------------------------------------------ helpers

    private fun newEvent(
        uid: String,
        type: String,
        lat: Double,
        lon: Double,
        staleSeconds: Int
    ): CotEvent {
        val event = CotEvent()
        event.uid = uid
        event.type = type
        event.how = "m-g"
        val now = CoordinatedTime()
        event.time = now
        event.start = now
        event.stale = now.addSeconds(staleSeconds)
        event.cotPoint = CotPoint(lat, lon, CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN)
        return event
    }

    companion object {
        private const val TAG = "IdeaPlowCotPublisher"

        /** Ground equipment / vehicle PLI type. */
        const val PLI_EVENT_TYPE = "a-f-G-E-V-C"

        private const val COVERAGE_SHARE_INTERVAL_MS = 20_000L
        private const val ALERT_STALE_S = 3600
        private const val STORM_STALE_S = 24 * 3600
        private const val ZONE_STALE_S = 7 * 24 * 3600
        private const val TASK_STALE_S = 4 * 3600
        private const val ROUTE_STALE_S = 24 * 3600
        private const val CONDITION_STALE_S = 4 * 3600
    }
}
