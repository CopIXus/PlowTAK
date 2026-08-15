package com.atakmap.android.plowtak.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.atakmap.android.channels.net.ServerGroupsClient
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.android.http.rest.ServerGroup
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.sync.MissionCoverageCodec
import com.atakmap.android.plowtak.sync.TakServerTargets
import com.atakmap.comms.TAKServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared dialogs for starting / joining storms and picking the Data Sync server.
 *
 * Start-storm wizard: server → channel → optional 2h join-or-new → name
 * (auto PlowTAK Storm yyyy.MMdd.HHmm; Data Sync mission = stamp).
 */
object StormServerDialogs {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun showStormMenu(
        controller: PlowTakController,
        hostContext: Context,
        onChanged: (() -> Unit)? = null
    ) {
        val items = arrayOf(
            "Start storm…",
            "Join / pick storm…",
            "Data Sync server…",
            "Storm coverage settings…",
            "Import provisioning…",
            "Export provisioning…",
            "End storm / delete Data Sync…"
        )
        AlertDialog.Builder(hostContext)
            .setTitle("Storm")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showStartStormWizard(controller, hostContext, onChanged)
                    1 -> showJoinStormDialog(controller, hostContext, onChanged)
                    2 -> showDataSyncServerPicker(controller, hostContext, onChanged)
                    3 -> showCoverageSettingsDialog(controller, hostContext, onChanged)
                    4 -> showImportProvisioningDialog(controller, hostContext, onChanged)
                    5 -> showExportProvisioningDialog(controller, hostContext)
                    6 -> {
                        showEndStormDialog(controller, hostContext)
                        onChanged?.invoke()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Entry used by Settings; same wizard as the driver Storm button. */
    fun showStartStormDialog(controller: PlowTakController, hostContext: Context) {
        showStartStormWizard(controller, hostContext, null)
    }

    fun showStartStormWizard(
        controller: PlowTakController,
        hostContext: Context,
        onChanged: (() -> Unit)? = null
    ) {
        pickServer(controller, hostContext) { server ->
            if (server == null) return@pickServer
            controller.prefs.dataSyncServerConnectString = server.connectString
            pickChannel(hostContext, server) { channel ->
                if (channel == null) return@pickChannel
                maybeJoinRecentOrStartNew(controller, hostContext, channel, onChanged)
            }
        }
    }

    private fun pickServer(
        controller: PlowTakController,
        hostContext: Context,
        after: (TakServerTargets.Target?) -> Unit
    ) {
        val servers = TakServerTargets.listServers()
        if (servers.isEmpty()) {
            Toast.makeText(
                hostContext,
                "No TAK servers configured in ATAK Networks",
                Toast.LENGTH_LONG
            ).show()
            after(null)
            return
        }
        val preferred = controller.prefs.dataSyncServerConnectString
        val labels = servers.map { s ->
            val state = if (s.connected) "connected" else "offline"
            val mark = when {
                s.connectString == preferred -> "★ "
                preferred.isEmpty() && s.connected &&
                    s.connectString == servers.firstOrNull { it.connected }?.connectString -> "★ "
                else -> ""
            }
            "$mark${s.label}  [$state]"
        }.toTypedArray()
        AlertDialog.Builder(hostContext)
            .setTitle("1/3 — Data Sync server")
            .setItems(labels) { _, which ->
                val chosen = servers[which]
                if (!chosen.connected) {
                    Toast.makeText(
                        hostContext,
                        "Server offline — connect it in ATAK Networks first",
                        Toast.LENGTH_LONG
                    ).show()
                    after(null)
                } else {
                    after(chosen)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> after(null) }
            .show()
    }

    private fun pickChannel(
        hostContext: Context,
        server: TakServerTargets.Target,
        after: (String?) -> Unit
    ) {
        val busy = ProgressDialog(hostContext).apply {
            setMessage("Loading channels you can access…")
            setCancelable(true)
            show()
        }
        var cancelled = false
        busy.setOnCancelListener {
            cancelled = true
            after(null)
        }
        loadChannels(hostContext, server.connectString) { groups ->
            if (cancelled) return@loadChannels
            try {
                busy.dismiss()
            } catch (_: Throwable) {
            }
            showChannelPicker(hostContext, groups, after)
        }
    }

    /**
     * Active channels (server groups) this client cert can use on [connectString].
     * Calls [onResult] once on the main thread; falls back to groups cached on
     * the TAKServer object and finally to an 8 s timeout.
     */
    fun loadChannels(
        hostContext: Context,
        connectString: String,
        onResult: (List<String>) -> Unit
    ) {
        val finished = AtomicBoolean(false)
        fun finish(groups: List<String>) {
            if (!finished.compareAndSet(false, true)) return
            onResult(groups)
        }
        try {
            ServerGroupsClient.getInstance().getAllGroups(
                hostContext,
                connectString,
                true,
                ServerGroupsClient.ServerGroupsCallback { _, groups ->
                    mainHandler.post {
                        val names = accessibleChannelNames(groups)
                        if (names.isNotEmpty()) {
                            finish(names)
                        } else {
                            finish(cachedServerGroups(connectString))
                        }
                    }
                }
            )
            // Safety timeout — ATAK callback can stall if API port is closed.
            mainHandler.postDelayed({
                if (!finished.get()) {
                    finish(cachedServerGroups(connectString))
                }
            }, 8_000L)
        } catch (t: Throwable) {
            finish(cachedServerGroups(connectString))
        }
    }

    private fun showChannelPicker(
        hostContext: Context,
        groups: List<String>,
        after: (String?) -> Unit
    ) {
        val options = ArrayList<String>()
        if (groups.isNotEmpty()) {
            options.addAll(groups)
        } else {
            options.add("__ANON__")
            Toast.makeText(
                hostContext,
                "No active channels returned — using __ANON__ or enter one manually",
                Toast.LENGTH_LONG
            ).show()
        }
        options.add("Enter channel manually…")

        AlertDialog.Builder(hostContext)
            .setTitle("2/3 — Channel that owns the storm")
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = options[which]
                if (chosen.startsWith("Enter channel")) {
                    promptManualChannel(hostContext, after)
                } else {
                    after(chosen)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> after(null) }
            .show()
    }

    /**
     * Active groups this enrollment can use. Prefer IN (publish) direction;
     * fall back to any active group name; finally all named groups.
     */
    private fun accessibleChannelNames(groups: List<ServerGroup>?): List<String> {
        if (groups.isNullOrEmpty()) return emptyList()
        val valid = groups.filter { it.isValid && !it.name.isNullOrBlank() }
        val activeIn = valid.filter {
            it.isActive && it.direction.equals("IN", ignoreCase = true)
        }.map { it.name.trim() }
        if (activeIn.isNotEmpty()) return activeIn.distinct().sorted()

        val activeAny = valid.filter { it.isActive }.map { it.name.trim() }
        if (activeAny.isNotEmpty()) return activeAny.distinct().sorted()

        return valid.map { it.name.trim() }.distinct().sorted()
    }

    private fun promptManualChannel(hostContext: Context, after: (String?) -> Unit) {
        val input = EditText(hostContext).apply {
            hint = "Channel name (e.g. __ANON__)"
            setText("__ANON__")
            setSingleLine()
            setSelection(text.length)
        }
        val pad = dp(hostContext, 16)
        val wrap = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(hostContext)
            .setTitle("CoT channel")
            .setView(wrap)
            .setPositiveButton("Next") { _, _ ->
                val ch = input.text.toString().trim()
                if (ch.isEmpty()) {
                    Toast.makeText(hostContext, "Channel required", Toast.LENGTH_SHORT).show()
                    after(null)
                } else {
                    after(ch)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> after(null) }
            .show()
    }

    /**
     * If an active storm was heard or exists on the server within 2 hours,
     * offer Join vs Start new; otherwise go straight to naming.
     */
    private fun maybeJoinRecentOrStartNew(
        controller: PlowTakController,
        hostContext: Context,
        channel: String,
        onChanged: (() -> Unit)?
    ) {
        val now = System.currentTimeMillis()
        val windowMs = 2L * 60L * 60L * 1000L
        val heard = controller.stormManager.knownStorms()
            .filter { it.isActive && now - it.startTimeMs in 0..windowMs }
            .sortedByDescending { it.startTimeMs }

        fun continueWithCandidates(extra: List<com.atakmap.android.plowtak.model.StormSession>) {
            val candidates = (heard + extra)
                .distinctBy { it.id }
                .sortedByDescending { it.startTimeMs }
            if (candidates.isEmpty()) {
                nameAndStartStorm(controller, hostContext, channel, onChanged)
                return
            }
            val best = candidates.first()
            val mission = MissionCoverageCodec.effectiveMissionName(best.id, best.missionName)
            AlertDialog.Builder(hostContext)
                .setTitle("Storm already active")
                .setMessage(
                    "A storm already exists (${best.displayName()} / mission $mission) " +
                        "within the last 2 hours. Join it, or start a new storm?"
                )
                .setPositiveButton("Join") { _, _ ->
                    if (best.missionName.isNotBlank() &&
                        best.missionName.matches(Regex("""\d{4}\.\d{4}\.\d{4}"""))
                    ) {
                        controller.joinStormFromServerMission(best.missionName) { session ->
                            Toast.makeText(
                                hostContext,
                                if (session != null) "Joined ${session.displayName()}"
                                else "Could not join ${best.displayName()}",
                                Toast.LENGTH_SHORT
                            ).show()
                            onChanged?.invoke()
                        }
                    } else {
                        controller.joinStormSession(best)
                        Toast.makeText(
                            hostContext,
                            "Joined ${best.displayName()}",
                            Toast.LENGTH_SHORT
                        ).show()
                        onChanged?.invoke()
                    }
                }
                .setNeutralButton("Start new") { _, _ ->
                    nameAndStartStorm(controller, hostContext, channel, onChanged)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Best-effort server scan; fail open to heard-only / create.
        controller.listServerPlowTakMissions { missions ->
            val fromServer = missions.mapNotNull { missionName ->
                val start = parseStampToMs(missionName) ?: return@mapNotNull null
                if (now - start !in 0..windowMs) return@mapNotNull null
                com.atakmap.android.plowtak.model.StormSession(
                    id = missionName,
                    startTimeMs = start,
                    label = "PlowTAK Storm $missionName",
                    missionName = missionName,
                    channel = channel
                )
            }
            continueWithCandidates(fromServer)
        }
    }

    /** Parse yyyy.MMdd.HHmm mission stamp to epoch ms; null if not that shape. */
    private fun parseStampToMs(stamp: String): Long? {
        return try {
            val fmt = SimpleDateFormat("yyyy.MMdd.HHmm", Locale.US)
            fmt.isLenient = false
            fmt.parse(stamp.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun nameAndStartStorm(
        controller: PlowTakController,
        hostContext: Context,
        channel: String,
        onChanged: (() -> Unit)?
    ) {
        val pad = dp(hostContext, 16)
        val now = System.currentTimeMillis()
        val defaultName = defaultStormName(now)
        val stamp = stormNameStamp(now)
        val name = EditText(hostContext).apply {
            setText(defaultName)
            setSingleLine()
            setSelection(text.length)
        }
        val agency = EditText(hostContext).apply {
            hint = "Agency (optional, e.g. VDOT)"
            setSingleLine()
        }
        val green = EditText(hostContext).apply {
            hint = "Green until (min) — track stays green after plow"
            setText(controller.prefs.greenUntilMinutes.toString())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val yellow = EditText(hostContext).apply {
            hint = "Yellow until (min) — aging band before red"
            setText(controller.prefs.yellowUntilMinutes.toString())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val red = EditText(hostContext).apply {
            hint = "Red after (min) — needs plow again"
            setText(controller.prefs.cycleTimeMinutes.toString())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val retain = EditText(hostContext).apply {
            hint = "Remove after (hours) — 0 = keep red forever"
            setText(formatRetention(controller.prefs.retentionHours))
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val condTtl = EditText(hostContext).apply {
            hint = "Road condition report TTL (minutes)"
            setText(controller.prefs.roadConditionStaleMinutes.toString())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val column = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(agency)
            addView(green)
            addView(yellow)
            addView(red)
            addView(retain)
            addView(condTtl)
        }
        AlertDialog.Builder(hostContext)
            .setTitle("Name storm")
            .setMessage(
                "Creates Data Sync mission \"$stamp\" on channel \"$channel\".\n" +
                    "Plow-track timers are shared with every joined device.\n\n" +
                    currentServerSummary(controller)
            )
            .setView(column)
            .setPositiveButton("Start") { _, _ ->
                val label = name.text.toString().trim().ifEmpty { defaultName }
                val g = green.text.toString().toIntOrNull()
                    ?: controller.prefs.greenUntilMinutes
                val y = yellow.text.toString().toIntOrNull()
                    ?: controller.prefs.yellowUntilMinutes
                val mins = red.text.toString().toIntOrNull()
                    ?: controller.prefs.cycleTimeMinutes
                val retainH = retain.text.toString().toDoubleOrNull()
                    ?: controller.prefs.retentionHours
                val ttl = condTtl.text.toString().toIntOrNull()
                    ?: controller.prefs.roadConditionStaleMinutes
                val session = controller.startStormSession(
                    label = label,
                    agency = agency.text.toString(),
                    missionName = stamp,
                    channel = channel,
                    greenUntilMinutes = g,
                    yellowUntilMinutes = y,
                    cycleMinutes = mins,
                    coverageRetentionHours = retainH,
                    roadConditionTtlMinutes = ttl
                )
                if (session == null) {
                    Toast.makeText(hostContext, "Cannot start storm", Toast.LENGTH_SHORT).show()
                } else {
                    val mission = MissionCoverageCodec.effectiveMissionName(
                        session.id, session.missionName
                    )
                    Toast.makeText(
                        hostContext,
                        "Storm started: ${session.displayName()}\n" +
                            "Data Sync mission: $mission\nChannel: $channel",
                        Toast.LENGTH_LONG
                    ).show()
                    onChanged?.invoke()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showEndStormDialog(controller: PlowTakController, hostContext: Context) {
        val active = controller.stormManager.activeSession()
        if (active == null) {
            Toast.makeText(hostContext, "No joined storm to end", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(hostContext)
            .setTitle("End storm")
            .setMessage(
                "End \"${active.displayName()}\" for the whole fleet?\n\n" +
                    "All trucks stop uploading. The Data Sync mission and its " +
                    "data stay on the TAK server; only a server admin can " +
                    "delete it."
            )
            .setPositiveButton("End storm") { _, _ ->
                controller.endStormSession()
                Toast.makeText(
                    hostContext,
                    "Storm ended; mission kept on server",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showJoinStormDialog(
        controller: PlowTakController,
        hostContext: Context,
        onChanged: (() -> Unit)? = null
    ) {
        val busy = ProgressDialog(hostContext).apply {
            setMessage("Checking server for PlowTAK Data Sync missions…")
            setCancelable(true)
            show()
        }
        controller.listServerPlowTakMissions { serverMissions ->
            try {
                busy.dismiss()
            } catch (_: Throwable) {
            }
            showJoinStormList(controller, hostContext, serverMissions, onChanged)
        }
    }

    private fun showJoinStormList(
        controller: PlowTakController,
        hostContext: Context,
        serverMissions: List<String>,
        onChanged: (() -> Unit)?
    ) {
        val storms = controller.stormManager.knownStorms()
        val joinedId = controller.stormManager.activeStormId
        // Missions already represented by a heard storm are not repeated.
        val heardMissions = storms.map {
            MissionCoverageCodec.effectiveMissionName(it.id, it.missionName)
        }.toSet()
        val serverOnly = serverMissions.filter { it !in heardMissions }

        if (storms.isEmpty() && serverOnly.isEmpty()) {
            Toast.makeText(
                hostContext,
                "No PlowTAK storms heard and none on the server. Start one from Storm.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        storms.forEach { s ->
            val state = if (s.isActive) "ACTIVE" else "ended"
            val mark = if (s.id == joinedId) "★ " else ""
            val mission = MissionCoverageCodec.effectiveMissionName(s.id, s.missionName)
            val ch = if (s.channel.isNotBlank()) " · ch ${s.channel}" else ""
            labels.add(
                "$mark${s.displayName()}  [$state]\n" +
                    "by ${s.startedBy.ifBlank { "?" }} · mission $mission$ch · ${s.cycleMinutes}m" +
                        if (s.coverageRetentionHours <= 0) " · keep red"
                        else " · clear ${s.coverageRetentionHours}h"
            )
            actions.add {
                if (!s.isActive) {
                    Toast.makeText(hostContext, "That storm has ended", Toast.LENGTH_SHORT).show()
                } else {
                    controller.joinStormSession(s)
                    Toast.makeText(
                        hostContext,
                        "Reporting into ${s.displayName()}",
                        Toast.LENGTH_SHORT
                    ).show()
                    onChanged?.invoke()
                }
            }
        }
        serverOnly.forEach { mission ->
            labels.add("$mission  [server]\nPlowTAK Data Sync mission on server")
            actions.add {
                val joining = ProgressDialog(hostContext).apply {
                    setMessage("Joining $mission…")
                    setCancelable(false)
                    show()
                }
                controller.joinStormFromServerMission(mission) { session ->
                    try {
                        joining.dismiss()
                    } catch (_: Throwable) {
                    }
                    if (session == null) {
                        Toast.makeText(hostContext, "Join failed", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            hostContext,
                            "Reporting into ${session.displayName()}",
                            Toast.LENGTH_SHORT
                        ).show()
                        onChanged?.invoke()
                    }
                }
            }
        }

        AlertDialog.Builder(hostContext)
            .setTitle("Storm — select Data Sync")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNeutralButton("Leave storm") { _, _ ->
                controller.leaveStormSession()
                Toast.makeText(hostContext, "Left storm (not reporting)", Toast.LENGTH_SHORT).show()
                onChanged?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showDataSyncServerPicker(
        controller: PlowTakController,
        hostContext: Context,
        after: (() -> Unit)? = null
    ) {
        val servers = TakServerTargets.listServers()
        if (servers.isEmpty()) {
            Toast.makeText(
                hostContext,
                "No TAK servers configured in ATAK Networks",
                Toast.LENGTH_LONG
            ).show()
            after?.invoke()
            return
        }
        val current = controller.prefs.dataSyncServerConnectString
        val labels = ArrayList<String>()
        val keys = ArrayList<String>()
        labels.add(
            if (current.isEmpty()) "★ First connected server (default)"
            else "First connected server (default)"
        )
        keys.add("")
        servers.forEach { s ->
            val state = if (s.connected) "connected" else "offline"
            val mark = if (s.connectString == current) "★ " else ""
            labels.add("$mark${s.label}  [$state]")
            keys.add(s.connectString)
        }
        AlertDialog.Builder(hostContext)
            .setTitle("Data Sync server")
            .setItems(labels.toTypedArray()) { _, which ->
                controller.prefs.dataSyncServerConnectString = keys[which]
                Toast.makeText(
                    hostContext,
                    "Data Sync → ${labels[which].removePrefix("★ ").substringBefore("  [")}",
                    Toast.LENGTH_SHORT
                ).show()
                after?.invoke()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> after?.invoke() }
            .show()
    }

    fun currentServerSummary(controller: PlowTakController): String {
        val preferred = controller.prefs.dataSyncServerConnectString
        val resolved = TakServerTargets.resolveApiBaseUrl(
            controller.mapView.context, preferred
        )
        return when {
            resolved == null -> "Data Sync server: (none connected)"
            preferred.isEmpty() -> "Data Sync server: ${resolved.label} (first connected)"
            resolved.usedFallback ->
                "Data Sync server: ${resolved.label} (fallback — preferred offline)"
            else -> "Data Sync server: ${resolved.label}"
        }
    }

    /** Auto storm label: PlowTAK Storm YY.MM.DD.HH (local device time). */
    fun defaultStormName(nowMs: Long): String {
        val stamp = stormNameStamp(nowMs)
        return "PlowTAK Storm $stamp"
    }

    /** Data Sync mission name stamp: yyyy.MMdd.HHmm (local 24h). */
    fun stormNameStamp(nowMs: Long): String {
        val fmt = SimpleDateFormat("yyyy.MMdd.HHmm", Locale.US)
        return fmt.format(Date(nowMs))
    }

    private fun cachedServerGroups(connectString: String): List<String> {
        return try {
            val servers: Array<TAKServer> =
                CotMapComponent.getInstance()?.servers ?: return emptyList()
            val match = servers.firstOrNull { it.connectString == connectString }
                ?: return emptyList()
            match.groups
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun showCycleMinutesDialog(
        controller: PlowTakController,
        hostContext: Context,
        onChanged: (() -> Unit)? = null
    ) {
        showCoverageSettingsDialog(controller, hostContext, onChanged)
    }

    /**
     * Edit storm-level coverage timers (cycle, P1–P3, clear-after, condition TTL).
     * Changes republish to every joined device via CoT + storm-config.json.
     */
    fun showCoverageSettingsDialog(
        controller: PlowTakController,
        hostContext: Context,
        onChanged: (() -> Unit)? = null
    ) {
        val storm = controller.stormManager.activeSession()
        val pad = dp(hostContext, 16)
        val green = EditText(hostContext).apply {
            hint = "Green until (min)"
            setText((storm?.greenUntilMinutes ?: controller.prefs.greenUntilMinutes).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val yellow = EditText(hostContext).apply {
            hint = "Yellow until (min)"
            setText((storm?.yellowUntilMinutes ?: controller.prefs.yellowUntilMinutes).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val cycle = EditText(hostContext).apply {
            hint = "Red after (min)"
            setText((storm?.cycleMinutes ?: controller.prefs.cycleTimeMinutes).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val p1 = EditText(hostContext).apply {
            hint = "P1 red-after min (0 = default)"
            setText((storm?.cycleP1Minutes ?: controller.prefs.cycleP1Minutes).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val p2 = EditText(hostContext).apply {
            hint = "P2 red-after min (0 = default)"
            setText((storm?.cycleP2Minutes ?: controller.prefs.cycleP2Minutes).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val p3 = EditText(hostContext).apply {
            hint = "P3 red-after min (0 = default)"
            setText((storm?.cycleP3Minutes ?: controller.prefs.cycleP3Minutes).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val retain = EditText(hostContext).apply {
            hint = "Remove after hours (0 = keep red)"
            setText(
                formatRetention(storm?.coverageRetentionHours ?: controller.prefs.retentionHours)
            )
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        val condTtl = EditText(hostContext).apply {
            hint = "Road condition report TTL minutes"
            setText(
                (storm?.roadConditionTtlMinutes ?: controller.prefs.roadConditionStaleMinutes)
                    .toString()
            )
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val column = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(green)
            addView(yellow)
            addView(cycle)
            addView(p1)
            addView(p2)
            addView(p3)
            addView(retain)
            addView(condTtl)
        }
        val msg = if (storm != null) {
            "Applies to joined storm \"${storm.displayName()}\" and syncs to all devices."
        } else {
            "No storm joined — saved as defaults for the next storm you start."
        }
        AlertDialog.Builder(hostContext)
            .setTitle("Storm coverage settings")
            .setMessage(msg)
            .setView(column)
            .setPositiveButton("Apply") { _, _ ->
                val g = green.text.toString().toIntOrNull()
                    ?: controller.prefs.greenUntilMinutes
                val y = yellow.text.toString().toIntOrNull()
                    ?: controller.prefs.yellowUntilMinutes
                val mins = cycle.text.toString().toIntOrNull()
                if (mins == null || mins < 5) {
                    Toast.makeText(hostContext, "Red after must be at least 5 minutes", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                val retainH = retain.text.toString().toDoubleOrNull() ?: 0.0
                if (retainH < 0 || retainH > 72) {
                    Toast.makeText(hostContext, "Remove-after must be 0–72 hours", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                val ttl = condTtl.text.toString().toIntOrNull()
                    ?: controller.prefs.roadConditionStaleMinutes
                val p1m = p1.text.toString().toIntOrNull() ?: 0
                val p2m = p2.text.toString().toIntOrNull() ?: 0
                val p3m = p3.text.toString().toIntOrNull() ?: 0
                if (storm != null) {
                    controller.updateStormCoverageSettings(
                        greenUntilMinutes = g,
                        yellowUntilMinutes = y,
                        cycleMinutes = mins,
                        cycleP1Minutes = p1m,
                        cycleP2Minutes = p2m,
                        cycleP3Minutes = p3m,
                        coverageRetentionHours = retainH,
                        roadConditionTtlMinutes = ttl
                    )
                    Toast.makeText(
                        hostContext,
                        "Storm settings synced (red ${mins}m, remove ${formatRetention(retainH)}h)",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    controller.prefs.greenUntilMinutes = g
                    controller.prefs.yellowUntilMinutes = y
                    controller.prefs.cycleTimeMinutes = mins
                    controller.prefs.cycleP1Minutes = p1m
                    controller.prefs.cycleP2Minutes = p2m
                    controller.prefs.cycleP3Minutes = p3m
                    controller.prefs.retentionHours = retainH
                    controller.prefs.roadConditionStaleMinutes = ttl
                    controller.syncFreshnessFromStorm()
                    controller.coverageOverlay.recolorAll(System.currentTimeMillis())
                    Toast.makeText(hostContext, "Defaults saved for next storm", Toast.LENGTH_SHORT)
                        .show()
                }
                onChanged?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatRetention(hours: Double): String =
        if (hours == hours.toLong().toDouble()) hours.toLong().toString()
        else hours.toString()

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    fun showExportProvisioningDialog(controller: PlowTakController, hostContext: Context) {
        val path = controller.exportProvisioningFile(
            name = "PlowTAK provisioning",
            agency = controller.stormManager.activeSession()?.agency.orEmpty()
        )
        if (path != null) {
            Toast.makeText(
                hostContext,
                "Wrote provisioning to $path",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(hostContext, "Provisioning export failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun showImportProvisioningDialog(
        controller: PlowTakController,
        hostContext: Context,
        onChanged: (() -> Unit)? = null
    ) {
        val files = controller.listProvisioningFiles()
        if (files.isEmpty()) {
            Toast.makeText(
                hostContext,
                "No *.ipprov.json in tools/plowtak — export one or copy a package there",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(hostContext)
            .setTitle("Import provisioning")
            .setItems(labels) { _, which ->
                val file = files.getOrNull(which) ?: return@setItems
                val ok = controller.importProvisioningFile(file.absolutePath)
                Toast.makeText(
                    hostContext,
                    if (ok) "Applied ${file.name}" else "Import failed: ${file.name}",
                    Toast.LENGTH_LONG
                ).show()
                if (ok) onChanged?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
