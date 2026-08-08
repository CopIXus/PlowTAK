package com.atakmap.android.ideaplow

import android.content.Context
import android.util.Log
import com.atakmap.android.ideaplow.cot.OutboundCotQueue
import com.atakmap.android.ideaplow.cot.PlowCotListener
import com.atakmap.android.ideaplow.cot.PlowCotPublisher
import com.atakmap.android.ideaplow.coverage.CoverageStore
import com.atakmap.android.ideaplow.coverage.FreshnessModel
import com.atakmap.android.ideaplow.coverage.SwathBuilder
import com.atakmap.android.ideaplow.equipment.ManualEquipmentProvider
import com.atakmap.android.ideaplow.map.AlertOverlay
import com.atakmap.android.ideaplow.map.CoverageOverlay
import com.atakmap.android.ideaplow.map.FleetMarkerManager
import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.CapabilityRules
import com.atakmap.android.ideaplow.model.FacilityType
import com.atakmap.android.ideaplow.model.HazardType
import com.atakmap.android.ideaplow.model.StormSession
import com.atakmap.android.ideaplow.model.VehicleStatus
import com.atakmap.android.ideaplow.ops.AlertManager
import com.atakmap.android.ideaplow.ops.FacilityGeofences
import com.atakmap.android.ideaplow.ops.FleetManager
import com.atakmap.android.ideaplow.ops.ShiftLog
import com.atakmap.android.ideaplow.ops.StatusManager
import com.atakmap.android.ideaplow.ops.StormSessionManager
import com.atakmap.android.ideaplow.prefs.IdeaPlowPreferences
import com.atakmap.android.ideaplow.prefs.VehicleCapabilityStore
import com.atakmap.android.ideaplow.report.HazardReporter
import com.atakmap.android.ideaplow.service.IdeaPlowShiftService
import com.atakmap.android.ideaplow.tracking.SelfTracker
import com.atakmap.android.maps.MapView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Composition root for the IdeaPlow engine: owns every manager, wires the
 * data flow (GPS → treating rule → swath → store → overlay → CoT), and is
 * the single dependency handed to the UI panels. Created by
 * [IdeaPlowMapComponent.onCreate], torn down in [dispose].
 */
class IdeaPlowController(
    val pluginContext: Context,
    val mapView: MapView
) {

    // ----------------------------------------------------------- settings
    val prefs = IdeaPlowPreferences(pluginContext)
    val capabilityStore = VehicleCapabilityStore(pluginContext)

    // -------------------------------------------------------------- state
    val equipment = ManualEquipmentProvider()
    val statusManager = StatusManager()
    val shiftLog = ShiftLog(prefs)
    val stormManager = StormSessionManager(prefs)
    val facilityGeofences = FacilityGeofences(prefs)
    val fleetManager = FleetManager(staleAfterMs = prefs.staleAfterS * 1000L)
    val alertManager = AlertManager()

    // ----------------------------------------------------------- coverage
    val freshnessModel = FreshnessModel(
        cycleTimeMinutes = prefs.cycleTimeMinutes,
        retentionHours = prefs.retentionHours
    )
    val coverageStore = CoverageStore(File(pluginContext.filesDir, "ideaplow"))
    private val swathBuilder = SwathBuilder { segment -> coverageStore.addLocal(segment) }

    // ---------------------------------------------------------------- cot
    private val cotQueue = OutboundCotQueue()
    val cotPublisher = PlowCotPublisher(
        capabilityStore, prefs, statusManager, equipment,
        shiftLog, stormManager, coverageStore, cotQueue
    )
    private val cotListener = PlowCotListener(
        selfUid = { capabilityStore.vehicleUid },
        fleetManager = fleetManager,
        coverageStore = coverageStore,
        alertManager = alertManager,
        stormManager = stormManager
    )

    // ------------------------------------------------------------ display
    val coverageOverlay = CoverageOverlay(mapView, coverageStore, freshnessModel)
    val fleetMarkers = FleetMarkerManager(mapView, fleetManager)
    val alertOverlay = AlertOverlay(mapView, alertManager)
    val hazardReporter = HazardReporter(cotQueue)

    // ------------------------------------------------------------- timers
    private val tracker = SelfTracker(mapView) { prefs.gpsCeThresholdM }
    private var timers: ScheduledExecutorService? = null

    private var lastPositionSample: SelfTracker.PositionSample? = null

    fun start() {
        Log.i(TAG, "starting IdeaPlow engine")

        coverageStore.setStorm(stormManager.activeStormId)
        coverageOverlay.start()
        fleetMarkers.start()
        alertOverlay.start()
        cotListener.start()
        equipment.start()

        // GPS fan-out: swath recording, geofences, publisher pacing.
        tracker.addListener(recordingListener)
        tracker.addListener(cotPublisher)
        tracker.start()

        // Equipment / shift changes update the derived status.
        equipment.addListener { refreshTreatingState() }
        shiftLog.addListener { shift ->
            statusManager.updateShift(shift != null)
            refreshTreatingState()
            if (shift != null) {
                IdeaPlowShiftService.start(
                    mapView.context,
                    "${capabilityStore.load().callsign} — ${statusManager.current.label}"
                )
            } else {
                swathBuilder.flush()
                IdeaPlowShiftService.stop(mapView.context)
            }
        }
        statusManager.addListener { status ->
            refreshTreatingState()
            if (shiftLog.isOnShift) {
                IdeaPlowShiftService.start(
                    mapView.context,
                    "${capabilityStore.load().callsign} — ${status.label}"
                )
            }
        }

        // Storm changes re-scope coverage.
        stormManager.addListener { session ->
            swathBuilder.flush()
            coverageStore.setStorm(session?.takeIf { it.isActive }?.id ?: "")
        }

        // Facility transitions: suggest LOADING at salt domes.
        facilityGeofences.addListener { t ->
            if (t.entered && t.facility.type == FacilityType.SALT_DOME) {
                statusManager.suggest(VehicleStatus.LOADING, t.facility.name)
            }
        }

        // Local alert transitions go out over CoT.
        alertManager.addListener(object : AlertManager.Listener {
            override fun onAlertsChanged(alerts: List<AlertEvent>) {}
            override fun onLocalTransition(alert: AlertEvent) {
                cotPublisher.publishAlert(alert)
            }
        })

        // Slow shared timer: recolor coverage, staleness, retention prune.
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "IdeaPlow-Timers").apply { isDaemon = true }
        }
        timers = exec
        exec.scheduleWithFixedDelay({
            try {
                val now = System.currentTimeMillis()
                freshnessModel.cycleTimeMinutes = prefs.cycleTimeMinutes
                freshnessModel.retentionHours = prefs.retentionHours
                fleetManager.staleAfterMs = prefs.staleAfterS * 1000L
                coverageOverlay.recolorAll(now)
                fleetMarkers.refreshStaleness(now)
                coverageStore.pruneExpired(freshnessModel, now)
            } catch (e: Exception) {
                Log.e(TAG, "recolor tick failed", e)
            }
        }, RECOLOR_PERIOD_S, RECOLOR_PERIOD_S, TimeUnit.SECONDS)
        exec.scheduleWithFixedDelay({
            try {
                alertOverlay.pulse()
            } catch (e: Exception) {
                Log.e(TAG, "pulse tick failed", e)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    fun dispose() {
        Log.i(TAG, "disposing IdeaPlow engine")
        try {
            swathBuilder.flush()
        } catch (e: Exception) {
            Log.w(TAG, "flush on dispose failed", e)
        }
        tracker.stop()
        timers?.shutdownNow()
        timers = null
        cotListener.stop()
        equipment.stop()
        coverageOverlay.dispose()
        fleetMarkers.dispose()
        alertOverlay.dispose()
        IdeaPlowShiftService.stop(mapView.context)
    }

    // ------------------------------------------------------------ actions

    /** Latest GPS fix, for UI actions that need "here". */
    val lastPosition: SelfTracker.PositionSample? get() = lastPositionSample

    /** One-tap distress from the local unit. */
    fun sendDistress() {
        val cap = capabilityStore.load()
        if (!cap.canSendDistress) return
        val pos = lastPositionSample ?: return
        alertManager.raiseLocal(
            AlertEvent(
                uid = AlertEvent.makeUid(capabilityStore.vehicleUid),
                vehicleUid = capabilityStore.vehicleUid,
                callsign = cap.callsign,
                vehicleType = cap.type,
                lat = pos.lat,
                lon = pos.lon,
                timeMs = System.currentTimeMillis(),
                bladeDown = equipment.state.bladeDown,
                saltOn = equipment.state.saltOn
            )
        )
    }

    /** Cancel our own outstanding distress. */
    fun clearOwnDistress() {
        val uid = AlertEvent.makeUid(capabilityStore.vehicleUid)
        alertManager.clear(uid, capabilityStore.load().callsign)
    }

    /** One-tap hazard drop at the current position. */
    fun reportHazard(type: HazardType) {
        val pos = lastPositionSample ?: return
        val cap = capabilityStore.load()
        hazardReporter.report(
            type, pos.lat, pos.lon,
            capabilityStore.vehicleUid, cap.callsign, stormManager.activeStormId
        )
    }

    /** Supervisor: start a storm session and broadcast it. */
    fun startStormSession(): StormSession? {
        val cap = capabilityStore.load()
        if (!cap.canManageStorm) return null
        val session = stormManager.startSession(cap.callsign, System.currentTimeMillis())
        broadcastStorm(session)
        return session
    }

    /** Supervisor: end the active storm session and broadcast it. */
    fun endStormSession(): StormSession? {
        val cap = capabilityStore.load()
        if (!cap.canManageStorm) return null
        val session = stormManager.endSession(System.currentTimeMillis()) ?: return null
        broadcastStorm(session)
        return session
    }

    private fun broadcastStorm(session: StormSession) {
        val pos = lastPositionSample
        cotPublisher.publishStormSession(session, pos?.lat ?: 0.0, pos?.lon ?: 0.0)
    }

    // ------------------------------------------------------------ wiring

    private val recordingListener = SelfTracker.Listener { sample ->
        lastPositionSample = sample

        val cap = capabilityStore.load()
        val shift = shiftLog.currentShift
        val treating = isTreatingNow() && sample.gpsOk

        swathBuilder.setContext(
            SwathBuilder.Context(
                vehicleUid = capabilityStore.vehicleUid,
                callsign = cap.callsign,
                stormId = stormManager.activeStormId,
                operatorId = shift?.operatorId ?: ""
            )
        )
        swathBuilder.onSample(
            lat = sample.lat,
            lon = sample.lon,
            headingDeg = sample.headingDeg,
            timeMs = sample.timeMs,
            treating = treating,
            material = CapabilityRules.materialMode(
                cap, equipment.state.bladeDown, equipment.state.saltOn
            ),
            widthM = cap.plowWidthM
        )

        facilityGeofences.update(
            sample.lat, sample.lon, sample.timeMs, shift?.operatorId ?: ""
        )
    }

    /** The treating rule, evaluated against capability + equipment + shift. */
    fun isTreatingNow(): Boolean {
        if (!shiftLog.isOnShift) return false
        val cap = capabilityStore.load()
        return CapabilityRules.isTreating(
            cap, prefs.treatRule, equipment.state.bladeDown, equipment.state.saltOn
        )
    }

    private fun refreshTreatingState() {
        statusManager.updateTreating(isTreatingNow())
    }

    companion object {
        private const val TAG = "IdeaPlowController"
        private const val RECOLOR_PERIOD_S = 30L
    }
}
