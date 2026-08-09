package com.atakmap.android.plowtak.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.ops.AlertManager
import com.atakmap.android.plowtak.ops.FleetManager
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater

/**
 * Read-only observer view: live fleet list and active alerts. Coverage and
 * alert markers render on the map; can join a storm for map context but has
 * no treat controls.
 */
class ObserverPanel(
    private val controller: PlowTakController,
    private val onOpenSettings: () -> Unit
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_observer, null
    )

    private val header = view.findViewById<TextView>(R.id.obs_header)
    private val stormLine = view.findViewById<TextView>(R.id.obs_storm_line)

    private val alertAdapter = ArrayAdapter<String>(
        controller.pluginContext, android.R.layout.simple_list_item_1
    )
    private val fleetAdapter = ArrayAdapter<String>(
        controller.pluginContext, android.R.layout.simple_list_item_1
    )

    private val fleetListener = FleetManager.Listener { view.post { refresh() } }
    private val alertListener = object : AlertManager.Listener {
        override fun onAlertsChanged(alerts: List<AlertEvent>) {
            view.post { refresh() }
        }
        override fun onLocalTransition(alert: AlertEvent) {}
    }

    init {
        view.findViewById<ListView>(R.id.obs_alert_list).adapter = alertAdapter
        view.findViewById<ListView>(R.id.obs_fleet_list).adapter = fleetAdapter
        view.findViewById<Button>(R.id.obs_settings).setOnClickListener { onOpenSettings() }
        view.findViewById<Button>(R.id.obs_storm_join).setOnClickListener {
            StormServerDialogs.showJoinStormDialog(
                controller, controller.mapView.context
            ) { view.post { refresh() } }
        }

        controller.fleetManager.addListener(fleetListener)
        controller.alertManager.addListener(alertListener)
        refresh()
    }

    fun dispose() {
        controller.fleetManager.removeListener(fleetListener)
        controller.alertManager.removeListener(alertListener)
    }

    fun refresh() {
        val cap = controller.capabilityStore.load()
        val label = cap.observerLabel.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""
        header.text = view.context.getString(R.string.obs_title) + label

        val storm = controller.stormManager.activeSession()
        stormLine.text = storm?.let { "Reporting: ${it.displayName()}" }
            ?: "No storm selected"

        val now = System.currentTimeMillis()
        val staleAfter = controller.fleetManager.staleAfterMs

        alertAdapter.clear()
        alertAdapter.addAll(
            controller.alertManager.activeAlerts().map { FleetListFormatter.alertLine(it, now) }
        )
        alertAdapter.notifyDataSetChanged()

        fleetAdapter.clear()
        fleetAdapter.addAll(
            controller.fleetManager.all()
                .sortedBy { it.callsign }
                .map { FleetListFormatter.vehicleLine(it, now, staleAfter) }
        )
        fleetAdapter.notifyDataSetChanged()
    }
}
