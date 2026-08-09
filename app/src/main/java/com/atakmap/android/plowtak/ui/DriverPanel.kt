package com.atakmap.android.plowtak.ui

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.WidthPreset
import com.atakmap.android.plowtak.ops.TaskManager
import com.atakmap.android.plowtak.ops.ToggleSanity
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater

/**
 * Treating-unit panel: shift login, oversized blade/salt toggles (gated by
 * capability), material + width preset selectors, one-tap statuses, hazard
 * drops, road-condition quick reports, supervisor task Ack/Decline, night
 * palette, and the distress button.
 */
class DriverPanel(
    private val controller: PlowTakController,
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
    private val taskBar = view.findViewById<View>(R.id.driver_task_bar)
    private val taskText = view.findViewById<TextView>(R.id.driver_task_text)
    private val taskAck = view.findViewById<Button>(R.id.driver_task_ack)
    private val taskDecline = view.findViewById<Button>(R.id.driver_task_decline)
    private val materialTitle = view.findViewById<TextView>(R.id.driver_material_title)
    private val materialGrid = view.findViewById<GridLayout>(R.id.driver_material_grid)
    private val widthTitle = view.findViewById<TextView>(R.id.driver_width_title)
    private val widthGrid = view.findViewById<GridLayout>(R.id.driver_width_grid)
    private val statusGrid = view.findViewById<GridLayout>(R.id.driver_status_grid)
    private val hazardGrid = view.findViewById<GridLayout>(R.id.driver_hazard_grid)
    private val conditionGrid = view.findViewById<GridLayout>(R.id.driver_condition_grid)
    private val distressButton = view.findViewById<Button>(R.id.driver_distress)
    private val nightToggle = view.findViewById<ToggleButton>(R.id.driver_night_toggle)

    private val materialButtons = HashMap<Material, Button>()
    private val widthButtons = HashMap<WidthPreset, Button>()
    private var currentTask: TaskEvent? = null

    private val statusListener = com.atakmap.android.plowtak.ops.StatusManager.Listener {
        view.post { refresh() }
    }
    private val taskListener = object : TaskManager.Listener {
        override fun onTasksChanged(tasks: List<TaskEvent>) {
            view.post { refresh() }
        }
        override fun onLocalTransition(task: TaskEvent) {}
        override fun onEscalated(task: TaskEvent) {}
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

        taskAck.setOnClickListener {
            currentTask?.let { controller.ackTask(it.uid) }
        }
        taskDecline.setOnClickListener {
            currentTask?.let { controller.declineTask(it.uid) }
        }

        nightToggle.isChecked = controller.prefs.nightMode
        nightToggle.setOnCheckedChangeListener { _, checked ->
            controller.prefs.nightMode = checked
            NightPalette.apply(view, checked)
            refresh()
        }

        buildMaterialGrid(cap.hasSalt)
        buildWidthGrid()
        buildStatusGrid()
        buildHazardGrid()
        buildConditionGrid()

        controller.statusManager.addListener(statusListener)
        controller.taskManager.addListener(taskListener)
        controller.statusManager.suggestionListener =
            com.atakmap.android.plowtak.ops.StatusManager.SuggestionListener { status, reason ->
                view.post {
                    Toast.makeText(
                        controller.mapView.context,
                        "At $reason — set status ${status.label}? Tap it below.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        // Forgot-to-toggle prompts surface here as a dialog. Prompts only —
        // the buttons below stay the single source of equipment truth.
        controller.sanityPromptListener = { prompt -> view.post { showSanityPrompt(prompt) } }

        if (controller.prefs.nightMode) NightPalette.apply(view, true)
        refresh()
    }

    fun dispose() {
        controller.statusManager.removeListener(statusListener)
        controller.taskManager.removeListener(taskListener)
        controller.statusManager.suggestionListener = null
        controller.sanityPromptListener = null
    }

    fun refresh() {
        val cap = controller.capabilityStore.load()
        val shift = controller.shiftLog.currentShift
        val widthM = cap.widthFor(controller.equipment.state.widthPreset)

        header.text = "${cap.callsign}  (${cap.type.wireName}, $widthM m)"
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

        refreshTaskBar()
        refreshMaterialSelection()
        refreshWidthSelection()

        val myAlert = controller.alertManager
            .get(AlertEvent.makeUid(controller.selfUid()))
        distressButton.text = view.context.getString(
            if (myAlert != null && myAlert.state != AlertState.CLEARED)
                R.string.driver_distress_clear
            else
                R.string.driver_distress
        )
    }

    // -------------------------------------------------------------- tasks

    private fun refreshTaskBar() {
        val pending = controller.taskManager
            .pendingFor(controller.selfUid())
            .firstOrNull()
        currentTask = pending
        if (pending == null) {
            taskBar.visibility = View.GONE
            return
        }
        taskBar.visibility = View.VISIBLE
        taskText.text = "TASK from ${pending.assignedBy}: " +
                pending.description.ifEmpty { pending.kind.label }
    }

    // ---------------------------------------------------- sanity prompts

    private fun showSanityPrompt(prompt: ToggleSanity.Prompt) {
        // SDK-fixup: AlertDialog over the map may need mapView.context and
        // TYPE_APPLICATION_OVERLAY handling on some ATAK builds.
        AlertDialog.Builder(controller.mapView.context)
            .setTitle("PlowTAK check")
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -------------------------------------------------------------- grids

    private fun buildMaterialGrid(hasSalt: Boolean) {
        materialGrid.removeAllViews()
        materialButtons.clear()
        val show = hasSalt
        materialTitle.visibility = if (show) View.VISIBLE else View.GONE
        materialGrid.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        for (material in Material.entries) {
            val button = Button(controller.pluginContext)
            button.text = material.label
            button.textSize = 14f
            button.setTextColor(Color.WHITE)
            button.setOnClickListener {
                controller.equipment.setMaterial(material)
                refresh()
            }
            materialButtons[material] = button
            materialGrid.addView(button, gridCell())
        }
    }

    private fun refreshMaterialSelection() {
        val selected = controller.equipment.state.material
        for ((material, button) in materialButtons) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (material == selected) TOGGLE_ON else TOGGLE_OFF
            )
        }
    }

    private fun buildWidthGrid() {
        widthGrid.removeAllViews()
        widthButtons.clear()
        val cap = controller.capabilityStore.load()
        val presets = cap.availablePresets()
        val show = cap.canTreat && presets.size > 1
        widthTitle.visibility = if (show) View.VISIBLE else View.GONE
        widthGrid.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        for (preset in presets) {
            val button = Button(controller.pluginContext)
            button.text = "${preset.label}\n${cap.widthFor(preset)} m"
            button.textSize = 14f
            button.setTextColor(Color.WHITE)
            button.setOnClickListener {
                controller.equipment.setWidthPreset(preset)
                refresh()
            }
            widthButtons[preset] = button
            widthGrid.addView(button, gridCell())
        }
    }

    private fun refreshWidthSelection() {
        val selected = controller.equipment.state.widthPreset
        for ((preset, button) in widthButtons) {
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (preset == selected) TOGGLE_ON else TOGGLE_OFF
            )
        }
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
            button.setOnLongClickListener {
                reportHazardWithPhoto(hazard)
                true
            }
            hazardGrid.addView(button, gridCell())
        }
    }

    /** Long-press hazard: ATAK QuickPic -> publish with photo attachment. */
    private fun reportHazardWithPhoto(hazard: HazardType) {
        if (controller.lastPosition == null) {
            Toast.makeText(
                controller.mapView.context, "No GPS position yet", Toast.LENGTH_SHORT
            ).show()
            return
        }
        controller.requestHazardWithQuickPic(hazard)
        Toast.makeText(
            controller.mapView.context,
            "QuickPic: capture a photo for ${hazard.label}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun buildConditionGrid() {
        conditionGrid.removeAllViews()
        for (condition in RoadCondition.entries) {
            val button = Button(controller.pluginContext)
            button.text = condition.label
            button.textSize = 14f
            button.setTextColor(Color.WHITE)
            button.backgroundTintList =
                android.content.res.ColorStateList.valueOf(CONDITION_COLOR)
            button.setOnClickListener {
                controller.reportRoadCondition(condition)
                Toast.makeText(
                    controller.mapView.context,
                    "Road condition: ${condition.label}", Toast.LENGTH_SHORT
                ).show()
            }
            conditionGrid.addView(button, gridCell())
        }
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
        val myUid = AlertEvent.makeUid(controller.selfUid())
        val existing = controller.alertManager.get(myUid)
        if (existing != null && existing.state != AlertState.CLEARED) {
            controller.clearOwnDistress()
        } else {
            controller.sendDistress()
        }
        refresh()
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
        private const val CONDITION_COLOR = 0xFF37474F.toInt()
    }
}
