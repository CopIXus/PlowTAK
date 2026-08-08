package com.atakmap.android.ideaplow.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.atakmap.android.ideaplow.IdeaPlowController
import com.atakmap.android.ideaplow.R
import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.Facility
import com.atakmap.android.ideaplow.model.FacilityType
import com.atakmap.android.ideaplow.model.PlowVehicle
import com.atakmap.android.ideaplow.ops.AlertManager
import com.atakmap.android.ideaplow.ops.FleetManager
import com.atakmap.android.ideaplow.plugin.PluginLayoutInflater

/**
 * Supervisor ops panel: storm session start/stop, cycle-time setting,
 * facility geofence creation at the current position, fleet list, and the
 * alert list with tap-to-ack / long-press-to-clear.
 */
class SupervisorPanel(
    private val controller: IdeaPlowController,
    private val onOpenSettings: () -> Unit
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_supervisor, null
    )

    private val header = view.findViewById<TextView>(R.id.sup_header)
    private val stormLine = view.findViewById<TextView>(R.id.sup_storm_line)
    private val stormButton = view.findViewById<Button>(R.id.sup_storm_button)
    private val cycleTime = view.findViewById<EditText>(R.id.sup_cycle_time)
    private val facilityName = view.findViewById<EditText>(R.id.sup_facility_name)
    private val facilityType = view.findViewById<Spinner>(R.id.sup_facility_type)
    private val alertList = view.findViewById<ListView>(R.id.sup_alert_list)
    private val fleetList = view.findViewById<ListView>(R.id.sup_fleet_list)

    private val alertAdapter = ArrayAdapter<String>(
        controller.pluginContext, android.R.layout.simple_list_item_1
    )
    private val fleetAdapter = ArrayAdapter<String>(
        controller.pluginContext, android.R.layout.simple_list_item_1
    )

    private var currentAlerts: List<AlertEvent> = emptyList()

    private val fleetListener = FleetManager.Listener { view.post { refresh() } }
    private val alertListener = object : AlertManager.Listener {
        override fun onAlertsChanged(alerts: List<AlertEvent>) {
            view.post { refresh() }
        }
        override fun onLocalTransition(alert: AlertEvent) {}
    }

    init {
        alertList.adapter = alertAdapter
        fleetList.adapter = fleetAdapter

        facilityType.adapter = ArrayAdapter(
            controller.pluginContext,
            android.R.layout.simple_spinner_dropdown_item,
            FacilityType.entries.map { it.label }
        )

        cycleTime.setText(controller.prefs.cycleTimeMinutes.toString())

        stormButton.setOnClickListener { toggleStorm() }
        view.findViewById<Button>(R.id.sup_cycle_apply).setOnClickListener { applyCycleTime() }
        view.findViewById<Button>(R.id.sup_facility_add).setOnClickListener { addFacility() }
        view.findViewById<Button>(R.id.sup_distress).setOnClickListener {
            controller.sendDistress()
        }
        view.findViewById<Button>(R.id.sup_settings).setOnClickListener { onOpenSettings() }

        alertList.setOnItemClickListener { _, _, position, _ ->
            currentAlerts.getOrNull(position)?.let { alert ->
                controller.alertManager.acknowledge(
                    alert.uid, controller.capabilityStore.load().callsign
                )
            }
        }
        alertList.setOnItemLongClickListener { _, _, position, _ ->
            currentAlerts.getOrNull(position)?.let { alert ->
                controller.alertManager.clear(
                    alert.uid, controller.capabilityStore.load().callsign
                )
            }
            true
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
        header.text = "${cap.callsign}  (supervisor)"

        val session = controller.stormManager.current
        if (session != null && session.isActive) {
            stormLine.text = "Storm ${session.id} active (started by ${session.startedBy})"
            stormButton.text = view.context.getString(R.string.sup_storm_end)
        } else {
            stormLine.text = "No active storm session"
            stormButton.text = view.context.getString(R.string.sup_storm_start)
        }

        val now = System.currentTimeMillis()
        val staleAfter = controller.fleetManager.staleAfterMs

        currentAlerts = controller.alertManager.activeAlerts()
        alertAdapter.clear()
        alertAdapter.addAll(currentAlerts.map { FleetListFormatter.alertLine(it, now) })
        alertAdapter.notifyDataSetChanged()

        fleetAdapter.clear()
        fleetAdapter.addAll(
            controller.fleetManager.all()
                .sortedBy { it.callsign }
                .map { v: PlowVehicle -> FleetListFormatter.vehicleLine(v, now, staleAfter) }
        )
        fleetAdapter.notifyDataSetChanged()
    }

    private fun toggleStorm() {
        val session = controller.stormManager.current
        if (session != null && session.isActive) {
            controller.endStormSession()
        } else {
            controller.startStormSession()
        }
        refresh()
    }

    private fun applyCycleTime() {
        val minutes = cycleTime.text.toString().toIntOrNull()
        if (minutes == null || minutes < 5) {
            Toast.makeText(controller.mapView.context, "Invalid cycle time", Toast.LENGTH_SHORT)
                .show()
            return
        }
        controller.prefs.cycleTimeMinutes = minutes
        controller.freshnessModel.cycleTimeMinutes = minutes
        controller.coverageOverlay.recolorAll(System.currentTimeMillis())
        Toast.makeText(
            controller.mapView.context, "Cycle time: $minutes min", Toast.LENGTH_SHORT
        ).show()
    }

    private fun addFacility() {
        val pos = controller.lastPosition
        if (pos == null) {
            Toast.makeText(controller.mapView.context, "No GPS position yet", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val name = facilityName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(controller.mapView.context, "Enter facility name", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val type = FacilityType.entries[facilityType.selectedItemPosition
            .coerceIn(0, FacilityType.entries.size - 1)]
        controller.facilityGeofences.add(
            Facility(
                id = "fac-${System.currentTimeMillis()}",
                name = name,
                type = type,
                lat = pos.lat,
                lon = pos.lon,
                radiusM = DEFAULT_FACILITY_RADIUS_M
            )
        )
        facilityName.setText("")
        Toast.makeText(
            controller.mapView.context,
            "${type.label} \"$name\" added (${DEFAULT_FACILITY_RADIUS_M.toInt()} m radius)",
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private const val DEFAULT_FACILITY_RADIUS_M = 150.0
    }
}
