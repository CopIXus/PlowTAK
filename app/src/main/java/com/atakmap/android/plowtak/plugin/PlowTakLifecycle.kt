package com.atakmap.android.plowtak.plugin

import com.atak.plugins.impl.AbstractPlugin
import com.atak.plugins.impl.PluginContextProvider
import com.atakmap.android.plowtak.PlowTakMapComponent
import gov.tak.api.plugin.IServiceController

/**
 * ATAK 5.8+ plugin entry point declared in assets/plugin.xml as
 * [gov.tak.api.plugin.IPlugin]. Wires the toolbar tool and map component.
 */
class PlowTakLifecycle(serviceController: IServiceController) : AbstractPlugin(
    serviceController,
    PlowTakTool(
        serviceController.getService(PluginContextProvider::class.java).pluginContext
    ),
    PlowTakMapComponent()
)
