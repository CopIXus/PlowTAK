package com.atakmap.android.plowtak

import android.content.Context
import android.content.Intent
import android.util.Log
import com.atakmap.android.dropdown.DropDownMapComponent
import com.atakmap.android.plowtak.ui.PlowTakDropDownReceiver
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter
import com.atakmap.android.maps.MapView

/**
 * Registers PlowTak receivers and owns the [PlowTakController] engine
 * (coverage recording, CoT publish/consume, map overlays, geofences).
 */
class PlowTakMapComponent : DropDownMapComponent() {

    private var dropDownReceiver: PlowTakDropDownReceiver? = null
    private var controller: PlowTakController? = null

    override fun onCreate(context: Context, intent: Intent, view: MapView) {
        context.setTheme(R.style.ATAKPluginTheme)
        super.onCreate(context, intent, view)

        Log.d(TAG, "creating the PlowTak map component")

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
    }

    override fun onDestroyImpl(context: Context, view: MapView) {
        // DropDownMapComponent unregisters receivers registered through
        // registerDropDownReceiver; the controller tears down MapItems,
        // overlays, timers, and CoT listeners.
        controller?.dispose()
        controller = null
        dropDownReceiver = null
        super.onDestroyImpl(context, view)
    }

    companion object {
        private const val TAG = "PlowTakMapComponent"
    }
}
