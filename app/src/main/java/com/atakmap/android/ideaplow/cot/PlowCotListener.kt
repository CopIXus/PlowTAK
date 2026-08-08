package com.atakmap.android.ideaplow.cot

import android.os.Bundle
import android.util.Log
import com.atakmap.android.ideaplow.cot.codec.AlertCotCodec
import com.atakmap.android.ideaplow.cot.codec.CoverageCotCodec
import com.atakmap.android.ideaplow.cot.codec.IdeaPlowDetail
import com.atakmap.android.ideaplow.cot.codec.RouteAssignmentCotCodec
import com.atakmap.android.ideaplow.cot.codec.StormCotCodec
import com.atakmap.android.ideaplow.cot.codec.TaskCotCodec
import com.atakmap.android.ideaplow.cot.codec.ZoneCotCodec
import com.atakmap.android.ideaplow.coverage.CoverageStore
import com.atakmap.android.ideaplow.model.CapabilityRules
import com.atakmap.android.ideaplow.model.PlowVehicle
import com.atakmap.android.ideaplow.ops.AlertManager
import com.atakmap.android.ideaplow.ops.FleetManager
import com.atakmap.android.ideaplow.ops.RouteAssignmentManager
import com.atakmap.android.ideaplow.ops.StormSessionManager
import com.atakmap.android.ideaplow.ops.TaskManager
import com.atakmap.android.ideaplow.ops.ZoneManager
import com.atakmap.comms.CotServiceRemote
import com.atakmap.coremap.cot.event.CotEvent

/**
 * Consumes inbound IdeaPlow CoT:
 *  - PLI with `<__ideaplow>` detail → [FleetManager];
 *  - coverage events → [CoverageStore], merged ONLY from canTreat units
 *    (supervisor/observer positions never paint roads);
 *  - distress alerts (and cancels) → [AlertManager];
 *  - storm session broadcasts → [StormSessionManager] adoption;
 *  - special-zone updates → [ZoneManager];
 *  - supervisor tasks + state transitions → [TaskManager].
 */
class PlowCotListener(
    private val selfUid: () -> String,
    private val fleetManager: FleetManager,
    private val coverageStore: CoverageStore,
    private val alertManager: AlertManager,
    private val stormManager: StormSessionManager,
    private val zoneManager: ZoneManager? = null,
    private val taskManager: TaskManager? = null,
    private val routeAssignments: RouteAssignmentManager? = null
) : CotServiceRemote.CotEventListener, CotServiceRemote.ConnectionListener {

    private var remote: CotServiceRemote? = null

    fun start() {
        if (remote != null) return
        val r = CotServiceRemote()
        r.setCotEventListener(this)
        r.connect(this)
        remote = r
    }

    fun stop() {
        try {
            remote?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed", e)
        }
        remote = null
    }

    override fun onCotServiceConnected(fullServiceState: Bundle?) {
        Log.d(TAG, "CoT service connected")
    }

    override fun onCotServiceDisconnected() {
        Log.d(TAG, "CoT service disconnected")
    }

    override fun onCotEvent(event: CotEvent?, extra: Bundle?) {
        if (event == null || !event.isValid) return
        if (event.uid == null || event.uid.startsWith(selfUid())) return // ignore self echo

        try {
            when {
                event.type == CoverageCotCodec.COVERAGE_EVENT_TYPE -> handleCoverage(event)
                event.type == StormCotCodec.STORM_EVENT_TYPE -> handleStorm(event)
                event.type == AlertCotCodec.DISTRESS_EVENT_TYPE ||
                        event.type == AlertCotCodec.DISTRESS_CANCEL_TYPE -> handleAlert(event)
                event.type == ZoneCotCodec.ZONE_EVENT_TYPE -> handleZone(event)
                event.type == TaskCotCodec.TASK_EVENT_TYPE -> handleTask(event)
                event.type == RouteAssignmentCotCodec.ROUTE_EVENT_TYPE ->
                    handleRouteAssignment(event)
                else -> handlePli(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed handling CoT ${event.uid} (${event.type})", e)
        }
    }

    private fun handlePli(event: CotEvent) {
        val node = CotDetailAdapter.findIdeaPlowNode(event.detail) ?: return
        val detail = IdeaPlowDetail.fromNode(node) ?: return
        val point = event.cotPoint ?: return

        val callsign = event.detail
            ?.getFirstChildByName(0, "contact")
            ?.getAttribute("callsign")
            ?: event.uid

        fleetManager.update(
            PlowVehicle(
                uid = event.uid,
                callsign = callsign,
                type = detail.vehicleType,
                status = detail.status,
                lat = point.lat,
                lon = point.lon,
                headingDeg = detail.headingDeg,
                lastUpdateMs = System.currentTimeMillis(),
                hasBlade = detail.hasBlade,
                hasSalt = detail.hasSalt,
                bladeDown = detail.bladeDown,
                saltOn = detail.saltOn,
                stormId = detail.stormId,
                operatorId = detail.operatorId,
                operatorName = detail.operatorName,
                reloadCount = detail.reloadCount
            )
        )
    }

    private fun handleCoverage(event: CotEvent) {
        val node = CotDetailAdapter.findIdeaPlowNode(event.detail) ?: return
        val segments = CoverageCotCodec.decode(node)
        var merged = 0
        for (seg in segments) {
            // Capability gate: only accept paint from units we know as
            // treat-capable. Unknown senders are trusted if their segment
            // claims a treat-capable identity — the segment itself came from
            // a SwathBuilder that only runs under the treating rule.
            val known = fleetManager.get(seg.vehicleUid)
            if (known != null && !CapabilityRules.paintsCoverage(known.type)) continue
            if (coverageStore.mergeRemote(seg)) merged++
        }
        if (merged > 0) Log.d(TAG, "merged $merged remote segments from ${event.uid}")
    }

    private fun handleAlert(event: CotEvent) {
        val node = CotDetailAdapter.findIdeaPlowNode(event.detail) ?: return
        val point = event.cotPoint ?: return
        val alert = AlertCotCodec.decode(node, event.uid, point.lat, point.lon) ?: return
        alertManager.onRemote(alert)
    }

    private fun handleZone(event: CotEvent) {
        val manager = zoneManager ?: return
        val node = CotDetailAdapter.findIdeaPlowNode(event.detail) ?: return
        val update = ZoneCotCodec.decode(node) ?: return
        if (manager.onRemote(update.zone, update.removed)) {
            Log.d(TAG, "zone ${update.zone.id} ${if (update.removed) "removed" else "updated"} by ${update.by}")
        }
    }

    private fun handleTask(event: CotEvent) {
        val manager = taskManager ?: return
        val node = CotDetailAdapter.findIdeaPlowNode(event.detail) ?: return
        val point = event.cotPoint ?: return
        val task = TaskCotCodec.decode(node, event.uid, point.lat, point.lon) ?: return
        manager.onRemote(task)
    }

    private fun handleRouteAssignment(event: CotEvent) {
        val manager = routeAssignments ?: return
        val node = CotDetailAdapter.findIdeaPlowNode(event.detail) ?: return
        val assignment = RouteAssignmentCotCodec.decode(node) ?: return
        manager.onRemote(assignment)
    }

    private fun handleStorm(event: CotEvent) {
        val node = CotDetailAdapter.findIdeaPlowNode(event.detail) ?: return
        val session = StormCotCodec.decode(node) ?: return
        stormManager.adoptRemote(session)
    }

    companion object {
        private const val TAG = "IdeaPlowCotListener"
    }
}
