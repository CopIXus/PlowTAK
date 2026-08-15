package com.atakmap.android.plowtak.ui

import android.content.Context
import android.content.Intent
import android.view.View
import com.atakmap.android.dropdown.DropDown.OnStateListener
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.maps.MapView

/**
 * Drop-down host. First run shows vehicle setup; afterward every role uses
 * the unified ops panel (DriverPanel). Storm admin and vehicle config live
 * under the header Settings gear.
 */
class PlowTakDropDownReceiver(
    mapView: MapView,
    private val pluginContext: Context,
    private val controller: PlowTakController
) : DropDownReceiver(mapView), OnStateListener {

    private var opsPanel: DriverPanel? = null
    private var taskingPanel: TaskingPanel? = null

    override fun disposeImpl() {
        disposePanels()
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SHOW_PLUGIN -> show(selectView())
        }
    }

    private fun show(view: View) {
        showDropDown(
            view,
            HALF_WIDTH, FULL_HEIGHT,
            FULL_WIDTH, HALF_HEIGHT,
            false, this
        )
    }

    private fun selectView(): View {
        if (!controller.capabilityStore.isConfigured) {
            return SetupPanel(controller) { show(selectView()) }.view
        }
        disposePanels()
        return DriverPanel(controller, ::openSettings, ::openStorm, ::openTasks)
            .also { opsPanel = it }.view
    }

    private fun openSettings() {
        show(SetupPanel(controller) { show(selectView()) }.view)
    }

    private fun openStorm() {
        show(StormPanel(controller) { show(selectView()) }.view)
    }

    private fun openTasks() {
        taskingPanel?.dispose()
        taskingPanel = TaskingPanel(controller) { show(selectView()) }
        show(taskingPanel!!.view)
    }

    private fun disposePanels() {
        opsPanel?.dispose()
        opsPanel = null
        taskingPanel?.dispose()
        taskingPanel = null
    }

    override fun onDropDownSelectionRemoved() {}

    override fun onDropDownVisible(visible: Boolean) {
        if (visible) {
            opsPanel?.refresh()
            taskingPanel?.refresh()
        }
        controller.plowStatusHud.setPanelOpen(visible)
    }

    override fun onDropDownSizeChanged(width: Double, height: Double) {}

    override fun onDropDownClose() {
        controller.plowStatusHud.setPanelOpen(false)
    }

    companion object {
        const val SHOW_PLUGIN = "com.atakmap.android.plowtak.SHOW"
    }
}
