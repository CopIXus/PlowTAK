package com.atakmap.android.ideaplow.plugin

import android.content.Context
import com.atak.plugins.impl.AbstractPluginTool
import com.atakmap.android.ideaplow.R
import com.atakmap.android.ideaplow.ui.IdeaPlowDropDownReceiver

/**
 * Toolbar entry declared in assets/plugin.xml. Tapping the IdeaPlow icon fires
 * the SHOW intent handled by [IdeaPlowDropDownReceiver].
 */
class IdeaPlowTool(context: Context) : AbstractPluginTool(
    context,
    context.getString(R.string.app_name),
    context.getString(R.string.app_name),
    context.resources.getDrawable(R.drawable.ic_ideaplow, context.theme),
    IdeaPlowDropDownReceiver.SHOW_PLUGIN
)
