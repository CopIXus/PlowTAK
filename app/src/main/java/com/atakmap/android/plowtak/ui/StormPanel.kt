package com.atakmap.android.plowtak.ui

import android.app.ProgressDialog
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater
import com.atakmap.android.plowtak.prefs.PlowTakSettingsBackup
import com.atakmap.android.plowtak.sync.MissionCoverageCodec
import com.atakmap.android.plowtak.sync.TakServerTargets

/**
 * Full-screen Storm / Data Sync manager: pick the TAK server, the channel
 * that owns the storm, then join an existing storm (heard on CoT or already
 * on the server) or create a new one. Server + channel selections persist in
 * prefs and mirror to the settings backup, surviving plugin updates.
 */
class StormPanel(
    private val controller: PlowTakController,
    private val onDone: () -> Unit
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_storm, null
    )

    private val hostContext = controller.mapView.context
    private val statusLine = view.findViewById<TextView>(R.id.storm_status_line)
    private val serverSpinner = view.findViewById<Spinner>(R.id.storm_server_spinner)
    private val channelSpinner = view.findViewById<Spinner>(R.id.storm_channel_spinner)
    private val channelHint = view.findViewById<TextView>(R.id.storm_channel_hint)
    private val stormList = view.findViewById<LinearLayout>(R.id.storm_list)
    private val stormListHint = view.findViewById<TextView>(R.id.storm_list_hint)
    private val newName = view.findViewById<EditText>(R.id.storm_new_name)

    private var servers: List<TakServerTargets.Target> = emptyList()
    private var channels: List<String> = emptyList()
    private var suppressServerSelect = true
    private var suppressChannelSelect = true

    init {
        view.findViewById<Button>(R.id.storm_done).setOnClickListener { onDone() }
        view.findViewById<Button>(R.id.storm_create).setOnClickListener { createStorm() }
        view.findViewById<Button>(R.id.storm_leave).setOnClickListener {
            controller.leaveStormSession()
            Toast.makeText(hostContext, "Left storm (not reporting)", Toast.LENGTH_SHORT).show()
            refresh()
        }
        view.findViewById<Button>(R.id.storm_end).setOnClickListener {
            StormServerDialogs.showEndStormDialog(controller, hostContext)
            view.postDelayed({ refresh() }, 500)
        }
        newName.setText(StormServerDialogs.defaultStormName(System.currentTimeMillis()))

        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, v: View?, position: Int, id: Long
            ) {
                if (suppressServerSelect) {
                    suppressServerSelect = false
                    return
                }
                val chosen = servers.getOrNull(position) ?: return
                controller.prefs.dataSyncServerConnectString = chosen.connectString
                persistSelections()
                loadChannels(chosen.connectString)
                reloadStorms()
                refreshStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        channelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, v: View?, position: Int, id: Long
            ) {
                if (suppressChannelSelect) {
                    suppressChannelSelect = false
                    return
                }
                val chosen = channels.getOrNull(position) ?: return
                controller.prefs.stormChannel = chosen
                persistSelections()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        refresh()
    }

    /** Reload servers, channels, storms and the status line. */
    fun refresh() {
        loadServers()
        reloadStorms()
        refreshStatus()
    }

    // ------------------------------------------------------------- server

    private fun loadServers() {
        servers = TakServerTargets.listServers()
        if (servers.isEmpty()) {
            statusLine.text = "No TAK servers configured in ATAK Networks"
        }
        val labels = servers.map { s ->
            val state = if (s.connected) "connected" else "offline"
            "${s.label}  [$state]"
        }
        suppressServerSelect = true
        serverSpinner.adapter = ArrayAdapter(
            controller.pluginContext,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val preferred = controller.prefs.dataSyncServerConnectString
        val idx = servers.indexOfFirst { it.connectString == preferred }
            .takeIf { it >= 0 }
            ?: servers.indexOfFirst { it.connected }.takeIf { it >= 0 }
            ?: 0
        if (servers.isNotEmpty()) {
            serverSpinner.setSelection(idx)
            loadChannels(servers[idx].connectString)
        }
    }

    // ------------------------------------------------------------ channel

    private fun loadChannels(connectString: String) {
        channelHint.text = "Loading channels…"
        StormServerDialogs.loadChannels(hostContext, connectString) { groups ->
            channels = groups.ifEmpty { listOf("__ANON__") }
            channelHint.text = if (groups.isEmpty()) {
                "No active channels returned — using __ANON__"
            } else {
                "Channels this device can publish to"
            }
            suppressChannelSelect = true
            channelSpinner.adapter = ArrayAdapter(
                controller.pluginContext,
                android.R.layout.simple_spinner_dropdown_item,
                channels
            )
            val saved = controller.prefs.stormChannel
            val idx = channels.indexOf(saved).takeIf { it >= 0 } ?: 0
            channelSpinner.setSelection(idx)
        }
    }

    // ------------------------------------------------------------- storms

    private fun reloadStorms() {
        stormListHint.text = "Checking server for PlowTAK missions…"
        renderStormRows(emptyList())
        controller.listServerPlowTakMissions { serverMissions ->
            stormListHint.text = if (serverMissions.isEmpty()) {
                "No PlowTAK missions on the server yet"
            } else {
                "PlowTAK Data Sync missions on the server + storms heard on CoT"
            }
            renderStormRows(serverMissions)
        }
    }

    private fun renderStormRows(serverMissions: List<String>) {
        stormList.removeAllViews()
        val joinedId = controller.stormManager.activeStormId
        val heard = controller.stormManager.knownStorms()
        val heardMissions = heard.filter { it.isActive }.map {
            MissionCoverageCodec.effectiveMissionName(it.id, it.missionName)
        }.toSet()

        // Ended storms are not joinable — keep the list to live options only.
        heard.filter { it.isActive }.forEach { s ->
            val mark = if (s.id == joinedId) "★ " else ""
            val mission = MissionCoverageCodec.effectiveMissionName(s.id, s.missionName)
            addStormRow(
                title = "$mark${s.displayName()}  [ACTIVE]",
                subtitle = "heard on CoT · mission $mission · ${s.cycleMinutes}m · " +
                    retentionLabel(s.coverageRetentionHours),
                highlighted = s.id == joinedId
            ) {
                controller.joinStormSession(s)
                Toast.makeText(
                    hostContext, "Reporting into ${s.displayName()}", Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
        }

        serverMissions.filter { it !in heardMissions }.forEach { mission ->
            addStormRow(
                title = mission,
                subtitle = "PlowTAK Data Sync mission on server",
                highlighted = false
            ) {
                val busy = ProgressDialog(hostContext).apply {
                    setMessage("Joining $mission…")
                    setCancelable(false)
                    show()
                }
                controller.joinStormFromServerMission(mission) { session: StormSession? ->
                    try {
                        busy.dismiss()
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
                        refresh()
                    }
                }
            }
        }

        if (stormList.childCount == 0) {
            val empty = TextView(controller.pluginContext).apply {
                text = "No storms yet — create one below"
                setTextColor(0xFF9E9E9E.toInt())
                textSize = 14f
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            stormList.addView(empty)
        }
    }

    private fun addStormRow(
        title: String,
        subtitle: String,
        highlighted: Boolean,
        onClick: () -> Unit
    ) {
        val row = LinearLayout(controller.pluginContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(if (highlighted) 0x335FA8D3 else 0x22FFFFFF)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(controller.pluginContext).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        })
        row.addView(TextView(controller.pluginContext).apply {
            text = subtitle
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 12f
        })
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        stormList.addView(row, lp)
    }

    // ------------------------------------------------------------- create

    private fun createStorm() {
        val server = servers.getOrNull(serverSpinner.selectedItemPosition)
        if (server == null) {
            Toast.makeText(hostContext, "Pick a TAK server first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!server.connected) {
            Toast.makeText(
                hostContext,
                "Server offline — connect it in ATAK Networks first",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val channel = channels.getOrNull(channelSpinner.selectedItemPosition)
            ?: controller.prefs.stormChannel.ifBlank { "__ANON__" }
        val label = newName.text.toString().trim().ifEmpty {
            StormServerDialogs.defaultStormName(System.currentTimeMillis())
        }
        controller.prefs.dataSyncServerConnectString = server.connectString
        controller.prefs.stormChannel = channel
        persistSelections()
        val session = controller.startStormSession(
            label = label,
            agency = "",
            missionName = label,
            channel = channel,
            cycleMinutes = controller.prefs.cycleTimeMinutes,
            cycleP1Minutes = controller.prefs.cycleP1Minutes,
            cycleP2Minutes = controller.prefs.cycleP2Minutes,
            cycleP3Minutes = controller.prefs.cycleP3Minutes,
            coverageRetentionHours = controller.prefs.retentionHours,
            roadConditionTtlMinutes = controller.prefs.roadConditionStaleMinutes
        )
        if (session == null) {
            Toast.makeText(hostContext, "Cannot start storm", Toast.LENGTH_SHORT).show()
            return
        }
        val mission = MissionCoverageCodec.effectiveMissionName(session.id, session.missionName)
        Toast.makeText(
            hostContext,
            "Storm started: ${session.displayName()}\nData Sync mission: $mission\nChannel: $channel",
            Toast.LENGTH_LONG
        ).show()
        newName.setText(StormServerDialogs.defaultStormName(System.currentTimeMillis()))
        refresh()
    }

    // -------------------------------------------------------------- misc

    private fun refreshStatus() {
        val active = controller.stormManager.activeSession()
        val stormText = if (active != null) {
            "Joined: ${active.displayName()} · ${active.cycleMinutes}m cycle · " +
                retentionLabel(active.coverageRetentionHours)
        } else {
            "No storm joined"
        }
        statusLine.text = stormText + "\n" + StormServerDialogs.currentServerSummary(controller)
    }

    private fun retentionLabel(hours: Double): String =
        if (hours <= 0) "keep red"
        else "clear ${if (hours == hours.toLong().toDouble()) hours.toLong() else hours}h"

    /** Mirror server/channel picks to the uninstall-proof settings backup. */
    private fun persistSelections() {
        PlowTakSettingsBackup.export(controller.pluginContext)
    }

    private fun dp(value: Int): Int =
        (value * controller.pluginContext.resources.displayMetrics.density).toInt()
}
