package com.atakmap.android.ideaplow

import android.content.Context
import android.content.Intent
import android.util.Log
import com.atakmap.android.dropdown.DropDownMapComponent
import com.atakmap.android.ideaplow.ui.IdeaPlowDropDownReceiver
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter
import com.atakmap.android.maps.MapView

/**
 * Registers IdeaPlow receivers and owns the [IdeaPlowController] engine
 * (coverage recording, CoT publish/consume, map overlays, geofences).
 */
class IdeaPlowMapComponent : DropDownMapComponent() {

    private var dropDownReceiver: IdeaPlowDropDownReceiver? = null
    private var controller: IdeaPlowController? = null

    override fun onCreate(context: Context, intent: Intent, view: MapView) {
        context.setTheme(R.style.ATAKPluginTheme)
        super.onCreate(context, intent, view)

        Log.d(TAG, "creating the IdeaPlow map component")

        val engine = IdeaPlowController(context, view)
        controller = engine
        engine.start()

        val receiver = IdeaPlowDropDownReceiver(view, context, engine)
        dropDownReceiver = receiver

        val filter = DocumentedIntentFilter()
        filter.addAction(
            IdeaPlowDropDownReceiver.SHOW_PLUGIN,
            "Show the IdeaPlow drop-down"
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
        private const val TAG = "IdeaPlowMapComponent"
    }
}
