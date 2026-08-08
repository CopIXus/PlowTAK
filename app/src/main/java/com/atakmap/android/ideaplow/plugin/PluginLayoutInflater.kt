package com.atakmap.android.ideaplow.plugin

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * Inflates layouts using the plugin's own context so plugin resources resolve
 * correctly inside the ATAK host process (standard ATAK plugin template helper).
 */
object PluginLayoutInflater {

    @JvmStatic
    @JvmOverloads
    fun inflate(pluginContext: Context, layoutId: Int, parent: ViewGroup? = null): View {
        val inflater =
            pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        return inflater.inflate(layoutId, parent, false)
    }
}
