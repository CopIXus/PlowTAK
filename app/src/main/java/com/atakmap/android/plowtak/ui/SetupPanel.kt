package com.atakmap.android.plowtak.ui

import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import com.atakmap.android.plowtak.BuildConfig
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * First-run / settings flow: pick the vehicle type, then sub-options
 * (plow/wing/tow widths in feet, hasSalt, presence, distress).
 * Saving persists a sanitized capability and returns to the main panel.
 */
class SetupPanel(
    private val controller: PlowTakController,
    private val onSaved: () -> Unit
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_setup, null
    )

    private val typeGroup = view.findViewById<RadioGroup>(R.id.setup_type_group)
    private val callsign = view.findViewById<EditText>(R.id.setup_callsign)
    private val vehicleId = view.findViewById<EditText>(R.id.setup_vehicle_id)
    private val treatOptions = view.findViewById<View>(R.id.setup_treat_options)
    private val hasSalt = view.findViewById<CheckBox>(R.id.setup_has_salt)
    private val plowWidthFt = view.findViewById<EditText>(R.id.setup_plow_width)
    private val wingLeftWidthFt = view.findViewById<EditText>(R.id.setup_wing_left_width)
    private val wingRightWidthFt = view.findViewById<EditText>(R.id.setup_wing_right_width)
    private val towWidthFt = view.findViewById<EditText>(R.id.setup_tow_width)
    private val observerOptions = view.findViewById<View>(R.id.setup_observer_options)
    private val observerLabel = view.findViewById<EditText>(R.id.setup_observer_label)
    private val presence = view.findViewById<CheckBox>(R.id.setup_presence)
    private val distress = view.findViewById<CheckBox>(R.id.setup_distress)
    private val ttsEnabled = view.findViewById<CheckBox>(R.id.setup_tts)
    private val taskingSnooze = view.findViewById<EditText>(R.id.setup_tasking_snooze)
    private val mapHud = view.findViewById<CheckBox>(R.id.setup_map_hud)
    private val conditionStale = view.findViewById<EditText>(R.id.setup_condition_stale)
    private val roadSnap = view.findViewById<CheckBox>(R.id.setup_roadsnap)
    private val roadSnapDir = view.findViewById<EditText>(R.id.setup_roadsnap_dir)
    private val btEnabled = view.findViewById<CheckBox>(R.id.setup_bt_enabled)
    private val btAddress = view.findViewById<EditText>(R.id.setup_bt_address)
    private val btBle = view.findViewById<CheckBox>(R.id.setup_bt_ble)

    init {
        view.findViewById<TextView>(R.id.setup_version).text =
            "PlowTAK ${BuildConfig.VERSION_NAME}"

        typeGroup.setOnCheckedChangeListener { _, _ -> applyVisibility() }
        roadSnap.setOnCheckedChangeListener { _, checked ->
            roadSnapDir.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Prefill from the stored profile when re-entering settings.
        val cap = controller.capabilityStore.load()
        if (controller.capabilityStore.isConfigured) {
            when (cap.type) {
                VehicleType.PLOW,
                VehicleType.SUPERVISOR,
                VehicleType.OBSERVER -> typeGroup.check(R.id.setup_type_plow)
                VehicleType.SALT_ONLY -> typeGroup.check(R.id.setup_type_salt)
            }
            callsign.setText(cap.callsign)
            vehicleId.setText(cap.vehicleId)
            hasSalt.isChecked = cap.hasSalt
            observerLabel.setText(cap.observerLabel)
            presence.isChecked = cap.publishPresence
            distress.isChecked = cap.canSendDistress
            plowWidthFt.setText(formatFeet(cap.plowWidthM))
            wingLeftWidthFt.setText(formatFeet(cap.wingLeftWidthM))
            wingRightWidthFt.setText(formatFeet(cap.wingRightWidthM))
            towWidthFt.setText(formatFeet(cap.towWidthM))
        } else {
            plowWidthFt.setText(formatFeet(VehicleCapability.DEFAULT_WIDTH_M))
            wingLeftWidthFt.setText("0")
            wingRightWidthFt.setText("0")
            towWidthFt.setText("0")
        }
        ttsEnabled.isChecked = controller.prefs.ttsEnabled
        taskingSnooze.setText(controller.prefs.taskingSnoozeMinutes.toString())
        mapHud.isChecked = controller.prefs.mapHudEnabled
        conditionStale.setText(controller.prefs.roadConditionStaleMinutes.toString())
        roadSnap.isChecked = controller.prefs.roadSnapEnabled
        roadSnapDir.setText(controller.prefs.roadSnapDir)
        roadSnapDir.visibility = if (roadSnap.isChecked) View.VISIBLE else View.GONE
        btEnabled.isChecked = controller.prefs.btEquipmentEnabled
        btAddress.setText(controller.prefs.btDeviceAddress)
        btBle.isChecked = controller.prefs.btUseBle
        applyVisibility()

        view.findViewById<Button>(R.id.setup_save).setOnClickListener { save() }
    }

    private fun selectedType(): VehicleType = when (typeGroup.checkedRadioButtonId) {
        R.id.setup_type_salt -> VehicleType.SALT_ONLY
        else -> VehicleType.PLOW
    }

    private fun applyVisibility() {
        val type = selectedType()
        treatOptions.visibility = View.VISIBLE
        // A salt-only truck always has a spreader; the checkbox is for plows.
        hasSalt.visibility = if (type == VehicleType.PLOW) View.VISIBLE else View.GONE
        observerOptions.visibility = View.GONE
    }

    private fun save() {
        val type = selectedType()
        val defaults = VehicleCapability.defaultsFor(type)

        val cap = defaults.copy(
            hasSalt = when (type) {
                VehicleType.PLOW -> hasSalt.isChecked
                VehicleType.SALT_ONLY -> true
                else -> false
            },
            canSendDistress = distress.isChecked,
            publishPresence = presence.isChecked,
            plowWidthM = if (defaults.canTreat) {
                VehicleCapability.feetToMeters(
                    plowWidthFt.text.toString().trim().toDoubleOrNull()
                        ?: (VehicleCapability.DEFAULT_WIDTH_M / VehicleCapability.FT_TO_M)
                )
            } else 0.0,
            wingLeftWidthM = if (defaults.canTreat) {
                VehicleCapability.feetToMeters(
                    wingLeftWidthFt.text.toString().trim().toDoubleOrNull() ?: 0.0
                )
            } else 0.0,
            wingRightWidthM = if (defaults.canTreat) {
                VehicleCapability.feetToMeters(
                    wingRightWidthFt.text.toString().trim().toDoubleOrNull() ?: 0.0
                )
            } else 0.0,
            towWidthM = if (defaults.canTreat) {
                VehicleCapability.feetToMeters(
                    towWidthFt.text.toString().trim().toDoubleOrNull() ?: 0.0
                )
            } else 0.0,
            callsign = callsign.text.toString().trim()
                .ifEmpty { defaultCallsign(type) },
            vehicleId = vehicleId.text.toString().trim(),
            observerLabel = observerLabel.text.toString().trim()
        )

        controller.capabilityStore.save(cap)
        // Clear wing extended state if a side was uninstalled.
        if (cap.wingLeftWidthM <= 0.0) controller.equipment.setWingLeft(false)
        if (cap.wingRightWidthM <= 0.0) controller.equipment.setWingRight(false)
        controller.prefs.ttsEnabled = ttsEnabled.isChecked
        controller.prefs.taskingSnoozeMinutes =
            taskingSnooze.text.toString().trim().toIntOrNull() ?: 15
        controller.prefs.mapHudEnabled = mapHud.isChecked
        controller.plowStatusHud.refreshVisibility()
        val staleMin = conditionStale.text.toString().trim().toIntOrNull() ?: 120
        controller.prefs.roadConditionStaleMinutes = staleMin
        // Keep a joined storm's TTL in sync and republish CoT + storm-config.
        if (controller.stormManager.activeSession() != null) {
            controller.updateStormCoverageSettings(roadConditionTtlMinutes = staleMin)
        } else {
            controller.stormManager.updateRoadConditionTtlMinutes(
                controller.prefs.roadConditionStaleMinutes
            )
        }
        controller.prefs.roadSnapEnabled = roadSnap.isChecked
        controller.prefs.roadSnapDir = roadSnapDir.text.toString().trim()
        controller.prefs.btEquipmentEnabled = btEnabled.isChecked
        controller.prefs.btDeviceAddress = btAddress.text.toString().trim()
        controller.prefs.btUseBle = btBle.isChecked
        com.atakmap.android.plowtak.prefs.PlowTakSettingsBackup.export(controller.pluginContext)
        controller.reloadRoadSnapper()
        controller.reloadBluetoothLink()
        onSaved()
    }

    private fun defaultCallsign(type: VehicleType): String = when (type) {
        VehicleType.SALT_ONLY -> "Spread-1"
        else -> "Plow-1"
    }

    companion object {
        /** Compact feet display from stored meters (whole feet when close). */
        fun formatFeet(meters: Double): String {
            if (meters <= 0.0) return "0"
            val ft = VehicleCapability.metersToFeet(meters)
            return if (abs(ft - ft.roundToInt()) < 0.05) {
                ft.roundToInt().toString()
            } else {
                String.format(java.util.Locale.US, "%.1f", ft)
            }
        }
    }
}
