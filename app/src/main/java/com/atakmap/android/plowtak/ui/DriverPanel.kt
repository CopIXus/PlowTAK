package com.atakmap.android.plowtak.ui

import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.ops.TaskManager
import com.atakmap.android.plowtak.ops.ToggleSanity
import com.atakmap.android.plowtak.plugin.PluginLayoutInflater

/**
 * Ops panel: shift, plow/wings/spreader, materials, status, hazards, Mayday.
 * Storm join/start and vehicle setup live on the header icons.
 *
 * Action grids use [OpsTileGrid] so tiles stay equal-sized and reflow like
 * ATAK Tools when the panel width changes (fold / rotation).
 */
class DriverPanel(
    private val controller: PlowTakController,
    private val onOpenSettings: () -> Unit,
    private val onOpenStorm: () -> Unit,
    private val onOpenTasks: () -> Unit = {}
) {

    val view: View = PluginLayoutInflater.inflate(
        controller.pluginContext, R.layout.panel_driver, null
    )

    private val header = view.findViewById<TextView>(R.id.driver_header)
    private val statusLine = view.findViewById<TextView>(R.id.driver_status_line)
    private val paintLine = view.findViewById<TextView>(R.id.driver_paint_line)
    private val shiftForm = view.findViewById<View>(R.id.driver_shift_form)
    private val operatorName = view.findViewById<EditText>(R.id.driver_operator_name)
    private val operatorId = view.findViewById<EditText>(R.id.driver_operator_id)
    private val shiftButton = view.findViewById<Button>(R.id.driver_shift_button)
    private val plowControl = view.findViewById<PlowControlView>(R.id.driver_plow_control)
    private val taskBar = view.findViewById<View>(R.id.driver_task_bar)
    private val taskText = view.findViewById<TextView>(R.id.driver_task_text)
    private val taskAck = view.findViewById<Button>(R.id.driver_task_ack)
    private val taskDecline = view.findViewById<Button>(R.id.driver_task_decline)
    private val materialTitle = view.findViewById<TextView>(R.id.driver_material_title)
    private val materialGrid = view.findViewById<OpsTileGrid>(R.id.driver_material_grid)
    private val statusGrid = view.findViewById<OpsTileGrid>(R.id.driver_status_grid)
    private val hazardGrid = view.findViewById<OpsTileGrid>(R.id.driver_hazard_grid)
    private val conditionGrid = view.findViewById<OpsTileGrid>(R.id.driver_condition_grid)
    private val distressButton = view.findViewById<Button>(R.id.driver_distress)

    private val materialButtons = HashMap<Material, FrameLayout>()
    private val statusButtons = HashMap<VehicleStatus?, FrameLayout>()
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

        plowControl.visibility =
            if (cap.hasBlade || cap.hasSalt) View.VISIBLE else View.GONE
        plowControl.compact = false
        plowControl.spreaderEnabled = cap.hasSalt
        plowControl.towAvailable = cap.hasBlade && cap.towWidthM > 0.0
        plowControl.wingLeftAvailable = cap.hasBlade && cap.wingLeftWidthM > 0.0
        plowControl.wingRightAvailable = cap.hasBlade && cap.wingRightWidthM > 0.0
        distressButton.visibility = if (cap.canSendDistress) View.VISIBLE else View.GONE

        plowControl.onBladeToggle = { down ->
            controller.equipment.setBladeDown(down)
            refresh()
        }
        plowControl.onWingLeftToggle = { ext ->
            controller.equipment.setWingLeft(ext)
            refresh()
        }
        plowControl.onWingRightToggle = { ext ->
            controller.equipment.setWingRight(ext)
            refresh()
        }
        plowControl.onTowToggle = { deployed ->
            controller.equipment.setTowDeployed(deployed)
            refresh()
        }
        plowControl.onSpreaderToggle = { on ->
            controller.equipment.setSpreading(on)
            refresh()
        }

        operatorName.setText(controller.prefs.lastOperatorName)
        operatorId.setText(controller.prefs.lastOperatorId)

        shiftButton.setOnClickListener { toggleShift() }
        distressButton.setOnClickListener { onDistress() }
        view.findViewById<View>(R.id.driver_settings_btn).setOnClickListener { onOpenSettings() }
        view.findViewById<View>(R.id.driver_storm_btn).setOnClickListener { onOpenStorm() }
        view.findViewById<View>(R.id.driver_tasks_btn).setOnClickListener { onOpenTasks() }

        taskAck.setOnClickListener {
            currentTask?.let { controller.ackTask(it.uid) }
        }
        taskDecline.setOnClickListener {
            currentTask?.let { controller.declineTask(it.uid) }
        }

        applyTopIcon(distressButton, R.drawable.ic_ops_mayday, dp(24))
        distressButton.compoundDrawablePadding = dp(4)

        // ATAK Loadout Tools: 80dp cells, zero gutters, width-driven columns.
        val opsGrids = listOf(materialGrid, statusGrid, hazardGrid, conditionGrid)
        for (grid in opsGrids) {
            grid.cellDp = 80
        }
        // Fold / dropdown width changes: force every ops grid to reflow together.
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val w = right - left
            val oldW = oldRight - oldLeft
            if (w > 0 && w != oldW) {
                for (grid in opsGrids) grid.requestLayout()
            }
        }

        buildMaterialGrid(cap.hasSalt)
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
        controller.sanityPromptListener = { prompt -> view.post { showSanityPrompt(prompt) } }

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
        val eq = controller.equipment.state
        val widthM = cap.widthFor(eq.effectiveWidthPreset())

        val unit = cap.callsign.ifBlank { "Unit ?" }
        val truck = cap.vehicleId.ifBlank { "Truck ?" }
        header.text = "$unit  ·  $truck  ($widthM m)"
        plowControl.compact = false
        plowControl.spreaderEnabled = cap.hasSalt
        plowControl.towAvailable = cap.hasBlade && cap.towWidthM > 0.0
        plowControl.wingLeftAvailable = cap.hasBlade && cap.wingLeftWidthM > 0.0
        plowControl.wingRightAvailable = cap.hasBlade && cap.wingRightWidthM > 0.0
        plowControl.visibility =
            if (cap.hasBlade || cap.hasSalt) View.VISIBLE else View.GONE

        val storm = controller.stormManager.activeSession()
        statusLine.text = "Status: ${controller.statusManager.current.label}" +
                (storm?.let { "  •  ${it.displayName()}" } ?: "  •  No storm selected")
        paintLine.text = controller.currentPaintStatus().reason

        val onShift = shift != null
        shiftForm.visibility = if (onShift) View.GONE else View.VISIBLE
        shiftButton.text = if (onShift)
            "${view.context.getString(R.string.driver_shift_end)} (${shift!!.operatorName})"
        else
            view.context.getString(R.string.driver_shift_start)

        plowControl.isEnabled = onShift
        plowControl.bind(eq)

        refreshTaskBar()
        refreshMaterialSelection()
        refreshStatusSelection()

        val myAlert = controller.alertManager
            .get(AlertEvent.makeUid(controller.selfUid()))
        distressButton.text = view.context.getString(
            if (myAlert != null && myAlert.state != AlertState.CLEARED)
                R.string.driver_distress_clear
            else
                R.string.driver_distress
        )
    }

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

    private fun showSanityPrompt(prompt: ToggleSanity.Prompt) {
        // Overspeed uses map HUD flash + TTS only (handled in controller).
        if (prompt.type == ToggleSanity.PromptType.CONFIRM_SPEED) return
        AlertDialog.Builder(controller.mapView.context)
            .setTitle("PlowTAK check")
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildMaterialGrid(hasSalt: Boolean) {
        materialGrid.removeAllViews()
        materialButtons.clear()
        materialTitle.visibility = if (hasSalt) View.VISIBLE else View.GONE
        materialGrid.visibility = if (hasSalt) View.VISIBLE else View.GONE
        if (!hasSalt) return
        for (material in Material.entries) {
            val tile = OpsTileGrid.createTile(
                controller.pluginContext,
                material.label,
                materialIcon(material),
                OpsTileGrid.FILL_IDLE
            ) {
                controller.equipment.setMaterial(material)
                refresh()
            }
            materialButtons[material] = tile
            materialGrid.addView(tile)
        }
    }

    private fun refreshMaterialSelection() {
        val selected = controller.equipment.state.material
        for ((material, tile) in materialButtons) {
            OpsTileGrid.tintTile(
                tile,
                if (material == selected) OpsTileGrid.FILL_SELECTED else OpsTileGrid.FILL_IDLE
            )
        }
    }

    private fun buildStatusGrid() {
        statusGrid.removeAllViews()
        statusButtons.clear()
        val options = VehicleStatus.MANUAL_OPTIONS + listOf<VehicleStatus?>(null)
        for (status in options) {
            val label = status?.label?.uppercase()
                ?: view.context.getString(R.string.driver_status_driving)
            val tile = OpsTileGrid.createTile(
                controller.pluginContext,
                label,
                statusIcon(status),
                OpsTileGrid.FILL_IDLE
            ) {
                if (status == null) controller.statusManager.clearManual()
                else controller.statusManager.setManual(status)
                refresh()
            }
            statusButtons[status] = tile
            statusGrid.addView(tile)
        }
    }

    private fun refreshStatusSelection() {
        val current = controller.statusManager.current
        val selectedKey: VehicleStatus? =
            if (current in VehicleStatus.MANUAL_OPTIONS) current else null
        for ((status, tile) in statusButtons) {
            OpsTileGrid.tintTile(
                tile,
                if (status == selectedKey) OpsTileGrid.FILL_SELECTED else OpsTileGrid.FILL_IDLE
            )
        }
    }

    private fun buildHazardGrid() {
        hazardGrid.removeAllViews()
        for (hazard in HazardType.entries) {
            val tile = OpsTileGrid.createTile(
                controller.pluginContext,
                hazard.label,
                hazardIcon(hazard),
                OpsTileGrid.FILL_HAZARD
            ) {
                controller.reportHazard(hazard)
                Toast.makeText(
                    controller.mapView.context,
                    "${hazard.label} reported", Toast.LENGTH_SHORT
                ).show()
            }
            tile.setOnLongClickListener {
                reportHazardWithPhoto(hazard)
                true
            }
            hazardGrid.addView(tile)
        }
    }

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
            val tile = OpsTileGrid.createTile(
                controller.pluginContext,
                condition.label,
                conditionIcon(condition),
                OpsTileGrid.FILL_CONDITION
            ) {
                controller.reportRoadCondition(condition)
                Toast.makeText(
                    controller.mapView.context,
                    "Road condition: ${condition.label}", Toast.LENGTH_SHORT
                ).show()
            }
            conditionGrid.addView(tile)
        }
    }

    private fun toggleShift() {
        if (controller.shiftLog.isOnShift) {
            controller.equipment.setBladeDown(false)
            controller.equipment.setSpreading(false)
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
            controller.prefs.lastOperatorName = name
            controller.prefs.lastOperatorId = id
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

    private fun applyTopIcon(button: TextView, iconRes: Int, sizePx: Int = dp(28)) {
        val d = scaledIcon(iconRes, sizePx) ?: return
        button.setCompoundDrawables(null, d, null, null)
    }

    private fun scaledIcon(iconRes: Int, sizePx: Int): Drawable? {
        val ctx = controller.pluginContext
        val d = ctx.resources.getDrawable(iconRes, ctx.theme)?.mutate() ?: return null
        d.setBounds(0, 0, sizePx, sizePx)
        return d
    }

    private fun materialIcon(material: Material): Int = when (material) {
        Material.SALT -> R.drawable.ic_ops_salt
        Material.SAND -> R.drawable.ic_ops_sand
        Material.GRAVEL -> R.drawable.ic_ops_gravel
        Material.BRINE -> R.drawable.ic_ops_brine
        Material.PREWET -> R.drawable.ic_ops_prewet
    }

    private fun statusIcon(status: VehicleStatus?): Int = when (status) {
        VehicleStatus.LOADING -> R.drawable.ic_ops_loading
        VehicleStatus.REFUELING -> R.drawable.ic_ops_refueling
        VehicleStatus.ON_BREAK -> R.drawable.ic_ops_on_break
        VehicleStatus.OUT_OF_SERVICE -> R.drawable.ic_ops_oos
        null -> R.drawable.ic_ops_driving
        else -> R.drawable.ic_ops_driving
    }

    private fun hazardIcon(hazard: HazardType): Int = when (hazard) {
        HazardType.STRANDED_VEHICLE -> R.drawable.ic_ops_stranded
        HazardType.TREE_WIRES_DOWN -> R.drawable.ic_ops_tree_wires
        HazardType.ABANDONED_CAR -> R.drawable.ic_ops_abandoned
        HazardType.DRIFT_ICE -> R.drawable.ic_ops_drift_ice
        HazardType.DAMAGE -> R.drawable.ic_ops_damage
    }

    private fun conditionIcon(condition: RoadCondition): Int = when (condition) {
        RoadCondition.BARE -> R.drawable.ic_ops_bare
        RoadCondition.WET -> R.drawable.ic_ops_wet
        RoadCondition.SLUSH -> R.drawable.ic_ops_slush
        RoadCondition.SNOW_COVERED -> R.drawable.ic_ops_snow
        RoadCondition.ICE -> R.drawable.ic_ops_ice
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            controller.pluginContext.resources.displayMetrics
        ).toInt()

}
