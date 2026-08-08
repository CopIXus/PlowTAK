package com.atakmap.android.ideaplow.ui

import android.content.Context
import android.content.Intent
import android.view.View
import com.atakmap.android.dropdown.DropDown.OnStateListener
import com.atakmap.android.dropdown.DropDownReceiver
import com.atakmap.android.ideaplow.R
import com.atakmap.android.ideaplow.plugin.PluginLayoutInflater
import com.atakmap.android.maps.MapView

/**
 * Placeholder drop-down for Phase 0. Phase 1 replaces the static layout with
 * capability-aware Driver / Supervisor / Observer panels.
 */
class IdeaPlowDropDownReceiver(
    mapView: MapView,
    private val pluginContext: Context
) : DropDownReceiver(mapView), OnStateListener {

    private val rootView: View =
        PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null)

    override fun disposeImpl() {
        // Nothing to release yet
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SHOW_PLUGIN -> showDropDown(
                rootView,
                HALF_WIDTH, FULL_HEIGHT,
                FULL_WIDTH, HALF_HEIGHT,
                false, this
            )
        }
    }

    override fun onDropDownSelectionRemoved() {}

    override fun onDropDownVisible(visible: Boolean) {}

    override fun onDropDownSizeChanged(width: Double, height: Double) {}

    override fun onDropDownClose() {}

    companion object {
        const val SHOW_PLUGIN = "com.atakmap.android.ideaplow.SHOW"
    }
}
