package com.atakmap.android.plowtak.plugin

import android.content.Context
import com.atak.plugins.impl.AbstractPluginTool
import com.atakmap.android.plowtak.R
import com.atakmap.android.plowtak.ui.PlowTakDropDownReceiver

/**
 * Toolbar entry declared in assets/plugin.xml. Tapping the PlowTak icon fires
 * the SHOW intent handled by [PlowTakDropDownReceiver].
 */
class PlowTakTool(context: Context) : AbstractPluginTool(
    context,
    context.getString(R.string.app_name),
    context.getString(R.string.app_name),
    context.resources.getDrawable(R.drawable.ic_plowtak, context.theme),
    PlowTakDropDownReceiver.SHOW_PLUGIN
)
