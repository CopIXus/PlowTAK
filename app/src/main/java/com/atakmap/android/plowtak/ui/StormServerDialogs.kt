package com.atakmap.android.plowtak.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.sync.MissionCoverageCodec
import com.atakmap.android.plowtak.sync.TakServerTargets

/**
 * Shared dialogs for starting / joining storms and picking the Data Sync server.
 */
object StormServerDialogs {

    fun showStartStormDialog(controller: PlowTakController, hostContext: Context) {
        val pad = dp(hostContext, 16)
        val agency = EditText(hostContext).apply {
            hint = "Agency (e.g. VDOT)"
            setSingleLine()
        }
        val label = EditText(hostContext).apply {
            hint = "Storm designator (e.g. I-81 North)"
            setSingleLine()
        }
        val mission = EditText(hostContext).apply {
            hint = "Mission override (optional)"
            setSingleLine()
        }
        val column = LinearLayout(hostContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(agency)
            addView(label)
            addView(mission)
        }
        val serverLine = currentServerSummary(controller)
        AlertDialog.Builder(hostContext)
            .setTitle("Start storm session")
            .setMessage(
                "Broadcasts this storm to the fleet. Coverage Data Sync uploads use:\n\n" +
                    serverLine +
                    "\n\nEmpty mission → plowtak-coverage-{stormId}"
            )
            .setView(column)
            .setPositiveButton("Start") { _, _ ->
                val session = controller.startStormSession(
                    label = label.text.toString(),
                    agency = agency.text.toString(),
                    missionName = mission.text.toString()
                )
                if (session == null) {
                    Toast.makeText(hostContext, "Cannot start storm", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        hostContext,
                        "Storm started: ${session.displayName()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNeutralButton("Data Sync server") { d, _ ->
                d.dismiss()
                showDataSyncServerPicker(controller, hostContext) {
                    showStartStormDialog(controller, hostContext)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showJoinStormDialog(
        controller: PlowTakController,
        hostContext: Context,
        onChanged: (() -> Unit)? = null
    ) {
        val storms = controller.stormManager.knownStorms()
        if (storms.isEmpty()) {
            Toast.makeText(
                hostContext,
                "No storms heard yet. Wait for a supervisor broadcast, or start one.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val joinedId = controller.stormManager.activeStormId
        val labels = storms.map { s ->
            val state = if (s.isActive) "ACTIVE" else "ended"
            val mark = if (s.id == joinedId) "★ " else ""
            val mission = MissionCoverageCodec.effectiveMissionName(s.id, s.missionName)
            "$mark${s.displayName()}  [$state]\n" +
                "by ${s.startedBy.ifBlank { "?" }} · mission $mission"
        }.toTypedArray()

        AlertDialog.Builder(hostContext)
            .setTitle("Join storm to report into")
            .setItems(labels) { _, which ->
                val chosen = storms[which]
                if (!chosen.isActive) {
                    Toast.makeText(hostContext, "That storm has ended", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                controller.joinStormSession(chosen)
                Toast.makeText(
                    hostContext,
                    "Reporting into ${chosen.displayName()}",
                    Toast.LENGTH_SHORT
                ).show()
                onChanged?.invoke()
            }
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

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
