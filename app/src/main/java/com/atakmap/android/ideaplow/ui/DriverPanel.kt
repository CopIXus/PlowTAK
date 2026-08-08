package com.atakmap.android.ideaplow.ui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import com.atakmap.android.ideaplow.IdeaPlowController
import com.atakmap.android.ideaplow.R
import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.AlertState
import com.atakmap.android.ideaplow.model.HazardType
import com.atakmap.android.ideaplow.model.VehicleStatus
import com.atakmap.android.ideaplow.plugin.PluginLayoutInflater

/**
 * Treating-unit panel: shift login, oversized blade/salt toggles (gated by
 * capability), one-tap statuses, hazard drops, and the distress button.
 */
class DriverPanel(
    private val controller: IdeaPlowController,
    private val onOpenSettings: () -> Unit
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_driver, null
    )

    private val header = view.findViewById<TextView>(R.id.driver_header)
    private val statusLine = view.findViewById<TextView>(R.id.driver_status_line)
    private val shiftForm = view.findViewById<View>(R.id.driver_shift_form)
    private val operatorName = view.findViewById<EditText>(R.id.driver_operator_name)
    private val operatorId = view.findViewById<EditText>(R.id.driver_operator_id)
    private val shiftButton = view.findViewById<Button>(R.id.driver_shift_button)
    private val bladeToggle = view.findViewById<ToggleButton>(R.id.driver_blade_toggle)
    private val saltToggle = view.findViewById<ToggleButton>(R.id.driver_salt_toggle)
    private val statusGrid = view.findViewById<GridLayout>(R.id.driver_status_grid)
    private val hazardGrid = view.findViewById<GridLayout>(R.id.driver_hazard_grid)
    private val distressButton = view.findViewById<Button>(R.id.driver_distress)

    private val statusListener = com.atakmap.android.ideaplow.ops.StatusManager.Listener {
        view.post { refresh() }
    }

    init {
        val cap = controller.capabilityStore.load()

        // Capability gating: only equipped channels get a toggle.
        bladeToggle.visibility = if (cap.hasBlade) View.VISIBLE else View.GONE
        saltToggle.visibility = if (cap.hasSalt) View.VISIBLE else View.GONE
        distressButton.visibility = if (cap.canSendDistress) View.VISIBLE else View.GONE

        bladeToggle.setOnCheckedChangeListener { _, checked ->
            controller.equipment.setBladeDown(checked)
            refresh()
        }
        saltToggle.setOnCheckedChangeListener { _, checked ->
            controller.equipment.setSaltOn(checked)
            refresh()
        }

        shiftButton.setOnClickListener { toggleShift() }
        distressButton.setOnClickListener { onDistress() }
        view.findViewById<Button>(R.id.driver_settings).setOnClickListener { onOpenSettings() }

        buildStatusGrid()
        buildHazardGrid()

        controller.statusManager.addListener(statusListener)
        controller.statusManager.suggestionListener =
            com.atakmap.android.ideaplow.ops.StatusManager.SuggestionListener { status, reason ->
                view.post {
                    Toast.makeText(
                        controller.mapView.context,
                        "At $reason — set status ${status.label}? Tap it below.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        refresh()
    }

    fun dispose() {
        controller.statusManager.removeListener(statusListener)
        controller.statusManager.suggestionListener = null
    }

    fun refresh() {
        val cap = controller.capabilityStore.load()
        val shift = controller.shiftLog.currentShift

        header.text = "${cap.callsign}  (${cap.type.wireName}, ${cap.plowWidthM} m)"
        statusLine.text = "Status: ${controller.statusManager.current.label}" +
                (controller.stormManager.activeStormId
                    .takeIf { it.isNotEmpty() }?.let { "  •  Storm $it" } ?: "  •  No storm session")

        val onShift = shift != null
        shiftForm.visibility = if (onShift) View.GONE else View.VISIBLE
        shiftButton.text = if (onShift)
            "${view.context.getString(R.string.driver_shift_end)} (${shift!!.operatorName})"
        else
            view.context.getString(R.string.driver_shift_start)

        // Toggles only matter on shift.
        bladeToggle.isEnabled = onShift
        saltToggle.isEnabled = onShift

        bladeToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (bladeToggle.isChecked) TOGGLE_ON else TOGGLE_OFF
        )
        saltToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (saltToggle.isChecked) TOGGLE_ON else TOGGLE_OFF
        )

        val myAlert = controller.alertManager
            .get(AlertEvent.makeUid(controller.capabilityStore.vehicleUid))
        distressButton.text = view.context.getString(
            if (myAlert != null && myAlert.state != AlertState.CLEARED)
                R.string.driver_distress_clear
            else
                R.string.driver_distress
        )
    }

    private fun toggleShift() {
        if (controller.shiftLog.isOnShift) {
            // Drop equipment to safe state on shift end.
            bladeToggle.isChecked = false
            saltToggle.isChecked = false
            controller.shiftLog.endShift(System.currentTimeMillis())
        } else {
            val name = operatorName.text.toString().trim()
            val id = operatorId.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(
                    controller.mapView.context, "Enter operator name", Toast.LENGTH_SHORT
                ).show()
                return
            }
            controller.shiftLog.startShift(name, id.ifEmpty { name }, System.currentTimeMillis())
        }
        refresh()
    }

    private fun onDistress() {
        val myUid = AlertEvent.makeUid(controller.capabilityStore.vehicleUid)
        val existing = controller.alertManager.get(myUid)
        if (existing != null && existing.state != AlertState.CLEARED) {
            controller.clearOwnDistress()
        } else {
            controller.sendDistress()
        }
        refresh()
    }

    private fun buildStatusGrid() {
        statusGrid.removeAllViews()
        val options = VehicleStatus.MANUAL_OPTIONS + listOf<VehicleStatus?>(null)
        for (status in options) {
            val button = Button(controller.pluginContext)
            button.text = status?.label?.uppercase()
                ?: view.context.getString(R.string.driver_status_driving)
            button.textSize = 16f
            button.setTextColor(Color.WHITE)
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (status == null) TOGGLE_ON else TOGGLE_OFF
            )
            button.setOnClickListener {
                if (status == null) controller.statusManager.clearManual()
                else controller.statusManager.setManual(status)
                refresh()
            }
            statusGrid.addView(button, gridCell())
        }
    }

    private fun buildHazardGrid() {
        hazardGrid.removeAllViews()
        for (hazard in HazardType.entries) {
            val button = Button(controller.pluginContext)
            button.text = hazard.label
            button.textSize = 14f
            button.setTextColor(Color.WHITE)
            button.backgroundTintList =
                android.content.res.ColorStateList.valueOf(HAZARD_COLOR)
            button.setOnClickListener {
                controller.reportHazard(hazard)
                Toast.makeText(
                    controller.mapView.context,
                    "${hazard.label} reported", Toast.LENGTH_SHORT
                ).show()
            }
            hazardGrid.addView(button, gridCell())
        }
    }

    private fun gridCell(): GridLayout.LayoutParams {
        val lp = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        )
        lp.width = 0
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.setMargins(4, 4, 4, 4)
        return lp
    }

    companion object {
        private const val TOGGLE_ON = 0xFF2E7D32.toInt()
        private const val TOGGLE_OFF = 0xFF455A64.toInt()
        private const val HAZARD_COLOR = 0xFF6D4C41.toInt()
    }
}
