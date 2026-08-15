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
import com.atakmap.android.plowtak.coverage.GeoMath
import com.atakmap.android.plowtak.coverage.RoadSnapper
import com.atakmap.android.plowtak.coverage.SegmentIndex
import com.atakmap.android.plowtak.coverage.SwathBuilder
import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapItem
import com.atakmap.android.maps.Polyline
import com.atakmap.android.plowtak.demo.DemoFleetSimulator
import com.atakmap.android.plowtak.demo.DemoRoadWalker
import com.atakmap.android.plowtak.equipment.BluetoothEquipmentProvider
import com.atakmap.android.plowtak.equipment.ManualEquipmentProvider
import com.atakmap.android.plowtak.gis.LaneModel
import com.atakmap.android.plowtak.gis.RoadNetwork
import com.atakmap.android.plowtak.gis.RoadNetworkImporter
import com.atakmap.android.plowtak.gis.RoadPoint
import com.atakmap.android.plowtak.map.AlertOverlay
import com.atakmap.android.plowtak.map.CoverageOverlay
import com.atakmap.android.plowtak.map.FleetMarkerManager
import com.atakmap.android.plowtak.map.PlowStatusHud
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.CapabilityRules
import com.atakmap.android.plowtak.model.FacilityType
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.PaintStatus
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.RoutePriority
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.model.PlowVehicle
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
import com.atakmap.android.plowtak.ops.SnoozeStore
import com.atakmap.android.plowtak.ops.TaskingItem
import com.atakmap.android.plowtak.ops.TaskingListBuilder
import com.atakmap.android.plowtak.ops.StatusManager
import com.atakmap.android.plowtak.ops.StormSessionManager
import com.atakmap.android.plowtak.ops.TaskManager
import com.atakmap.android.plowtak.ops.ToggleSanity
import com.atakmap.android.plowtak.ops.ZoneManager
import com.atakmap.android.plowtak.prefs.PlowTakPreferences
import com.atakmap.android.plowtak.prefs.PlowTakSettingsBackup
import com.atakmap.android.plowtak.prefs.VehicleCapabilityStore
import com.atakmap.android.plowtak.report.ExportManager
import com.atakmap.android.plowtak.report.HazardReporter
import com.atakmap.android.plowtak.report.QuickPicHazardCapture
import com.atakmap.android.plowtak.report.MetricsCalculator
import com.atakmap.android.plowtak.report.StormExportData
import com.atakmap.android.plowtak.report.StormReplay
import com.atakmap.android.plowtak.service.PlowTakShiftService
import com.atakmap.android.plowtak.sync.MissionCoverageSync
import com.atakmap.android.plowtak.sync.MissionPullSink
import com.atakmap.android.plowtak.sync.OpsMissionCodec
import com.atakmap.android.plowtak.sync.StormConfigCodec
import com.atakmap.android.plowtak.tracking.SelfTracker
import com.atakmap.android.plowtak.ui.VoiceAlerts
import com.atakmap.android.maps.MapView
import com.atakmap.coremap.filesystem.FileSystemUtils
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
    val prefs = PlowTakPreferences(pluginContext).also {
        // Uninstall-proof mirror under atak/tools/plowtak/settings.json
        PlowTakSettingsBackup.restoreIfNeeded(pluginContext)
    }
    val capabilityStore = VehicleCapabilityStore(pluginContext).also { store ->
        store.addListener { PlowTakSettingsBackup.export(pluginContext) }
    }

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
    val snoozeStore = SnoozeStore(prefs)
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
    val plowStatusHud = PlowStatusHud(
        mapView = mapView,
        pluginContext = pluginContext,
        equipment = equipment,
        isOnShift = { shiftLog.isOnShift },
        hasSalt = { capabilityStore.load().hasSalt },
        isEnabled = { prefs.mapHudEnabled }
    )
    val hazardReporter = HazardReporter(cotQueue)
    val exportManager = ExportManager(pluginContext)
    val voiceAlerts = VoiceAlerts(mapView.context) { prefs.ttsEnabled }
    private var btProvider: BluetoothEquipmentProvider? = null
    val quickPicHazards = QuickPicHazardCapture { type, photoName ->
        reportHazard(type, photoName, attachQuickPic = true)
    }

    /** Synthetic storm fleet for demos / sales walkthroughs. */
    val demoFleet = DemoFleetSimulator(
        fleetManager = fleetManager,
        coverageStore = coverageStore,
        hazardReporter = hazardReporter,
        stormIdProvider = { stormManager.activeStormId },
        anchorProvider = {
            // GPS sample → self marker → map center. Demo must start without VNS
            // and without a warm GPS fix (common on indoor Fold demos).
            fun geoPair(p: com.atakmap.coremap.maps.coords.GeoPoint?): Pair<Double, Double>? {
                if (p == null || !p.isValid) return null
                return p.latitude to p.longitude
            }
            lastPositionSample?.let { it.lat to it.lon }
                ?: geoPair(mapView.selfMarker?.point)
                ?: geoPair(mapView.centerPoint?.get())
        },
        packDirResolver = {
            val vnsGh = try {
                File(FileSystemUtils.getItem("tools"), "VNS/GH")
            } catch (e: Exception) {
                null
            }
            DemoRoadWalker.resolvePackDir(prefs.roadSnapDir, vnsGh)
        },
        scheduler = { timers },
        onHazard = { hazard -> synchronized(hazardLog) { hazardLog[hazard.uid] = hazard } }
    )

    val missionCoverageSync = MissionCoverageSync(
        appContext = mapView.context,
        prefs = prefs,
        coverageStore = coverageStore,
        vehicleUid = { selfUid() },
        activeStorm = { stormManager.activeSession() },
        hazards = { synchronized(hazardLog) { hazardLog.values.toList() } },
        conditions = { synchronized(conditionLog) { conditionLog.values.toList() } },
        selfStatus = { buildSelfStatusVehicle() },
        demoVehicles = { demoFleet.snapshotVehicles() },
        routes = { routeAssignments.all() },
        zones = { zoneManager.all() },
        tasks = { taskManager.all() },
        snoozes = { snoozeStore.all() },
        cycleMinutesFor = { seg -> cycleMinutesForSegment(seg) },
        sink = object : MissionPullSink {
            override fun onStormConfigPulled(cfg: StormConfigCodec.StormConfig) {
                applyStormConfig(cfg)
            }

            override fun onUnitStatusPulled(vehicle: PlowVehicle) {
                if (vehicle.uid == selfUid()) return
                val existing = fleetManager.get(vehicle.uid)
                // Prefer CoT location when we already have a fresher position.
                if (existing != null && existing.lastUpdateMs >= vehicle.lastUpdateMs) {
                    fleetManager.update(
                        vehicle.copy(
                            lat = existing.lat,
                            lon = existing.lon,
                            lastUpdateMs = maxOf(existing.lastUpdateMs, vehicle.lastUpdateMs)
                        )
                    )
                } else {
                    fleetManager.update(vehicle)
                }
            }

            override fun onDemoFleetPulled(units: List<PlowVehicle>) {
                for (u in units) {
                    if (u.uid == selfUid()) continue
                    fleetManager.update(u)
                }
            }

            override fun onHazardsPulled(hazards: List<HazardEvent>) {
                for (h in hazards) {
                    if (h.reporterUid == selfUid()) continue
                    // Always remember the event; only paint when the UID is
                    // neither in our log nor already on the ATAK map (CoT may
                    // have beaten Data Sync and created the marker already).
                    val shouldPaint = synchronized(hazardLog) {
                        val known = hazardLog.containsKey(h.uid)
                        hazardLog[h.uid] = h
                        !known
                    }
                    if (!shouldPaint || mapHasUid(h.uid)) continue
                    hazardReporter.showLocal(h)
                }
            }

            override fun onConditionsPulled(conditions: List<RoadConditionReport>) {
                val ttl = conditionStaleMinutes()
                for (c in conditions) {
                    if (c.reporterUid == selfUid()) continue
                    val shouldPaint = synchronized(conditionLog) {
                        val known = conditionLog.containsKey(c.uid)
                        conditionLog[c.uid] = c
                        !known
                    }
                    if (!shouldPaint || mapHasUid(c.uid)) continue
                    cotPublisher.publishRoadCondition(c, ttl)
                }
            }

            override fun onConditionsPruned(fresh: List<RoadConditionReport>) {
                val dropped: List<RoadConditionReport>
                synchronized(conditionLog) {
                    val keep = fresh.map { it.uid }.toSet()
                    dropped = conditionLog.values.filter { it.uid !in keep }
                    conditionLog.clear()
                    for (c in fresh) conditionLog[c.uid] = c
                }
                for (c in dropped) {
                    cotPublisher.withdrawRoadCondition(c, mapView)
                }
            }

            override fun onOpsPulled(snapshot: OpsMissionCodec.Snapshot) {
                for (r in snapshot.routes) routeAssignments.onRemote(r)
                for (z in snapshot.zones) zoneManager.onRemote(z, removed = false)
                for (t in snapshot.tasks) taskManager.onRemote(t)
                if (snapshot.snoozes.isNotEmpty()) {
                    snoozeStore.mergeRemote(snapshot.snoozes)
                }
            }

            override fun onCoveragePulled(segments: List<TreatSegment>) {
                for (seg in segments) coverageStore.mergeRemote(seg)
            }
        }
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
        PlowTakSettingsBackup.export(pluginContext)

        coverageStore.setStorm(stormManager.activeStormId)
        syncFreshnessFromStorm()

        // Phase 2 overlay hooks: per-segment cycle time (priority + zones)
        // and direction-split (half-treated) rendering.
        coverageOverlay.cycleMinutesHook = { segment ->
            cycleMinutesForSegment(segment)
        }
        refreshDirectionHook()

        coverageOverlay.start()
        fleetMarkers.start()
        alertOverlay.start()
        plowStatusHud.start()
        plowStatusHud.refreshVisibility()
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
            plowStatusHud.refreshVisibility()
            if (shift != null) {
                PlowTakShiftService.start(
                    mapView.context,
                    "${capabilityStore.load().callsign} — ${statusManager.current.label}"
                )
            } else {
                swathBuilder.flush()
                toggleSanity.reset()
                plowStatusHud.setOverspeedCondition(false)
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
            syncFreshnessFromStorm()
            coverageOverlay.recolorAll(System.currentTimeMillis())
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
                syncFreshnessFromStorm()
                fleetManager.staleAfterMs = prefs.staleAfterS * 1000L
                taskManager.escalateAfterMs = prefs.taskEscalateMinutes * 60_000L
                refreshDirectionHook()
                coverageOverlay.recolorAll(now)
                fleetMarkers.refreshStaleness(now)
                // Only prune when the storm (or device default) sets a clear window.
                if (freshnessModel.retentionHours > 0) {
                    coverageStore.pruneExpired(freshnessModel, now)
                }
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
        // TAK Data Sync: replace this truck's live coverage GeoJSON every 1 min.
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
            demoFleet.stop()
        } catch (e: Exception) {
            Log.w(TAG, "demo fleet stop on dispose failed", e)
        }
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
        plowStatusHud.dispose()
        voiceAlerts.shutdown()
        PlowTakShiftService.stop(mapView.context)
        PlowTakSettingsBackup.export(pluginContext)
    }

    /**
     * Start/stop the showcase fleet on a background thread (GraphHopper pack
     * open can take a moment). [onDone] is posted to the map UI thread.
     */
    fun toggleDemoFleet(onDone: (DemoFleetSimulator.StartResult) -> Unit) {
        if (demoFleet.isRunning) {
            val msg = demoFleet.stop()
            mapView.post {
                onDone(DemoFleetSimulator.StartResult(true, msg, 0, false))
            }
            return
        }
        background.execute {
            val result = try {
                demoFleet.start(DemoFleetSimulator.DEFAULT_UNIT_COUNT)
            } catch (e: Exception) {
                Log.e(TAG, "demo fleet start failed", e)
                DemoFleetSimulator.StartResult(false, "Demo start failed: ${e.message}")
            }
            mapView.post { onDone(result) }
        }
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
        cotPublisher.publishRoadCondition(report, conditionStaleMinutes())
    }

    /** Effective road-condition TTL: joined storm, else the settings default. */
    fun conditionStaleMinutes(): Int {
        val fromStorm = stormManager.activeSession()?.roadConditionTtlMinutes
        return (fromStorm ?: prefs.roadConditionStaleMinutes)
            .coerceIn(15, 24 * 60)
    }

    /** Start a storm session, create its Data Sync mission, and broadcast it. */
    fun startStormSession(
        label: String = "",
        agency: String = "",
        missionName: String = "",
        channel: String = "",
        cycleMinutes: Int = prefs.cycleTimeMinutes,
        cycleP1Minutes: Int = prefs.cycleP1Minutes,
        cycleP2Minutes: Int = prefs.cycleP2Minutes,
        cycleP3Minutes: Int = prefs.cycleP3Minutes,
        coverageRetentionHours: Double = prefs.retentionHours,
        roadConditionTtlMinutes: Int = prefs.roadConditionStaleMinutes
    ): StormSession? {
        val cap = capabilityStore.load()
        val session = stormManager.startSession(
            startedBy = cap.callsign,
            nowMs = System.currentTimeMillis(),
            label = label,
            agency = agency,
            missionName = missionName,
            channel = channel,
            cycleMinutes = cycleMinutes,
            cycleP1Minutes = cycleP1Minutes,
            cycleP2Minutes = cycleP2Minutes,
            cycleP3Minutes = cycleP3Minutes,
            coverageRetentionHours = coverageRetentionHours,
            roadConditionTtlMinutes = roadConditionTtlMinutes
        )
        applyStormTimersToPrefs(session)
        syncFreshnessFromStorm()
        broadcastStorm(session)
        background.execute { missionCoverageSync.onStormStarted(session.id) }
        return session
    }

    /**
     * End the joined storm: broadcast the end so the fleet stops reporting.
     * The Data Sync mission and its data stay on the TAK server — deleting
     * a mission is an admin action on the server, never done from here.
     */
    fun endStormSession(): StormSession? {
        val session = stormManager.endSession(System.currentTimeMillis()) ?: return null
        broadcastStorm(session)
        background.execute { missionCoverageSync.onStormEnded() }
        return session
    }

    fun updateStormCycleMinutes(minutes: Int): StormSession? =
        updateStormCoverageSettings(cycleMinutes = minutes)

    /**
     * Update coverage timers on the joined storm, persist as device defaults
     * for the next storm, republish CoT + storm-config.json, and recolor.
     */
    fun updateStormCoverageSettings(
        cycleMinutes: Int? = null,
        cycleP1Minutes: Int? = null,
        cycleP2Minutes: Int? = null,
        cycleP3Minutes: Int? = null,
        coverageRetentionHours: Double? = null,
        roadConditionTtlMinutes: Int? = null
    ): StormSession? {
        val session = stormManager.updateCoverageSettings(
            cycleMinutes = cycleMinutes,
            cycleP1Minutes = cycleP1Minutes,
            cycleP2Minutes = cycleP2Minutes,
            cycleP3Minutes = cycleP3Minutes,
            coverageRetentionHours = coverageRetentionHours,
            roadConditionTtlMinutes = roadConditionTtlMinutes
        ) ?: return null
        applyStormTimersToPrefs(session)
        syncFreshnessFromStorm()
        coverageOverlay.recolorAll(System.currentTimeMillis())
        background.execute { missionCoverageSync.onStormStarted(session.id) }
        broadcastStorm(session)
        return session
    }

    /** Join a catalogued / announced storm for coverage + Data Sync reporting. */
    fun joinStormSession(session: StormSession): Boolean {
        val changed = stormManager.join(session)
        if (changed && session.isActive) {
            applyStormTimersToPrefs(session)
            syncFreshnessFromStorm()
            coverageOverlay.recolorAll(System.currentTimeMillis())
            background.execute { missionCoverageSync.onStormStarted(session.id) }
        }
        return changed
    }

    /** Stop reporting into a storm without ending it for other units. */
    fun leaveStormSession() {
        stormManager.leave()
    }

    /**
     * PlowTAK missions currently on the Data Sync server (blocking network —
     * runs [onResult] on the main thread).
     */
    fun listServerPlowTakMissions(onResult: (List<String>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        background.execute {
            val missions = missionCoverageSync.listPlowTakMissions()
            main.post { onResult(missions) }
        }
    }

    /**
     * Join a storm directly from its server mission: pull `storm-config.json`
     * to rebuild the session, or fall back to a minimal session keyed on the
     * mission name (config gets pulled and applied on the first sync tick).
     */
    fun joinStormFromServerMission(missionName: String, onDone: (StormSession?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        background.execute {
            val cfg = missionCoverageSync.fetchStormConfig(missionName)
            val session = if (cfg != null && cfg.id.isNotBlank()) {
                cfg.toSession().copy(
                    missionName = cfg.mission.ifBlank { missionName },
                    cycleMinutes = if (cfg.cycleMinutes > 0) cfg.cycleMinutes
                    else prefs.cycleTimeMinutes
                )
            } else {
                StormSession(
                    id = missionName,
                    startTimeMs = System.currentTimeMillis(),
                    label = missionName,
                    missionName = missionName,
                    cycleMinutes = prefs.cycleTimeMinutes,
                    cycleP1Minutes = prefs.cycleP1Minutes,
                    cycleP2Minutes = prefs.cycleP2Minutes,
                    cycleP3Minutes = prefs.cycleP3Minutes,
                    coverageRetentionHours = prefs.retentionHours,
                    roadConditionTtlMinutes = prefs.roadConditionStaleMinutes
                )
            }
            main.post {
                joinStormSession(session)
                applyStormTimersToPrefs(session)
                syncFreshnessFromStorm()
                onDone(session)
            }
        }
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

    /** Mine-first needs-treated list for the Tasks screen. */
    fun buildTaskingList(): List<TaskingItem> {
        val now = System.currentTimeMillis()
        snoozeStore.pruneExpired(now)
        val fix = selfLatLonOrNull()
        val hasFix = fix != null
        val selfLat = fix?.first ?: 0.0
        val selfLon = fix?.second ?: 0.0
        val cycle = stormManager.activeSession()?.cycleMinutes ?: prefs.cycleTimeMinutes
        val asg = routeAssignments.assignmentFor(selfUid())
        val routePolys = asg?.let { routePolylines(it) }.orEmpty()
        val myRoute = asg?.let { assignment ->
            val polys = routePolys
            val anchor = polys.firstOrNull()?.firstOrNull()?.let { it.lat to it.lon }
                ?: resolveRouteAnchor(assignment.routeId, assignment.source)
                ?: (selfLat to selfLon)
            val pct = if (polys.isNotEmpty()) {
                val index = SegmentIndex()
                for (seg in coverageStore.all()) index.add(seg)
                RouteCoverage.forPolylines(
                    polys, index,
                    stormManager.activeStormId.takeIf { it.isNotEmpty() }
                ).percent
            } else null
            TaskingListBuilder.RouteInput(
                routeId = assignment.routeId,
                lat = anchor.first,
                lon = anchor.second,
                coveragePercent = pct
            )
        }
        return TaskingListBuilder.build(
            TaskingListBuilder.Input(
                selfUid = selfUid(),
                selfLat = selfLat,
                selfLon = selfLon,
                nowMs = now,
                escalateAfterMs = prefs.taskEscalateMinutes * 60_000L,
                cycleMinutes = cycle,
                tasks = taskManager.all(),
                myRoute = myRoute,
                segments = coverageStore.all(),
                snoozes = snoozeStore.all(),
                classify = { seg ->
                    freshnessModel.classify(
                        seg.endTimeMs, now, cycleMinutesForSegment(seg)
                    )
                },
                cycleMinutesFor = { seg -> cycleMinutesForSegment(seg) },
                suppressOverdue = { seg ->
                    routePolys.isNotEmpty() && segmentNearRoute(seg, routePolys)
                },
                hasSelfFix = hasFix
            )
        )
    }

    /**
     * Defer a tasking row by [PlowTakPreferences.taskingSnoozeMinutes].
     * Returns minutes applied. Re-uploads ops snapshot when a storm is joined.
     */
    fun snoozeTaskingItem(itemId: String): Int {
        val mins = prefs.taskingSnoozeMinutes
        snoozeStore.bump(itemId, mins, System.currentTimeMillis())
        background.execute {
            stormManager.activeSession()?.id?.let { missionCoverageSync.onStormStarted(it) }
        }
        return mins
    }

    /** Pan/zoom the map to a tasking item. */
    fun zoomToTaskingItem(item: TaskingItem) {
        try {
            val point = com.atakmap.coremap.maps.coords.GeoPoint(item.lat, item.lon)
            val mapCtrl = mapView.mapController ?: return
            mapCtrl.panTo(point, true)
            try {
                mapCtrl.zoomTo(0.00008, true)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            Log.w(TAG, "zoomToTaskingItem failed", t)
        }
    }

    /** Best-effort self position: tracker → self marker → map center. */
    fun selfLatLonOrNull(): Pair<Double, Double>? {
        fun geoPair(p: com.atakmap.coremap.maps.coords.GeoPoint?): Pair<Double, Double>? {
            if (p == null || !p.isValid) return null
            return p.latitude to p.longitude
        }
        return lastPositionSample?.let { it.lat to it.lon }
            ?: geoPair(mapView.selfMarker?.point)
            ?: geoPair(mapView.centerPoint?.get())
    }

    /** Effective cycle minutes for a segment (priority from GIS + zones). */
    fun cycleMinutesForSegment(segment: TreatSegment): Int {
        val priority = loadRoadNetworkOrNull()?.priorityForSegment(segment)
            ?: RoutePriority.DEFAULT
        return CycleResolver.resolveForSegment(
            effectiveCycleTimes(), priority, zoneManager.all(), segment
        )
    }

    /**
     * Resolve GIS vs drawn source for a route id: prefer GIS when the
     * road network knows the route, else a matching ATAK map route.
     */
    fun resolveRouteSource(routeId: String): RouteAssignment.Source {
        val net = loadRoadNetworkOrNull()
        if (net != null && net.roadsForRoute(routeId).isNotEmpty()) {
            return RouteAssignment.Source.GIS
        }
        if (resolveDrawnRoutePoints(routeId) != null) {
            return RouteAssignment.Source.DRAWN
        }
        return RouteAssignment.Source.GIS
    }

    private fun routePolylines(asg: RouteAssignment): List<List<RoadPoint>> {
        return when (asg.source) {
            RouteAssignment.Source.GIS -> {
                val net = loadRoadNetworkOrNull() ?: return emptyList()
                net.roadsForRoute(asg.routeId).map { it.points }.filter { it.size >= 2 }
            }
            RouteAssignment.Source.DRAWN -> {
                listOfNotNull(resolveDrawnRoutePoints(asg.routeId))
            }
        }
    }

    private fun segmentNearRoute(
        segment: TreatSegment,
        polylines: List<List<RoadPoint>>,
        maxDistM: Double = 40.0
    ): Boolean {
        val mid = segment.points.getOrNull(segment.points.size / 2) ?: return false
        for (line in polylines) {
            for (i in 0 until line.size - 1) {
                val a = line[i]
                val b = line[i + 1]
                val d = GeoMath.closestPointOnSegment(
                    mid.lat, mid.lon, a.lat, a.lon, b.lat, b.lon
                )[2]
                if (d <= maxDistM) return true
            }
        }
        return false
    }

    /** First point of a GIS or drawn route. */
    private fun resolveRouteAnchor(
        routeId: String,
        source: RouteAssignment.Source,
        network: RoadNetwork? = loadRoadNetworkOrNull()
    ): Pair<Double, Double>? {
        when (source) {
            RouteAssignment.Source.GIS -> {
                val net = network ?: return null
                val road = net.roadsForRoute(routeId).firstOrNull() ?: return null
                val p = road.points.firstOrNull() ?: return null
                return p.lat to p.lon
            }
            RouteAssignment.Source.DRAWN -> {
                val pts = resolveDrawnRoutePoints(routeId) ?: return null
                val p = pts.firstOrNull() ?: return null
                return p.lat to p.lon
            }
        }
    }

    /** Points for an ATAK-drawn route matched by UID or title. */
    private fun resolveDrawnRoutePoints(routeId: String): List<RoadPoint>? {
        if (routeId.isBlank()) return null
        return try {
            val root = mapView.rootGroup ?: return null
            extractPolylinePoints(root.deepFindUID(routeId))
                ?: findMapItemByTitle(root, routeId)?.let { extractPolylinePoints(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "resolveDrawnRoutePoints failed for $routeId", t)
            null
        }
    }

    private fun findMapItemByTitle(root: MapGroup, title: String): MapItem? {
        val found = ArrayList<MapItem>()
        collectMapItemsSafe(root, found)
        val needle = title.trim()
        return found.firstOrNull { item ->
            item.uid.equals(needle, ignoreCase = true) ||
                item.title.orEmpty().equals(needle, ignoreCase = true)
        }
    }

    /** Best-effort walk of ATAK MapGroup hierarchy (API varies by CIV build). */
    private fun collectMapItemsSafe(group: MapGroup, out: MutableList<MapItem>) {
        try {
            val getItems = group.javaClass.methods.firstOrNull {
                it.name == "getItems" && it.parameterCount == 0
            }
            when (val items = getItems?.invoke(group)) {
                is Iterator<*> -> {
                    while (items.hasNext()) {
                        (items.next() as? MapItem)?.let { out.add(it) }
                    }
                }
                is Collection<*> -> items.filterIsInstance<MapItem>().forEach { out.add(it) }
            }
        } catch (_: Throwable) {
        }
        try {
            val childMethod = group.javaClass.methods.firstOrNull {
                (it.name == "getChildGroups" || it.name == "getGroups") && it.parameterCount == 0
            }
            when (val kids = childMethod?.invoke(group)) {
                is Iterator<*> -> {
                    while (kids.hasNext()) {
                        (kids.next() as? MapGroup)?.let { collectMapItemsSafe(it, out) }
                    }
                }
                is Collection<*> -> kids.filterIsInstance<MapGroup>()
                    .forEach { collectMapItemsSafe(it, out) }
            }
        } catch (_: Throwable) {
        }
    }

    private fun extractPolylinePoints(item: MapItem?): List<RoadPoint>? {
        if (item == null) return null
        try {
            if (item is Polyline) {
                val pts = item.points ?: return null
                if (pts.size < 2) return null
                return pts.map { RoadPoint(it.latitude, it.longitude) }
            }
            val method = item.javaClass.methods.firstOrNull {
                it.name == "getPoints" && it.parameterCount == 0
            } ?: return null
            val pts = method.invoke(item) as? Array<*> ?: return null
            if (pts.size < 2) return null
            val out = ArrayList<RoadPoint>(pts.size)
            for (p in pts) {
                if (p == null) continue
                val lat = p.javaClass.getMethod("getLatitude").invoke(p) as Double
                val lon = p.javaClass.getMethod("getLongitude").invoke(p) as Double
                out.add(RoadPoint(lat, lon))
            }
            return out.takeIf { it.size >= 2 }
        } catch (_: Throwable) {
            return null
        }
    }

    @Volatile
    private var roadNetworkCache: Pair<String, RoadNetwork>? = null

    private fun loadRoadNetworkOrNull(): RoadNetwork? {
        val path = prefs.roadNetworkFile
        if (path.isBlank()) {
            roadNetworkCache = null
            return null
        }
        roadNetworkCache?.let { (cachedPath, net) ->
            if (cachedPath == path) return net
        }
        return try {
            val file = File(path)
            if (!file.isFile) return null
            val net = RoadNetworkImporter.import(file.readText()).network
            roadNetworkCache = path to net
            net
        } catch (_: Throwable) {
            null
        }
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
            coverageStore.all(), fleetManager.all(), effectiveCycleTimes(),
            zoneManager.all(), System.currentTimeMillis(),
            priorityFor = { seg ->
                loadRoadNetworkOrNull()?.priorityForSegment(seg) ?: RoutePriority.DEFAULT
            }
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
        roadNetworkCache = null
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

    /**
     * Apply a provisioning / data-package profile: device defaults, optional
     * capability, facilities, zones. When a storm is joined, coverage timers
     * are written onto that storm and republished (CoT + storm-config.json).
     */
    fun applyProvisioning(profile: ProvisioningProfile) {
        profile.capability?.let { capabilityStore.save(it) }
        profile.cycleTimes?.let { c ->
            prefs.cycleTimeMinutes = c.defaultMinutes
            prefs.cycleP1Minutes = c.p1Minutes
            prefs.cycleP2Minutes = c.p2Minutes
            prefs.cycleP3Minutes = c.p3Minutes
        }
        profile.coverageRetentionHours?.let { prefs.retentionHours = it }
        profile.roadConditionTtlMinutes?.let { prefs.roadConditionStaleMinutes = it }
        for (f in profile.facilities) {
            facilityGeofences.add(f)
        }
        for (z in profile.zones) {
            zoneManager.put(z)
        }
        if (stormManager.activeSession() != null &&
            (profile.cycleTimes != null || profile.coverageRetentionHours != null ||
                profile.roadConditionTtlMinutes != null)
        ) {
            val c = profile.cycleTimes
            updateStormCoverageSettings(
                cycleMinutes = c?.defaultMinutes,
                cycleP1Minutes = c?.p1Minutes,
                cycleP2Minutes = c?.p2Minutes,
                cycleP3Minutes = c?.p3Minutes,
                coverageRetentionHours = profile.coverageRetentionHours,
                roadConditionTtlMinutes = profile.roadConditionTtlMinutes
            )
        } else {
            syncFreshnessFromStorm()
            coverageOverlay.recolorAll(System.currentTimeMillis())
        }
        PlowTakSettingsBackup.export(pluginContext)
    }

    /** Build a provisioning profile from current device defaults + storm timers. */
    fun exportProvisioningProfile(
        name: String = "",
        agency: String = "",
        includeCapability: Boolean = false
    ): ProvisioningProfile {
        val storm = stormManager.activeSession()
        return ProvisioningProfile(
            name = name,
            agency = agency,
            createdMs = System.currentTimeMillis(),
            capability = if (includeCapability) capabilityStore.load() else null,
            cycleTimes = storm?.cycleTimes() ?: prefs.cycleTimes(),
            coverageRetentionHours = storm?.coverageRetentionHours ?: prefs.retentionHours,
            roadConditionTtlMinutes = storm?.roadConditionTtlMinutes
                ?: prefs.roadConditionStaleMinutes,
            facilities = facilityGeofences.all(),
            zones = zoneManager.all()
        )
    }

    /** Write [exportProvisioningProfile] JSON next to settings backup; return path. */
    fun exportProvisioningFile(
        name: String = "PlowTAK provisioning",
        agency: String = "",
        includeCapability: Boolean = false
    ): String? {
        return try {
            val profile = exportProvisioningProfile(name, agency, includeCapability)
            val dir = File(FileSystemUtils.getItem("tools"), "plowtak")
            dir.mkdirs()
            val file = File(dir, ProvisioningCodec.FILE_EXTENSION)
            file.writeText(ProvisioningCodec.encode(profile))
            file.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "exportProvisioningFile failed", t)
            null
        }
    }

    /** Load and apply a provisioning profile from disk. */
    fun importProvisioningFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.isFile) return false
            val profile = ProvisioningCodec.decode(file.readText()) ?: return false
            applyProvisioning(profile)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "importProvisioningFile failed", t)
            false
        }
    }

    /** List `*.ipprov.json` files under tools/plowtak for the import picker. */
    fun listProvisioningFiles(): List<File> {
        return try {
            val dir = File(FileSystemUtils.getItem("tools"), "plowtak")
            if (!dir.isDirectory) return emptyList()
            dir.listFiles { f ->
                f.isFile && f.name.endsWith(ProvisioningCodec.FILE_EXTENSION, ignoreCase = true)
            }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun broadcastStorm(session: StormSession) {
        val pos = lastPositionSample
        cotPublisher.publishStormSession(session, pos?.lat ?: 0.0, pos?.lon ?: 0.0)
    }

    /** Status payload for Data Sync (blade / spread / mode — not CoT). */
    private fun buildSelfStatusVehicle(): PlowVehicle? {
        val sample = lastPositionSample ?: return null
        val cap = capabilityStore.load()
        val eq = equipment.state
        return PlowVehicle(
            uid = selfUid(),
            callsign = cap.callsign.ifEmpty { selfUid() },
            type = cap.type,
            status = statusManager.current,
            lat = sample.lat,
            lon = sample.lon,
            headingDeg = sample.headingDeg,
            lastUpdateMs = sample.timeMs,
            hasBlade = cap.hasBlade,
            hasSalt = cap.hasSalt,
            bladeDown = eq.bladeDown,
            saltOn = eq.saltOn,
            stormId = stormManager.activeStormId,
            operatorId = shiftLog.currentShift?.operatorId ?: "",
            operatorName = shiftLog.currentShift?.operatorName ?: "",
            reloadCount = facilityGeofences.reloadCountSince(
                stormManager.current?.startTimeMs ?: 0L
            )
        )
    }

    // ------------------------------------------------------------ wiring

    private val recordingListener = SelfTracker.Listener { sample ->
        lastPositionSample = sample

        val cap = capabilityStore.load()
        val shift = shiftLog.currentShift
        val paint = evaluatePaint(sample.gpsOk, sample.moving)
        // Gate on storm + shift + equipment + GPS quality + movement.
        val treating = paint.anyPainting

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

        val eq = equipment.state
        val widthPreset = eq.effectiveWidthPreset()
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
                cap, paint.bladePainting, paint.spreadPainting
            ),
            widthM = cap.widthFor(widthPreset),
            spreadMaterial = if (paint.spreadPainting) eq.material else null
        )

        facilityGeofences.update(
            sample.lat, sample.lon, sample.timeMs, shift?.operatorId ?: ""
        )

        // Forgot-to-toggle heuristics (prompts only — never auto-flips).
        val overspeedNow = equipment.state.bladeDown &&
            sample.speedMps > prefs.maxPlowSpeedMph * MPS_PER_MPH
        plowStatusHud.setOverspeedCondition(overspeedNow && shiftLog.isOnShift)

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
            if (prompt.type == ToggleSanity.PromptType.CONFIRM_SPEED) {
                // Audible + map HUD flash only — no dialog popup.
                plowStatusHud.flashAlert()
            } else {
                sanityPromptListener?.invoke(prompt)
            }
        }
    }

    /**
     * Legacy "treating" flag for status/voice: true when either blade or
     * spread would paint under current storm/shift/equipment (motion ignored).
     */
    fun isTreatingNow(): Boolean {
        val p = evaluatePaint(gpsOk = true, moving = true)
        return p.anyPainting
    }

    /** Operator-facing paint gate summary for the ops status line. */
    fun currentPaintStatus(): PaintStatus {
        val sample = lastPositionSample
        return evaluatePaint(sample?.gpsOk == true, sample?.moving == true)
    }

    private fun evaluatePaint(gpsOk: Boolean, moving: Boolean): PaintStatus {
        if (stormManager.activeStormId.isEmpty()) {
            return PaintStatus.idle("Not painting: no storm selected")
        }
        if (!shiftLog.isOnShift) {
            return PaintStatus.idle("Not painting: off shift")
        }
        val cap = capabilityStore.load()
        if (!CapabilityRules.paintsCoverage(cap.type) || !cap.canTreat) {
            return PaintStatus.idle("Not painting: no treat equipment")
        }
        val eq = equipment.state
        val bladeEq = CapabilityRules.bladeChannelActive(cap, eq.bladeDown)
        val spreadEq = CapabilityRules.spreadChannelActive(cap, eq.spreadingOn)
        if (!bladeEq && !spreadEq) {
            return PaintStatus.idle("Not painting: blade up / spread off")
        }
        if (!gpsOk) return PaintStatus.idle("Not painting: GPS poor")
        if (!moving) return PaintStatus.idle("Not painting: not moving")
        return PaintStatus(
            bladePainting = bladeEq,
            spreadPainting = spreadEq,
            reason = when {
                bladeEq && spreadEq -> "Painting plow + spread"
                bladeEq -> "Painting plow swath"
                else -> "Painting spread track"
            }
        )
    }

    private fun refreshTreatingState() {
        statusManager.updateTreating(isTreatingNow())
    }

    /** True when ATAK already has a map item for [uid] (CoT beat Data Sync). */
    private fun mapHasUid(uid: String): Boolean {
        if (uid.isBlank()) return false
        return try {
            mapView.rootGroup.deepFindUID(uid) != null
        } catch (_: Throwable) {
            false
        }
    }

    /** Attach or detach the direction-split hook per the setting. */
    private fun refreshDirectionHook() {
        coverageOverlay.directionHook = if (!prefs.directionSplitEnabled) null
        else { segment: TreatSegment, nowMs: Long ->
            val cycleMinutes = cycleMinutesForSegment(segment)
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
        val overdue = coverageStore.all().count { seg ->
            val cycle = cycleMinutesForSegment(seg)
            freshnessModel.classify(seg.endTimeMs, nowMs, cycle) == Freshness.RED
        }
        if (lastOverdueCount in 0 until overdue) {
            voiceAlerts.routeOverdue("$overdue treated stretches")
        }
        lastOverdueCount = overdue
    }

    /** Joined storm cycle model, else device defaults for the next storm. */
    fun effectiveCycleTimes(): com.atakmap.android.plowtak.coverage.CycleTimes =
        stormManager.activeSession()?.cycleTimes() ?: prefs.cycleTimes()

    /** Apply freshness model from the joined storm (or device defaults). */
    fun syncFreshnessFromStorm() {
        val storm = stormManager.activeSession()
        if (storm != null) {
            freshnessModel.cycleTimeMinutes = storm.cycleMinutes
            freshnessModel.retentionHours = storm.coverageRetentionHours
        } else {
            freshnessModel.cycleTimeMinutes = prefs.cycleTimeMinutes
            freshnessModel.retentionHours = prefs.retentionHours
        }
    }

    /** Mirror storm timers into device prefs (defaults for the next storm). */
    fun applyStormTimersToPrefs(session: StormSession) {
        prefs.cycleTimeMinutes = session.cycleMinutes
        prefs.cycleP1Minutes = session.cycleP1Minutes
        prefs.cycleP2Minutes = session.cycleP2Minutes
        prefs.cycleP3Minutes = session.cycleP3Minutes
        prefs.retentionHours = session.coverageRetentionHours
        prefs.roadConditionStaleMinutes = session.roadConditionTtlMinutes
    }

    /** Apply a pulled storm-config.json onto the joined storm + local defaults. */
    fun applyStormConfig(cfg: StormConfigCodec.StormConfig) {
        if (cfg.cycleMinutes > 0 || cfg.roadConditionTtlMinutes > 0 ||
            cfg.coverageRetentionHours >= 0
        ) {
            stormManager.updateCoverageSettings(
                cycleMinutes = cfg.cycleMinutes.takeIf { it > 0 },
                cycleP1Minutes = cfg.cycleP1Minutes,
                cycleP2Minutes = cfg.cycleP2Minutes,
                cycleP3Minutes = cfg.cycleP3Minutes,
                coverageRetentionHours = cfg.coverageRetentionHours,
                roadConditionTtlMinutes = cfg.roadConditionTtlMinutes.takeIf { it > 0 }
            )
        }
        stormManager.activeSession()?.let { applyStormTimersToPrefs(it) }
            ?: run {
                if (cfg.cycleMinutes > 0) prefs.cycleTimeMinutes = cfg.cycleMinutes
                prefs.cycleP1Minutes = cfg.cycleP1Minutes
                prefs.cycleP2Minutes = cfg.cycleP2Minutes
                prefs.cycleP3Minutes = cfg.cycleP3Minutes
                prefs.retentionHours = cfg.coverageRetentionHours
                if (cfg.roadConditionTtlMinutes > 0) {
                    prefs.roadConditionStaleMinutes = cfg.roadConditionTtlMinutes
                }
            }
        syncFreshnessFromStorm()
        coverageOverlay.recolorAll(System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "PlowTakController"
        private const val RECOLOR_PERIOD_S = 30L
        private const val MISSION_COV_PERIOD_S = 60L
        private const val MPS_PER_MPH = 0.44704
        private const val MOVING_SPEED_MPS = 2.0
    }
}
