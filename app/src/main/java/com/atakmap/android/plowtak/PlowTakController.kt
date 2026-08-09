package com.atakmap.android.plowtak

import android.content.Context
import android.util.Log
import com.atakmap.android.plowtak.cot.OutboundCotQueue
import com.atakmap.android.plowtak.cot.PlowCotListener
import com.atakmap.android.plowtak.cot.PlowCotPublisher
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.coverage.CycleResolver
import com.atakmap.android.plowtak.coverage.DirectionModel
import com.atakmap.android.plowtak.coverage.Freshness
import com.atakmap.android.plowtak.coverage.FreshnessModel
import com.atakmap.android.plowtak.coverage.RoadSnapper
import com.atakmap.android.plowtak.coverage.SwathBuilder
import com.atakmap.android.plowtak.equipment.BluetoothEquipmentProvider
import com.atakmap.android.plowtak.equipment.ManualEquipmentProvider
import com.atakmap.android.plowtak.gis.LaneModel
import com.atakmap.android.plowtak.gis.RoadNetwork
import com.atakmap.android.plowtak.gis.RoadNetworkImporter
import com.atakmap.android.plowtak.map.AlertOverlay
import com.atakmap.android.plowtak.map.CoverageOverlay
import com.atakmap.android.plowtak.map.FleetMarkerManager
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.CapabilityRules
import com.atakmap.android.plowtak.model.FacilityType
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.RoutePriority
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.ops.AlertManager
import com.atakmap.android.plowtak.ops.FacilityGeofences
import com.atakmap.android.plowtak.ops.FleetManager
import com.atakmap.android.plowtak.ops.ProvisioningCodec
import com.atakmap.android.plowtak.ops.ProvisioningProfile
import com.atakmap.android.plowtak.ops.RouteAssignment
import com.atakmap.android.plowtak.ops.RouteAssignmentManager
import com.atakmap.android.plowtak.ops.RouteCoverage
import com.atakmap.android.plowtak.ops.RouteCoverageResult
import com.atakmap.android.plowtak.ops.ShiftLog
import com.atakmap.android.plowtak.ops.StatusManager
import com.atakmap.android.plowtak.ops.StormSessionManager
import com.atakmap.android.plowtak.ops.TaskManager
import com.atakmap.android.plowtak.ops.ToggleSanity
import com.atakmap.android.plowtak.ops.ZoneManager
import com.atakmap.android.plowtak.prefs.PlowTakPreferences
import com.atakmap.android.plowtak.prefs.VehicleCapabilityStore
import com.atakmap.android.plowtak.report.ExportManager
import com.atakmap.android.plowtak.report.HazardReporter
import com.atakmap.android.plowtak.report.QuickPicHazardCapture
import com.atakmap.android.plowtak.report.MetricsCalculator
import com.atakmap.android.plowtak.report.StormExportData
import com.atakmap.android.plowtak.report.StormReplay
import com.atakmap.android.plowtak.service.PlowTakShiftService
import com.atakmap.android.plowtak.sync.MissionCoverageSync
import com.atakmap.android.plowtak.tracking.SelfTracker
import com.atakmap.android.plowtak.ui.VoiceAlerts
import com.atakmap.android.maps.MapView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Composition root for the PlowTak engine: owns every manager, wires the
 * data flow (GPS → treating rule → swath → store → overlay → CoT), and is
 * the single dependency handed to the UI panels. Created by
 * [PlowTakMapComponent.onCreate], torn down in [dispose].
 */
class PlowTakController(
    val pluginContext: Context,
    val mapView: MapView
) {

    // ----------------------------------------------------------- settings
    val prefs = PlowTakPreferences(pluginContext)
    val capabilityStore = VehicleCapabilityStore(pluginContext)

    // -------------------------------------------------------------- state
    val equipment = ManualEquipmentProvider()
    val statusManager = StatusManager()
    val shiftLog = ShiftLog(prefs)
    val stormManager = StormSessionManager(prefs)
    val facilityGeofences = FacilityGeofences(prefs)
    val fleetManager = FleetManager(staleAfterMs = prefs.staleAfterS * 1000L)
    val alertManager = AlertManager()
    val zoneManager = ZoneManager(prefs)
    val taskManager = TaskManager(escalateAfterMs = prefs.taskEscalateMinutes * 60_000L)
    val routeAssignments = RouteAssignmentManager(prefs)
    private val toggleSanity = ToggleSanity(
        ToggleSanity.Config(maxPlowSpeedMps = prefs.maxPlowSpeedMph * MPS_PER_MPH)
    )
    private val hazardLog = LinkedHashMap<String, HazardEvent>()
    private val conditionLog = LinkedHashMap<String, RoadConditionReport>()

    // ----------------------------------------------------------- coverage
    val freshnessModel = FreshnessModel(
        cycleTimeMinutes = prefs.cycleTimeMinutes,
        retentionHours = prefs.retentionHours
    )
    val coverageStore = CoverageStore(File(pluginContext.filesDir, "plowtak"))
    private val swathBuilder = SwathBuilder { segment -> coverageStore.addLocal(segment) }

    /** Optional GraphHopper road snapper; loaded async, fail-open to raw GPS. */
    @Volatile
    private var roadSnapper: RoadSnapper? = null

    // ---------------------------------------------------------------- cot
    private val cotQueue = OutboundCotQueue(
        persistFile = File(pluginContext.filesDir, "plowtak/outbound-cot.queue")
    )
    val cotPublisher = PlowCotPublisher(
        capabilityStore, prefs, statusManager, equipment,
        shiftLog, stormManager, coverageStore, cotQueue,
        reloadCount = {
            facilityGeofences.reloadCountSince(stormManager.current?.startTimeMs ?: 0L)
        }
    )
    private val cotListener = PlowCotListener(
        selfUid = { capabilityStore.effectiveUid(stormManager.activeStormId) },
        fleetManager = fleetManager,
        coverageStore = coverageStore,
        alertManager = alertManager,
        stormManager = stormManager,
        zoneManager = zoneManager,
        taskManager = taskManager,
        routeAssignments = routeAssignments,
        onHazard = { hazard -> synchronized(hazardLog) { hazardLog[hazard.uid] = hazard } },
        onCondition = { report -> synchronized(conditionLog) { conditionLog[report.uid] = report } }
    )

    /** Active publish UID (contractor storms use a temporary CTR-* identity). */
    fun selfUid(): String = capabilityStore.effectiveUid(stormManager.activeStormId)

    // ------------------------------------------------------------ display
    val coverageOverlay = CoverageOverlay(mapView, coverageStore, freshnessModel)
    val fleetMarkers = FleetMarkerManager(mapView, fleetManager)
    val alertOverlay = AlertOverlay(mapView, alertManager)
    val hazardReporter = HazardReporter(cotQueue)
    val exportManager = ExportManager(pluginContext)
    val voiceAlerts = VoiceAlerts(mapView.context) { prefs.ttsEnabled }
    private var btProvider: BluetoothEquipmentProvider? = null
    val quickPicHazards = QuickPicHazardCapture { type, photoName ->
        reportHazard(type, photoName, attachQuickPic = true)
    }
    val missionCoverageSync = MissionCoverageSync(
        appContext = mapView.context,
        prefs = prefs,
        coverageStore = coverageStore,
        vehicleUid = { selfUid() },
        activeStormId = { stormManager.activeStormId }
    )

    /** Set by the driver panel to surface forgot-to-toggle prompts. */
    @Volatile
    var sanityPromptListener: ((ToggleSanity.Prompt) -> Unit)? = null

    // ------------------------------------------------------------- timers
    private val tracker = SelfTracker(mapView) { prefs.gpsCeThresholdM }
    private var timers: ScheduledExecutorService? = null
    private val background = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PlowTak-Background").apply { isDaemon = true }
    }

    private var lastPositionSample: SelfTracker.PositionSample? = null
    private val seenAlertUids = HashSet<String>()
    private val seenTaskUids = HashSet<String>()
    private var lastOverdueCount = -1

    fun start() {
        Log.i(TAG, "starting PlowTak engine")

        coverageStore.setStorm(stormManager.activeStormId)

        // Phase 2 overlay hooks: per-segment cycle time (priority + zones)
        // and direction-split (half-treated) rendering.
        coverageOverlay.cycleMinutesHook = { segment ->
            CycleResolver.resolveForSegment(
                prefs.cycleTimes(), RoutePriority.DEFAULT, zoneManager.all(), segment
            )
        }
        refreshDirectionHook()

        coverageOverlay.start()
        fleetMarkers.start()
        alertOverlay.start()
        cotListener.start()
        equipment.start()
        reloadBluetoothLink()
        quickPicHazards.start()
        // After restart, re-queue persisted local segments that may not have
        // been shared before process death.
        coverageStore.queueLocalForShare()
        reloadRoadSnapper()

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
                PlowTakShiftService.start(
                    mapView.context,
                    "${capabilityStore.load().callsign} — ${statusManager.current.label}"
                )
            } else {
                swathBuilder.flush()
                toggleSanity.reset()
                PlowTakShiftService.stop(mapView.context)
            }
        }
        statusManager.addListener { status ->
            refreshTreatingState()
            if (shiftLog.isOnShift) {
                PlowTakShiftService.start(
                    mapView.context,
                    "${capabilityStore.load().callsign} — ${status.label}"
                )
            }
        }

        // Storm changes re-scope coverage; kick Data Sync mission upload.
        stormManager.addListener { session ->
            swathBuilder.flush()
            coverageStore.setStorm(session?.takeIf { it.isActive }?.id ?: "")
            val activeId = session?.takeIf { it.isActive }?.id
            if (activeId != null) {
                background.execute { missionCoverageSync.onStormStarted(activeId) }
            }
        }

        // Facility transitions: suggest LOADING at salt domes.
        facilityGeofences.addListener { t ->
            if (t.entered && t.facility.type == FacilityType.SALT_DOME) {
                statusManager.suggest(VehicleStatus.LOADING, t.facility.name)
            }
        }

        // Local alert transitions go out over CoT; new remote ones get voice.
        alertManager.addListener(object : AlertManager.Listener {
            override fun onAlertsChanged(alerts: List<AlertEvent>) {
                val self = selfUid()
                for (alert in alerts) {
                    if (alert.state != AlertState.ACTIVE) continue
                    if (alert.vehicleUid == self) continue
                    if (seenAlertUids.add(alert.uid + alert.timeMs)) {
                        voiceAlerts.distressNearby(alert.callsign)
                    }
                }
            }
            override fun onLocalTransition(alert: AlertEvent) {
                cotPublisher.publishAlert(alert)
            }
        })

        // Zone changes recolor coverage (stricter cycles apply immediately).
        zoneManager.addListener {
            coverageOverlay.recolorAll(System.currentTimeMillis())
        }

        // Route assignments: local supervisor edits broadcast over CoT.
        routeAssignments.addListener(object : RouteAssignmentManager.Listener {
            override fun onAssignmentsChanged(assignments: List<RouteAssignment>) = Unit
            override fun onLocalAssignment(assignment: RouteAssignment) {
                val pos = lastPositionSample
                cotPublisher.publishRouteAssignment(
                    assignment, pos?.lat ?: 0.0, pos?.lon ?: 0.0
                )
            }
        })

        // Task plumbing: local transitions broadcast; new tasks for this
        // vehicle get a voice ping; escalations re-alert the supervisor.
        taskManager.addListener(object : TaskManager.Listener {
            override fun onTasksChanged(tasks: List<TaskEvent>) {
                val self = selfUid()
                for (task in taskManager.pendingFor(self)) {
                    if (seenTaskUids.add(task.uid)) {
                        voiceAlerts.taskReceived(task.assignedBy)
                    }
                }
            }
            override fun onLocalTransition(task: TaskEvent) {
                cotPublisher.publishTask(task)
            }
            override fun onEscalated(task: TaskEvent) {
                if (capabilityStore.load().canManageStorm) {
                    voiceAlerts.say(
                        "escalation",
                        "Task for ${task.targetCallsign} not acknowledged.",
                        30_000L
                    )
                }
            }
        })

        // Slow shared timer: recolor coverage, staleness, retention prune,
        // task escalation, overdue voice.
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "PlowTak-Timers").apply { isDaemon = true }
        }
        timers = exec
        exec.scheduleWithFixedDelay({
            try {
                val now = System.currentTimeMillis()
                freshnessModel.cycleTimeMinutes = prefs.cycleTimeMinutes
                freshnessModel.retentionHours = prefs.retentionHours
                fleetManager.staleAfterMs = prefs.staleAfterS * 1000L
                taskManager.escalateAfterMs = prefs.taskEscalateMinutes * 60_000L
                refreshDirectionHook()
                coverageOverlay.recolorAll(now)
                fleetMarkers.refreshStaleness(now)
                coverageStore.pruneExpired(freshnessModel, now)
                coverageStore.pruneOverCount(prefs.maxRetainedSegments)
                taskManager.tick(now)
                taskManager.pruneTerminal(now)
                announceOverdue(now)
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
        // TAK Data Sync: replace this truck's live coverage GeoJSON every 5 min.
        exec.scheduleWithFixedDelay({
            background.execute {
                try {
                    missionCoverageSync.tick()
                } catch (e: Exception) {
                    Log.w(TAG, "mission coverage tick failed", e)
                }
            }
        }, MISSION_COV_PERIOD_S, MISSION_COV_PERIOD_S, TimeUnit.SECONDS)
    }

    fun dispose() {
        Log.i(TAG, "disposing PlowTak engine")
        try {
            swathBuilder.flush()
        } catch (e: Exception) {
            Log.w(TAG, "flush on dispose failed", e)
        }
        try {
            missionCoverageSync.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "mission coverage dispose failed", e)
        }
        tracker.stop()
        timers?.shutdownNow()
        timers = null
        background.shutdownNow()
        cotListener.stop()
        quickPicHazards.stop()
        btProvider?.stop()
        btProvider = null
        equipment.stop()
        coverageOverlay.dispose()
        fleetMarkers.dispose()
        alertOverlay.dispose()
        voiceAlerts.shutdown()
        PlowTakShiftService.stop(mapView.context)
    }

    /**
     * Manual / test trigger for TAK Data Sync mission coverage upload.
     * Prefer the 5-minute timer; this runs immediately on the background executor.
     */
    fun syncMissionCoverageNow() {
        background.execute {
            try {
                missionCoverageSync.tick()
            } catch (e: Exception) {
                Log.w(TAG, "syncMissionCoverageNow failed", e)
            }
        }
    }

    // ------------------------------------------------------------ actions

    /** Latest GPS fix, for UI actions that need "here". */
    val lastPosition: SelfTracker.PositionSample? get() = lastPositionSample

    /** One-tap distress from the local unit. */
    fun sendDistress() {
        val cap = capabilityStore.load()
        if (!cap.canSendDistress) return
        val pos = lastPositionSample ?: return
        val uid = selfUid()
        alertManager.raiseLocal(
            AlertEvent(
                uid = AlertEvent.makeUid(uid),
                vehicleUid = uid,
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
        val uid = AlertEvent.makeUid(selfUid())
        alertManager.clear(uid, capabilityStore.load().callsign)
    }

    /**
     * One-tap hazard drop at the current position. Pass [photoFile] when the
     * image is already known; for camera capture use [requestHazardWithQuickPic].
     */
    fun reportHazard(
        type: HazardType,
        photoFile: String = "",
        attachQuickPic: Boolean = false
    ) {
        val pos = lastPositionSample ?: return
        val cap = capabilityStore.load()
        val hazard = hazardReporter.report(
            type, pos.lat, pos.lon,
            selfUid(), cap.callsign, stormManager.activeStormId,
            photoFile
        )
        if (attachQuickPic && photoFile.isNotEmpty()) {
            quickPicHazards.attachToHazard(hazard.uid)
        }
        synchronized(hazardLog) { hazardLog[hazard.uid] = hazard }
    }

    /** Long-press hazard: open ATAK QuickPic, then publish with the photo. */
    fun requestHazardWithQuickPic(type: HazardType) {
        if (lastPositionSample == null) return
        quickPicHazards.request(type)
    }

    /** (Re)connect the Bluetooth plow/spreader controller from settings. */
    fun reloadBluetoothLink() {
        btProvider?.stop()
        btProvider = null
        if (!prefs.btEquipmentEnabled) return
        val addr = prefs.btDeviceAddress.trim()
        if (addr.isEmpty()) return
        val link = BluetoothEquipmentProvider(
            mapView.context, addr, prefs.btUseBle
        )
        link.addListener { hw -> equipment.applyHardware(hw) }
        btProvider = link
        link.start()
        Log.i(TAG, "Bluetooth equipment link starting ($addr, ble=${prefs.btUseBle})")
    }

    /** Quick road-condition report at the current position. */
    fun reportRoadCondition(condition: RoadCondition) {
        val pos = lastPositionSample ?: return
        val cap = capabilityStore.load()
        val now = System.currentTimeMillis()
        val report = RoadConditionReport(
            uid = RoadConditionReport.makeUid(selfUid(), now),
            condition = condition,
            reporterUid = selfUid(),
            reporterCallsign = cap.callsign,
            lat = pos.lat,
            lon = pos.lon,
            timeMs = now,
            stormId = stormManager.activeStormId
        )
        synchronized(conditionLog) { conditionLog[report.uid] = report }
        cotPublisher.publishRoadCondition(report)
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

    // ------------------------------------------------------ Phase 2 actions

    /** Supervisor: add or edit a special zone locally and broadcast it. */
    fun putSpecialZone(zone: SpecialZone) {
        if (!capabilityStore.load().canManageStorm) return
        zoneManager.put(zone)
        cotPublisher.publishZone(zone, removed = false)
    }

    /** Supervisor: remove a special zone locally and broadcast the removal. */
    fun removeSpecialZone(zoneId: String) {
        if (!capabilityStore.load().canManageStorm) return
        val zone = zoneManager.get(zoneId) ?: return
        if (zoneManager.remove(zoneId)) {
            cotPublisher.publishZone(zone, removed = true)
        }
    }

    /** Supervisor: create and broadcast a task for a vehicle. */
    fun createTask(
        targetUid: String,
        targetCallsign: String,
        kind: TaskKind,
        refId: String,
        lat: Double,
        lon: Double,
        description: String
    ): TaskEvent? {
        val cap = capabilityStore.load()
        if (!cap.canManageStorm) return null
        val now = System.currentTimeMillis()
        val task = TaskEvent(
            uid = TaskEvent.makeUid(selfUid(), now),
            targetVehicleUid = targetUid,
            targetCallsign = targetCallsign,
            assignedBy = cap.callsign,
            kind = kind,
            refId = refId,
            lat = lat,
            lon = lon,
            description = description,
            timeMs = now
        )
        taskManager.createLocal(task)
        return task
    }

    /** Supervisor: assign a unit to a GIS or drawn route; broadcasts over CoT. */
    fun assignRoute(
        vehicleUid: String,
        callsign: String,
        routeId: String,
        source: RouteAssignment.Source
    ) {
        if (!capabilityStore.load().canManageStorm) return
        routeAssignments.assign(
            RouteAssignment(
                vehicleUid = vehicleUid,
                callsign = callsign,
                routeId = routeId,
                source = source,
                assignedBy = capabilityStore.load().callsign,
                timeMs = System.currentTimeMillis()
            )
        )
    }

    /** Supervisor: clear a unit's route assignment. */
    fun unassignRoute(vehicleUid: String) {
        if (!capabilityStore.load().canManageStorm) return
        routeAssignments.unassign(
            vehicleUid,
            capabilityStore.load().callsign,
            System.currentTimeMillis()
        )
    }

    /** Driver: acknowledge a pending task (big green button). */
    fun ackTask(uid: String) {
        taskManager.ack(uid, capabilityStore.load().callsign, System.currentTimeMillis())
    }

    /** Driver: decline a pending task (big red button). */
    fun declineTask(uid: String) {
        taskManager.decline(uid, capabilityStore.load().callsign, System.currentTimeMillis())
    }

    /** Nearest dispatchable treat-capable truck to a point, for tasking. */
    fun suggestNearestTruck(lat: Double, lon: Double) =
        TaskManager.suggestNearest(
            fleetManager.all().filterNot {
                it.isStale(System.currentTimeMillis(), fleetManager.staleAfterMs)
            },
            lat, lon
        )

    /** Live supervisor metrics snapshot. */
    fun liveMetrics(): MetricsCalculator.StormMetrics =
        MetricsCalculator.calculate(
            coverageStore.all(), fleetManager.all(), prefs.cycleTimes(),
            zoneManager.all(), System.currentTimeMillis()
        )

    /**
     * Export the current storm session to GeoJSON + CSV on a background
     * thread; [onDone] is called with the folder path or null on failure.
     */
    fun exportStormSession(onDone: (String?) -> Unit) {
        val cap = capabilityStore.load()
        val data = StormExportData(
            stormId = stormManager.activeStormId.ifEmpty {
                stormManager.current?.id ?: ""
            },
            generatedAtMs = System.currentTimeMillis(),
            vehicleUid = selfUid(),
            callsign = cap.callsign,
            segments = coverageStore.all(),
            alerts = alertManager.all(),
            hazards = synchronized(hazardLog) { hazardLog.values.toList() },
            conditions = synchronized(conditionLog) { conditionLog.values.toList() },
            reloads = facilityGeofences.reloads(),
            shifts = shiftLog.shiftHistory() + listOfNotNull(shiftLog.currentShift)
        )
        background.execute {
            val result = exportManager.export(data)
            onDone(result?.folder?.absolutePath)
        }
    }

    /** Re-open (or drop) the road snapper after a settings change. */
    fun reloadRoadSnapper() {
        if (!prefs.roadSnapEnabled) {
            roadSnapper = null
            return
        }
        val dir = prefs.roadSnapDir
        if (dir.isEmpty()) {
            roadSnapper = null
            return
        }
        background.execute {
            val snapper = RoadSnapper.openOrNull(File(dir))
            roadSnapper = snapper
            Log.i(
                TAG,
                if (snapper != null) "road snapper ready from $dir"
                else "road snapper unavailable from $dir — raw GPS"
            )
        }
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
        // Gate on GPS quality AND movement: no swath blobs at red lights.
        val treating = isTreatingNow() && sample.gpsOk && sample.moving

        // Optional road snap (cosmetic; fail-open to raw GPS).
        var lat = sample.lat
        var lon = sample.lon
        val snapper = roadSnapper
        if (snapper != null && sample.gpsOk) {
            try {
                snapper.snap(lat, lon)?.let {
                    lat = it.lat
                    lon = it.lon
                }
            } catch (e: Exception) {
                Log.w(TAG, "road snap failed; using raw GPS", e)
            }
        }

        swathBuilder.setContext(
            SwathBuilder.Context(
                vehicleUid = selfUid(),
                callsign = cap.callsign,
                stormId = stormManager.activeStormId,
                operatorId = shift?.operatorId ?: ""
            )
        )
        swathBuilder.onSample(
            lat = lat,
            lon = lon,
            headingDeg = sample.headingDeg,
            timeMs = sample.timeMs,
            treating = treating,
            material = CapabilityRules.materialMode(
                cap, equipment.state.bladeDown, equipment.state.saltOn
            ),
            // Live effective width from the driver-selected preset.
            widthM = cap.widthFor(equipment.state.widthPreset),
            spreadMaterial = if (equipment.state.saltOn) equipment.state.material else null
        )

        facilityGeofences.update(
            sample.lat, sample.lon, sample.timeMs, shift?.operatorId ?: ""
        )

        // Forgot-to-toggle heuristics (prompts only — never auto-flips).
        val prompt = toggleSanity.onTick(
            ToggleSanity.Input(
                timeMs = sample.timeMs,
                moving = sample.moving && sample.speedMps > MOVING_SPEED_MPS,
                speedMps = sample.speedMps,
                treating = isTreatingNow(),
                bladeDown = equipment.state.bladeDown,
                stormActive = stormManager.activeStormId.isNotEmpty(),
                insideFacility = facilityGeofences.isInsideAny(),
                onShift = shiftLog.isOnShift
            )
        )
        if (prompt != null) {
            voiceAlerts.sanityPrompt(prompt.message)
            sanityPromptListener?.invoke(prompt)
        }
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

    /** Attach or detach the direction-split hook per the setting. */
    private fun refreshDirectionHook() {
        coverageOverlay.directionHook = if (!prefs.directionSplitEnabled) null
        else { segment: TreatSegment, nowMs: Long ->
            val cycleMinutes = CycleResolver.resolveForSegment(
                prefs.cycleTimes(), RoutePriority.DEFAULT, zoneManager.all(), segment
            )
            DirectionModel.directionStatus(
                segment,
                coverageStore.nearSegment(segment, DirectionModel.DEFAULT_CORRIDOR_WIDTH_M),
                nowMs,
                freshWithinMs = cycleMinutes * 60_000L
            )
        }
    }

    /** Voice "coverage overdue" when segments newly cross into RED. */
    private fun announceOverdue(nowMs: Long) {
        if (!shiftLog.isOnShift && !capabilityStore.load().canManageStorm) return
        val cycles = prefs.cycleTimes()
        val zones = zoneManager.all()
        val overdue = coverageStore.all().count { seg ->
            val cycle = CycleResolver.resolveForSegment(
                cycles, RoutePriority.DEFAULT, zones, seg
            )
            freshnessModel.classify(seg.endTimeMs, nowMs, cycle) == Freshness.RED
        }
        if (lastOverdueCount in 0 until overdue) {
            voiceAlerts.routeOverdue("$overdue treated stretches")
        }
        lastOverdueCount = overdue
    }

    companion object {
        private const val TAG = "PlowTakController"
        private const val RECOLOR_PERIOD_S = 30L
        private const val MISSION_COV_PERIOD_S = 5L * 60L
        private const val MPS_PER_MPH = 0.44704
        private const val MOVING_SPEED_MPS = 2.0
    }
}
