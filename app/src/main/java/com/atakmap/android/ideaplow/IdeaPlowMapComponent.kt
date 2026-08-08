package com.atakmap.android.ideaplow

import android.content.Context
import android.content.Intent
import android.util.Log
import com.atakmap.android.dropdown.DropDownMapComponent
import com.atakmap.android.ideaplow.ui.IdeaPlowDropDownReceiver
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter
import com.atakmap.android.maps.MapView

/**
 * Registers IdeaPlow receivers and (in later phases) map overlays, CoT
 * listeners, and the coverage engine.
 */
class IdeaPlowMapComponent : DropDownMapComponent() {

    private var dropDownReceiver: IdeaPlowDropDownReceiver? = null

    override fun onCreate(context: Context, intent: Intent, view: MapView) {
        context.setTheme(R.style.ATAKPluginTheme)
        super.onCreate(context, intent, view)

        Log.d(TAG, "creating the IdeaPlow map component")

        val receiver = IdeaPlowDropDownReceiver(view, context)
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
        // registerDropDownReceiver; Phase 1 must also clean up MapItems,
        // overlays, and CoT listeners here.
        dropDownReceiver = null
        super.onDestroyImpl(context, view)
    }

    companion object {
        private const val TAG = "IdeaPlowMapComponent"
    }
}
