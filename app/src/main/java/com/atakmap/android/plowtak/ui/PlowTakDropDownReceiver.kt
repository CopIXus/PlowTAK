package com.atakmap.android.plowtak.ui

import android.content.Context
import android.content.Intent
import android.view.View
import com.atakmap.android.dropdown.DropDown.OnStateListener
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.plowtak.PlowTakController
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.maps.MapView

/**
 * Capability-aware drop-down. First run shows the vehicle setup flow; after
 * that the panel is selected from the stored capability at runtime:
 * Plow/SaltOnly → DriverPanel, Supervisor → SupervisorPanel,
 * Observer → ObserverPanel.
 */
class PlowTakDropDownReceiver(
    mapView: MapView,
    private val pluginContext: Context,
    private val controller: PlowTakController
) : DropDownReceiver(mapView), OnStateListener {

    private var driverPanel: DriverPanel? = null
    private var supervisorPanel: SupervisorPanel? = null
    private var observerPanel: ObserverPanel? = null

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
        return when (controller.capabilityStore.load().type) {
            VehicleType.PLOW, VehicleType.SALT_ONLY -> {
                driverPanel?.dispose()
                DriverPanel(controller, ::openSettings).also { driverPanel = it }.view
            }
            VehicleType.SUPERVISOR -> {
                supervisorPanel?.dispose()
                SupervisorPanel(controller, ::openSettings).also { supervisorPanel = it }.view
            }
            VehicleType.OBSERVER -> {
                observerPanel?.dispose()
                ObserverPanel(controller, ::openSettings).also { observerPanel = it }.view
            }
        }
    }

    private fun openSettings() {
        show(SetupPanel(controller) { show(selectView()) }.view)
    }

    private fun disposePanels() {
        driverPanel?.dispose(); driverPanel = null
        supervisorPanel?.dispose(); supervisorPanel = null
        observerPanel?.dispose(); observerPanel = null
    }

    override fun onDropDownSelectionRemoved() {}

    override fun onDropDownVisible(visible: Boolean) {
        if (visible) {
            driverPanel?.refresh()
            supervisorPanel?.refresh()
            observerPanel?.refresh()
        }
    }

    override fun onDropDownSizeChanged(width: Double, height: Double) {}

    override fun onDropDownClose() {}

    companion object {
        const val SHOW_PLUGIN = "com.atakmap.android.plowtak.SHOW"
    }
}
