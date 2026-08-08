package com.atakmap.android.plowtak.ui

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleType

/**
 * First-run / settings flow: pick the vehicle type, then sub-options
 * (plow width presets, hasSalt, observer label, presence, distress).
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
    private val widthSpinner = view.findViewById<Spinner>(R.id.setup_width_spinner)
    private val wingSpinner = view.findViewById<Spinner>(R.id.setup_wing_spinner)
    private val towSpinner = view.findViewById<Spinner>(R.id.setup_tow_spinner)
    private val observerOptions = view.findViewById<View>(R.id.setup_observer_options)
    private val observerLabel = view.findViewById<EditText>(R.id.setup_observer_label)
    private val presence = view.findViewById<CheckBox>(R.id.setup_presence)
    private val distress = view.findViewById<CheckBox>(R.id.setup_distress)
    private val ttsEnabled = view.findViewById<CheckBox>(R.id.setup_tts)
    private val roadSnap = view.findViewById<CheckBox>(R.id.setup_roadsnap)
    private val roadSnapDir = view.findViewById<EditText>(R.id.setup_roadsnap_dir)

    private val widthLabels = listOf(
        "8 ft (2.4 m)", "10 ft (3.0 m)", "12 ft (3.7 m)",
        "Wing 16 ft (4.9 m)", "Tow 26 ft (7.9 m)"
    )

    /** Wing / tow preset options; index 0 = not fitted (disables the preset). */
    private val wingOptionsM = listOf(0.0, 4.9, 5.5)
    private val wingLabels = listOf("Not fitted", "16 ft (4.9 m)", "18 ft (5.5 m)")
    private val towOptionsM = listOf(0.0, 7.9, 8.5)
    private val towLabels = listOf("Not fitted", "26 ft (7.9 m)", "28 ft (8.5 m)")

    init {
        widthSpinner.adapter = ArrayAdapter(
            controller.pluginContext,
            android.R.layout.simple_spinner_dropdown_item,
            widthLabels
        )
        wingSpinner.adapter = ArrayAdapter(
            controller.pluginContext,
            android.R.layout.simple_spinner_dropdown_item,
            wingLabels
        )
        towSpinner.adapter = ArrayAdapter(
            controller.pluginContext,
            android.R.layout.simple_spinner_dropdown_item,
            towLabels
        )

        typeGroup.setOnCheckedChangeListener { _, _ -> applyVisibility() }
        roadSnap.setOnCheckedChangeListener { _, checked ->
            roadSnapDir.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Prefill from the stored profile when re-entering settings.
        val cap = controller.capabilityStore.load()
        if (controller.capabilityStore.isConfigured) {
            when (cap.type) {
                VehicleType.PLOW -> typeGroup.check(R.id.setup_type_plow)
                VehicleType.SALT_ONLY -> typeGroup.check(R.id.setup_type_salt)
                VehicleType.SUPERVISOR -> typeGroup.check(R.id.setup_type_supervisor)
                VehicleType.OBSERVER -> typeGroup.check(R.id.setup_type_observer)
            }
            callsign.setText(cap.callsign)
            vehicleId.setText(cap.vehicleId)
            hasSalt.isChecked = cap.hasSalt
            observerLabel.setText(cap.observerLabel)
            presence.isChecked = cap.publishPresence
            distress.isChecked = cap.canSendDistress
            val presetIndex = VehicleCapability.WIDTH_PRESETS_M
                .indexOfFirst { Math.abs(it - cap.plowWidthM) < 0.01 }
            widthSpinner.setSelection(if (presetIndex >= 0) presetIndex else 1)
            wingSpinner.setSelection(nearestIndex(wingOptionsM, cap.wingWidthM))
            towSpinner.setSelection(nearestIndex(towOptionsM, cap.towWidthM))
        } else {
            widthSpinner.setSelection(1) // 10 ft default
        }
        ttsEnabled.isChecked = controller.prefs.ttsEnabled
        roadSnap.isChecked = controller.prefs.roadSnapEnabled
        roadSnapDir.setText(controller.prefs.roadSnapDir)
        roadSnapDir.visibility = if (roadSnap.isChecked) View.VISIBLE else View.GONE
        applyVisibility()

        view.findViewById<Button>(R.id.setup_save).setOnClickListener { save() }
    }

    private fun nearestIndex(options: List<Double>, value: Double): Int {
        val i = options.indexOfFirst { Math.abs(it - value) < 0.01 }
        return if (i >= 0) i else 0
    }

    private fun selectedType(): VehicleType = when (typeGroup.checkedRadioButtonId) {
        R.id.setup_type_salt -> VehicleType.SALT_ONLY
        R.id.setup_type_supervisor -> VehicleType.SUPERVISOR
        R.id.setup_type_observer -> VehicleType.OBSERVER
        else -> VehicleType.PLOW
    }

    private fun applyVisibility() {
        val type = selectedType()
        treatOptions.visibility =
            if (type == VehicleType.PLOW || type == VehicleType.SALT_ONLY) View.VISIBLE
            else View.GONE
        // A salt-only truck always has a spreader; the checkbox is for plows.
        hasSalt.visibility = if (type == VehicleType.PLOW) View.VISIBLE else View.GONE
        observerOptions.visibility =
            if (type == VehicleType.OBSERVER) View.VISIBLE else View.GONE
    }

    private fun save() {
        val type = selectedType()
        val defaults = VehicleCapability.defaultsFor(type)
        val widthIndex = widthSpinner.selectedItemPosition
            .coerceIn(0, VehicleCapability.WIDTH_PRESETS_M.size - 1)

        val cap = defaults.copy(
            hasSalt = when (type) {
                VehicleType.PLOW -> hasSalt.isChecked
                VehicleType.SALT_ONLY -> true
                else -> false
            },
            canSendDistress = distress.isChecked,
            publishPresence = presence.isChecked,
            plowWidthM = if (defaults.canTreat)
                VehicleCapability.WIDTH_PRESETS_M[widthIndex]
            else 0.0,
            wingWidthM = if (defaults.canTreat)
                wingOptionsM[wingSpinner.selectedItemPosition.coerceIn(0, wingOptionsM.size - 1)]
            else 0.0,
            towWidthM = if (defaults.canTreat)
                towOptionsM[towSpinner.selectedItemPosition.coerceIn(0, towOptionsM.size - 1)]
            else 0.0,
            callsign = callsign.text.toString().trim()
                .ifEmpty { defaultCallsign(type) },
            vehicleId = vehicleId.text.toString().trim(),
            observerLabel = observerLabel.text.toString().trim()
        )

        controller.capabilityStore.save(cap)
        controller.prefs.ttsEnabled = ttsEnabled.isChecked
        controller.prefs.roadSnapEnabled = roadSnap.isChecked
        controller.prefs.roadSnapDir = roadSnapDir.text.toString().trim()
        controller.reloadRoadSnapper()
        onSaved()
    }

    private fun defaultCallsign(type: VehicleType): String = when (type) {
        VehicleType.PLOW -> "Plow-1"
        VehicleType.SALT_ONLY -> "Salt-1"
        VehicleType.SUPERVISOR -> "Sup-1"
        VehicleType.OBSERVER -> "Observer-1"
    }
}
