package com.atakmap.android.plowtak.cot

import android.util.Log
import com.atakmap.android.chat.ChatManagerMapComponent
import com.atakmap.android.contact.Contacts
import com.atakmap.android.plowtak.cot.codec.AlertCotCodec
import com.atakmap.android.plowtak.cot.codec.RoadConditionCotCodec
import com.atakmap.android.plowtak.cot.codec.StormCotCodec
import com.atakmap.android.plowtak.equipment.EquipmentProvider
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.ReportLabels
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.maps.MapView
import com.atakmap.android.plowtak.ops.RouteAssignment
import com.atakmap.android.plowtak.ops.ShiftLog
import com.atakmap.android.plowtak.ops.StatusManager
import com.atakmap.android.plowtak.ops.StormSessionManager
import com.atakmap.android.plowtak.prefs.PlowTakPreferences
import com.atakmap.android.plowtak.prefs.VehicleCapabilityStore
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.tracking.SelfTracker
import com.atakmap.coremap.cot.event.CotDetail
import com.atakmap.coremap.cot.event.CotEvent
import com.atakmap.coremap.cot.event.CotPoint
import com.atakmap.coremap.maps.time.CoordinatedTime

/**
 * Publishes this unit's outbound traffic:
 *  - **TAK CoT (external):** self PLI (callsign + full truck remarks),
 *    distress alerts, storm session announce (discovery / SA).
 *  - **Local-only CoT:** map markers for hazards / road conditions so the
 *    reporter sees them immediately.
 *  - **Data Sync:** blade/spread/status, coverage, routes, zones, tasks,
 *    hazards, and conditions — see [MissionCoverageSync].
 *
 * Driven by the 1 Hz [SelfTracker] tick — no timers of its own. External
 * sends go through [OutboundCotQueue] for offline queueing.
 */
class PlowCotPublisher(
    private val capabilityStore: VehicleCapabilityStore,
    private val prefs: PlowTakPreferences,
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
            // Coverage / status / ops share via Data Sync (MissionCoverageSync).
            queue.onTick()
        } catch (e: Exception) {
            Log.e(TAG, "publish tick failed", e)
        }
    }

    // ----------------------------------------------------------------- PLI

    /**
     * Self PLI to the TAK server with free-text remarks covering unit status,
     * shift, storm, setup widths, and live equipment. Structured blade/spread
     * for peers still rides Data Sync (`{uid}-status.json`).
     */
    private fun publishPli(sample: SelfTracker.PositionSample) {
        val cap = capabilityStore.load()
        if (!cap.publishPresence) return

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
        val remarks = CotDetail("remarks")
        remarks.innerText = PliRemarks.format(
            statusLabel = statusManager.current.label,
            onShift = shiftLog.isOnShift,
            stormName = stormManager.activeSession()?.displayName(),
            capability = cap,
            equipment = equipment.state
        )
        root.addChild(remarks)
        event.detail = root

        queue.send(event, alsoInternal = false) // self marker already local
    }

    // ------------------------------------------------------------- alerts

    /** Broadcast a distress alert or an ack/clear transition (TAK CoT). */
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

    /** Broadcast a storm session start/end for fleet discovery (TAK CoT). */
    fun publishStormSession(session: StormSession, lat: Double, lon: Double) {
        val event = newEvent(
            uid = "plowtak-storm-${session.id}",
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

    /**
     * Local bookkeeping only — zones are shared via Data Sync ops snapshot.
     * Kept so call sites stay stable; no TAK CoT.
     */
    fun publishZone(zone: SpecialZone, removed: Boolean) {
        Log.d(TAG, "zone ${zone.id} ${if (removed) "removed" else "updated"} (Data Sync)")
    }

    // -------------------------------------------------------------- tasks

    /** Tasks share via Data Sync; GeoChat ping retained for operator nudge. */
    fun publishTask(task: TaskEvent) {
        Log.d(TAG, "task ${task.uid} → Data Sync (no CoT)")
        sendTaskGeoChat(task)
    }

    /** Best-effort GeoChat ping alongside the task (fail-open). */
    private fun sendTaskGeoChat(task: TaskEvent) {
        try {
            val desc = task.description.ifEmpty { task.kind.name }
            val text = "PlowTAK task: " + desc + " -> " + task.targetCallsign
            val contact = Contacts.getInstance()?.getContactByUuid(task.targetVehicleUid)
            if (contact != null) {
                ChatManagerMapComponent.getInstance()?.sendMessage(text, listOf(contact))
            } else {
                ChatManagerMapComponent.getInstance()?.sendMessage(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GeoChat task ping failed (fail-open)", e)
        }
    }

    // ---------------------------------------------------- route assignment

    /** Routes share via Data Sync — no TAK CoT. */
    fun publishRouteAssignment(assignment: RouteAssignment, lat: Double, lon: Double) {
        Log.d(TAG, "route assign ${assignment.vehicleUid} (Data Sync)")
    }

    // --------------------------------------------------- road conditions

    /**
     * Local map marker only; Data Sync carries the shared condition set.
     * [staleMinutes] comes from the Road Condition stale setting (default 2 h)
     * so the marker auto-expires with the same TTL as the mission upload.
     */
    fun publishRoadCondition(
        report: RoadConditionReport,
        staleMinutes: Int = StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES
    ) {
        try {
            if (MapView.getMapView()?.rootGroup?.deepFindUID(report.uid) != null) return
        } catch (_: Throwable) {
            // fall through and publish
        }
        val staleS = (staleMinutes.coerceIn(15, 24 * 60) * 60).coerceAtLeast(60)
        val title = ReportLabels.condition(
            report.condition.label, report.reporterCallsign, report.timeMs
        )
        val event = newEvent(
            uid = report.uid,
            type = RoadConditionCotCodec.CONDITION_MARKER_TYPE,
            lat = report.lat,
            lon = report.lon,
            staleSeconds = staleS
        )
        event.how = "h-g-i-g-o" // human-observed
        val root = CotDetail("detail")
        val contact = CotDetail("contact")
        contact.setAttribute("callsign", title)
        root.addChild(contact)
        val remarks = CotDetail("remarks")
        remarks.innerText =
            "Road ${report.condition.label} reported by ${report.reporterCallsign}"
        root.addChild(remarks)
        root.addChild(CotDetailAdapter.toCotDetail(RoadConditionCotCodec.encode(report)))
        event.detail = root
        queue.sendLocalOnly(event)
    }

    /**
     * Drop a stale road-condition marker from the local map (TTL expired).
     * Dispatches a CoT with stale=now and removes the map item by UID.
     */
    fun withdrawRoadCondition(report: RoadConditionReport, mapView: MapView) {
        try {
            val event = newEvent(
                uid = report.uid,
                type = RoadConditionCotCodec.CONDITION_MARKER_TYPE,
                lat = report.lat,
                lon = report.lon,
                staleSeconds = 0
            )
            event.how = "h-g-i-g-o"
            queue.sendLocalOnly(event)
        } catch (e: Exception) {
            Log.w(TAG, "withdraw CoT failed for ${report.uid}", e)
        }
        mapView.post {
            try {
                mapView.rootGroup.deepFindUID(report.uid)?.removeFromGroup()
            } catch (e: Exception) {
                Log.w(TAG, "withdraw map item failed for ${report.uid}", e)
            }
        }
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
        event.setPoint(CotPoint(lat, lon, CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN))
        return event
    }

    companion object {
        private const val TAG = "PlowTakCotPublisher"

        /** Ground equipment / vehicle PLI type. */
        const val PLI_EVENT_TYPE = "a-f-G-E-V-C"

        private const val ALERT_STALE_S = 3600
        private const val STORM_STALE_S = 24 * 3600
    }
}
