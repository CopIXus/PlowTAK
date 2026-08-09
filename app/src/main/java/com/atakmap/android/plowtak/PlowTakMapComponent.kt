package com.atakmap.android.plowtak

import android.content.Context
import android.content.Intent
import android.util.Log
import com.atakmap.android.cot.detail.CotDetailManager
import com.atakmap.android.dropdown.DropDownMapComponent
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter
import com.atakmap.android.maps.MapView
import com.atakmap.android.plowtak.cot.PlowTakDetailHandler
import com.atakmap.android.plowtak.ui.PlowTakDropDownReceiver
import com.atakmap.android.plowtak.ui.PlowTakPreferenceFragment
import com.atakmap.app.preferences.ToolsPreferenceFragment

/**
 * Registers PlowTak receivers and owns the [PlowTakController] engine
 * (coverage recording, CoT publish/consume, map overlays, geofences).
 */
class PlowTakMapComponent : DropDownMapComponent() {

    private var dropDownReceiver: PlowTakDropDownReceiver? = null
    private var controller: PlowTakController? = null
    private var detailHandler: PlowTakDetailHandler? = null

    override fun onCreate(context: Context, intent: Intent, view: MapView) {
        context.setTheme(R.style.ATAKPluginTheme)
        super.onCreate(context, intent, view)

        try {
            Log.d(TAG, "creating the PlowTak map component")

            val handler = PlowTakDetailHandler()
            detailHandler = handler
            CotDetailManager.getInstance().registerHandler(PlowTakDetailHandler.ELEMENT, handler)

            ToolsPreferenceFragment.register(
                ToolsPreferenceFragment.ToolPreference(
                    "PlowTAK Preferences",
                    "Winter ops coverage, voice alerts, Bluetooth, Data Sync",
                    PlowTakPreferenceFragment.KEY,
                    context.resources.getDrawable(R.drawable.ic_plowtak, null),
                    PlowTakPreferenceFragment(context)
                )
            )

            val engine = PlowTakController(context, view)
            controller = engine
            engine.start()

            val receiver = PlowTakDropDownReceiver(view, context, engine)
            dropDownReceiver = receiver

            val filter = DocumentedIntentFilter()
            filter.addAction(
                PlowTakDropDownReceiver.SHOW_PLUGIN,
                "Show the PlowTAK drop-down"
            )
            registerDropDownReceiver(receiver, filter)
        } catch (t: Throwable) {
            Log.e(TAG, "PlowTakMapComponent.onCreate failed", t)
            throw t
        }
    }

    override fun onDestroyImpl(context: Context, view: MapView) {
        ToolsPreferenceFragment.unregister(PlowTakPreferenceFragment.KEY)
        detailHandler?.let {
            CotDetailManager.getInstance().unregisterHandler(it)
        }
        detailHandler = null
        controller?.dispose()
        controller = null
        dropDownReceiver = null
        super.onDestroyImpl(context, view)
    }

    companion object {
        private const val TAG = "PlowTakMapComponent"
    }
}
