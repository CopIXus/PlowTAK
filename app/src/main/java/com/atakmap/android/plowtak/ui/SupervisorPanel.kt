package com.atakmap.android.plowtak.ui

import android.app.AlertDialog
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.Facility
import com.atakmap.android.plowtak.model.FacilityType
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.TaskKind
import com.atakmap.android.plowtak.model.ZoneType
import com.atakmap.android.plowtak.ops.AlertManager
import com.atakmap.android.plowtak.ops.RouteAssignment
import com.atakmap.android.plowtak.ops.FleetManager
import com.atakmap.android.plowtak.ops.ZoneManager
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater

/**
 * Supervisor ops panel: storm session start/stop, cycle-time settings
 * (default + per-priority), special zones, facility geofence creation at
 * the current position, live metrics, fleet list with long-press tasking,
 * the alert list with tap-to-ack / long-press-to-clear, and storm export.
 */
class SupervisorPanel(
    private val controller: PlowTakController,
    private val onOpenSettings: () -> Unit
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_supervisor, null
    )

    private val header = view.findViewById<TextView>(R.id.sup_header)
    private val stormLine = view.findViewById<TextView>(R.id.sup_storm_line)
    private val stormButton = view.findViewById<Button>(R.id.sup_storm_button)
    private val dataSyncLine = view.findViewById<TextView>(R.id.sup_datasync_line)
    private val cycleTime = view.findViewById<EditText>(R.id.sup_cycle_time)
    private val cycleP1 = view.findViewById<EditText>(R.id.sup_cycle_p1)
    private val cycleP2 = view.findViewById<EditText>(R.id.sup_cycle_p2)
    private val cycleP3 = view.findViewById<EditText>(R.id.sup_cycle_p3)
    private val zoneName = view.findViewById<EditText>(R.id.sup_zone_name)
    private val zoneType = view.findViewById<Spinner>(R.id.sup_zone_type)
    private val zoneRadius = view.findViewById<EditText>(R.id.sup_zone_radius)
    private val zoneList = view.findViewById<ListView>(R.id.sup_zone_list)
    private val facilityName = view.findViewById<EditText>(R.id.sup_facility_name)
    private val facilityType = view.findViewById<Spinner>(R.id.sup_facility_type)
    private val alertList = view.findViewById<ListView>(R.id.sup_alert_list)
    private val metricsLine = view.findViewById<TextView>(R.id.sup_metrics_line)
    private val fleetList = view.findViewById<ListView>(R.id.sup_fleet_list)

    private val alertAdapter = ArrayAdapter<String>(
        controller.pluginContext, android.R.layout.simple_list_item_1
    )
    private val fleetAdapter = ArrayAdapter<String>(
        controller.pluginContext, android.R.layout.simple_list_item_1
    )
    private val zoneAdapter = ArrayAdapter<String>(
        controller.pluginContext, android.R.layout.simple_list_item_1
    )

    private var currentAlerts: List<AlertEvent> = emptyList()
    private var currentZones: List<SpecialZone> = emptyList()
    private var currentFleet: List<PlowVehicle> = emptyList()

    private val fleetListener = FleetManager.Listener { view.post { refresh() } }
    private val alertListener = object : AlertManager.Listener {
        override fun onAlertsChanged(alerts: List<AlertEvent>) {
            view.post { refresh() }
        }
        override fun onLocalTransition(alert: AlertEvent) {}
    }
    private val zoneListener = ZoneManager.Listener { view.post { refresh() } }

    init {
        alertList.adapter = alertAdapter
        fleetList.adapter = fleetAdapter
        zoneList.adapter = zoneAdapter

        facilityType.adapter = ArrayAdapter(
            controller.pluginContext,
            android.R.layout.simple_spinner_dropdown_item,
            FacilityType.entries.map { it.label }
        )
        zoneType.adapter = ArrayAdapter(
            controller.pluginContext,
            android.R.layout.simple_spinner_dropdown_item,
            ZoneType.entries.map { it.label }
        )

        cycleTime.setText(controller.prefs.cycleTimeMinutes.toString())
        val cycles = controller.prefs.cycleTimes()
        if (cycles.p1Minutes > 0) cycleP1.setText(cycles.p1Minutes.toString())
        if (cycles.p2Minutes > 0) cycleP2.setText(cycles.p2Minutes.toString())
        if (cycles.p3Minutes > 0) cycleP3.setText(cycles.p3Minutes.toString())
        bindCycleFieldsFromStorm()

        stormButton.setOnClickListener { toggleStorm() }
        view.findViewById<Button>(R.id.sup_storm_join).setOnClickListener {
            StormServerDialogs.showJoinStormDialog(
                controller, controller.mapView.context
            ) { view.post { refresh() } }
        }
        view.findViewById<Button>(R.id.sup_datasync_server).setOnClickListener {
            StormServerDialogs.showDataSyncServerPicker(
                controller, controller.mapView.context
            ) { view.post { refresh() } }
        }
        view.findViewById<Button>(R.id.sup_cycle_apply).setOnClickListener { applyCycleTime() }
        view.findViewById<Button>(R.id.sup_zone_add).setOnClickListener { addZone() }
        view.findViewById<Button>(R.id.sup_facility_add).setOnClickListener { addFacility() }
        view.findViewById<Button>(R.id.sup_export).setOnClickListener { exportStorm() }
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

        zoneList.setOnItemLongClickListener { _, _, position, _ ->
            currentZones.getOrNull(position)?.let { zone ->
                controller.removeSpecialZone(zone.id)
                Toast.makeText(
                    controller.mapView.context,
                    "Zone \"${zone.name}\" removed", Toast.LENGTH_SHORT
                ).show()
            }
            true
        }

        fleetList.setOnItemLongClickListener { _, _, position, _ ->
            currentFleet.getOrNull(position)?.let { fleetTruckDialog(it) }
            true
        }

        controller.fleetManager.addListener(fleetListener)
        controller.alertManager.addListener(alertListener)
        controller.zoneManager.addListener(zoneListener)
        refresh()
    }

    fun dispose() {
        controller.fleetManager.removeListener(fleetListener)
        controller.alertManager.removeListener(alertListener)
        controller.zoneManager.removeListener(zoneListener)
    }

    fun refresh() {
        val cap = controller.capabilityStore.load()
        header.text = "${cap.callsign}  (supervisor)"

        val session = controller.stormManager.activeSession()
        if (session != null) {
            stormLine.text =
                "Reporting: ${session.displayName()} (by ${session.startedBy.ifBlank { "?" }})"
            stormButton.text = view.context.getString(R.string.sup_storm_end)
        } else {
            val heard = controller.stormManager.knownStorms().count { it.isActive }
            stormLine.text = if (heard == 0) {
                "No storm selected — start or join one"
            } else {
                "No storm selected — $heard active storm(s) available to join"
            }
            stormButton.text = view.context.getString(R.string.sup_storm_start)
        }
        dataSyncLine.text = StormServerDialogs.currentServerSummary(controller)
        bindCycleFieldsFromStorm()

        val now = System.currentTimeMillis()
        val staleAfter = controller.fleetManager.staleAfterMs

        currentAlerts = controller.alertManager.activeAlerts()
        alertAdapter.clear()
        alertAdapter.addAll(currentAlerts.map { FleetListFormatter.alertLine(it, now) })
        alertAdapter.notifyDataSetChanged()

        currentZones = controller.zoneManager.all()
        zoneAdapter.clear()
        zoneAdapter.addAll(currentZones.map { zone ->
            "${zone.name} (${zone.type.label}, ×${zone.cycleMultiplier}, ${zone.radiusM.toInt()} m)"
        })
        zoneAdapter.notifyDataSetChanged()

        currentFleet = controller.fleetManager.all().sortedBy { it.callsign }
        fleetAdapter.clear()
        fleetAdapter.addAll(
            currentFleet.map { v: PlowVehicle ->
                val base = FleetListFormatter.vehicleLine(v, now, staleAfter)
                val route = controller.routeAssignments.assignmentFor(v.uid)?.routeId
                if (route.isNullOrEmpty()) base else "$base  [$route]"
            }
        )
        fleetAdapter.notifyDataSetChanged()

        refreshMetrics()
    }

    /** Prefer joined-storm timers so Apply does not republish stale prefs. */
    private fun bindCycleFieldsFromStorm() {
        val session = controller.stormManager.activeSession()
        val cycles = session?.cycleTimes() ?: controller.prefs.cycleTimes()
        cycleTime.setText(cycles.defaultMinutes.toString())
        cycleP1.setText(if (cycles.p1Minutes > 0) cycles.p1Minutes.toString() else "")
        cycleP2.setText(if (cycles.p2Minutes > 0) cycles.p2Minutes.toString() else "")
        cycleP3.setText(if (cycles.p3Minutes > 0) cycles.p3Minutes.toString() else "")
    }

    private fun refreshMetrics() {
        val m = controller.liveMetrics()
        metricsLine.text = String.format(
            java.util.Locale.US,
            "Treated %.1f lane-mi (%.1f/h) • %d%% within cycle • %d reloads • %d trucks",
            m.laneMilesTreated, m.laneMilesPerHour,
            (m.coverageWithinCycle * 100).toInt(),
            m.reloadsByTruck.values.sum(),
            m.activeTruckCount
        )
    }

    private fun toggleStorm() {
        val session = controller.stormManager.activeSession()
        if (session != null) {
            AlertDialog.Builder(controller.mapView.context)
                .setTitle("End storm?")
                .setMessage(
                    "Ends ${session.displayName()} for the fleet and stops Data Sync " +
                        "uploads. The mission and its data stay on the TAK server."
                )
                .setPositiveButton("End storm") { _, _ ->
                    controller.endStormSession()
                    refresh()
                }
                .setNeutralButton("Leave only") { _, _ ->
                    controller.leaveStormSession()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            StormServerDialogs.showStartStormDialog(
                controller, controller.mapView.context
            )
            view.post { refresh() }
        }
    }

    private fun applyCycleTime() {
        val minutes = cycleTime.text.toString().toIntOrNull()
        if (minutes == null || minutes < 5) {
            Toast.makeText(controller.mapView.context, "Invalid cycle time", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val p1 = cycleP1.text.toString().toIntOrNull() ?: 0
        val p2 = cycleP2.text.toString().toIntOrNull() ?: 0
        val p3 = cycleP3.text.toString().toIntOrNull() ?: 0
        if (controller.stormManager.activeSession() != null) {
            controller.updateStormCoverageSettings(
                cycleMinutes = minutes,
                cycleP1Minutes = p1,
                cycleP2Minutes = p2,
                cycleP3Minutes = p3
            )
            Toast.makeText(
                controller.mapView.context,
                "Storm cycle: $minutes min (synced to fleet)",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            controller.prefs.cycleTimeMinutes = minutes
            controller.prefs.cycleP1Minutes = p1
            controller.prefs.cycleP2Minutes = p2
            controller.prefs.cycleP3Minutes = p3
            controller.freshnessModel.cycleTimeMinutes = minutes
            controller.coverageOverlay.recolorAll(System.currentTimeMillis())
            Toast.makeText(
                controller.mapView.context,
                "Cycle time: $minutes min (default for next storm)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun addZone() {
        val pos = controller.lastPosition
        if (pos == null) {
            Toast.makeText(controller.mapView.context, "No GPS position yet", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val name = zoneName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(controller.mapView.context, "Enter zone name", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val type = ZoneType.entries[zoneType.selectedItemPosition
            .coerceIn(0, ZoneType.entries.size - 1)]
        val radius = zoneRadius.text.toString().toDoubleOrNull() ?: DEFAULT_ZONE_RADIUS_M
        controller.putSpecialZone(
            SpecialZone(
                id = "zone-${System.currentTimeMillis()}",
                name = name,
                type = type,
                cycleMultiplier = type.defaultMultiplier,
                centerLat = pos.lat,
                centerLon = pos.lon,
                radiusM = radius.coerceAtLeast(25.0)
            )
        )
        zoneName.setText("")
        Toast.makeText(
            controller.mapView.context,
            "${type.label} zone \"$name\" added (×${type.defaultMultiplier} cycle)",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Long-press a truck: task it or assign a route. */
    private fun fleetTruckDialog(vehicle: PlowVehicle) {
        val assignment = controller.routeAssignments.assignmentFor(vehicle.uid)
        val routeLine = assignment?.let { "Route: ${it.routeId}" } ?: "No route assigned"
        AlertDialog.Builder(controller.mapView.context)
            .setTitle(vehicle.callsign)
            .setMessage(routeLine)
            .setPositiveButton("Task") { _, _ -> taskTruckDialog(vehicle) }
            .setNeutralButton("Assign route") { _, _ -> assignRouteDialog(vehicle) }
            .setNegativeButton("Clear route") { _, _ ->
                controller.unassignRoute(vehicle.uid)
                Toast.makeText(
                    controller.mapView.context,
                    "Cleared route for ${vehicle.callsign}", Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            .show()
    }

    private fun assignRouteDialog(vehicle: PlowVehicle) {
        val input = EditText(controller.mapView.context)
        input.hint = view.context.getString(R.string.sup_route_id_hint)
        AlertDialog.Builder(controller.mapView.context)
            .setTitle("Assign route to ${vehicle.callsign}")
            .setView(input)
            .setPositiveButton("Assign") { _, _ ->
                val routeId = input.text.toString().trim()
                if (routeId.isEmpty()) {
                    Toast.makeText(
                        controller.mapView.context, "Enter a route id", Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val source = controller.resolveRouteSource(routeId)
                controller.assignRoute(
                    vehicle.uid, vehicle.callsign, routeId, source
                )
                val srcLabel = if (source == RouteAssignment.Source.DRAWN) "drawn" else "GIS"
                Toast.makeText(
                    controller.mapView.context,
                    "Assigned $routeId ($srcLabel) to ${vehicle.callsign}",
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Send a task at the supervisor or truck position. */
    private fun taskTruckDialog(vehicle: PlowVehicle) {
        val pos = controller.lastPosition
        val input = EditText(controller.mapView.context)
        input.hint = "Task description"
        AlertDialog.Builder(controller.mapView.context)
            .setTitle("Task ${vehicle.callsign}")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val task = controller.createTask(
                    targetUid = vehicle.uid,
                    targetCallsign = vehicle.callsign,
                    kind = TaskKind.SEGMENT,
                    refId = "",
                    lat = pos?.lat ?: vehicle.lat,
                    lon = pos?.lon ?: vehicle.lon,
                    description = input.text.toString().trim()
                        .ifEmpty { "Treat the flagged stretch" }
                )
                Toast.makeText(
                    controller.mapView.context,
                    if (task != null) "Task sent to ${vehicle.callsign}" else "Task not sent",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportStorm() {
        Toast.makeText(controller.mapView.context, "Exporting…", Toast.LENGTH_SHORT).show()
        controller.exportStormSession { folder ->
            view.post {
                Toast.makeText(
                    controller.mapView.context,
                    if (folder != null) "Exported to $folder" else "Export failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
        private const val DEFAULT_ZONE_RADIUS_M = 200.0
    }
}
