package com.atakmap.android.ideaplow.plugin

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.atakmap.android.ideaplow.IdeaPlowMapComponent
import com.atakmap.android.maps.MapComponent
import com.atakmap.android.maps.MapView
import transapps.maps.plugin.lifecycle.Lifecycle

/**
 * Plugin entry point declared in assets/plugin.xml. Bridges the transapps
 * lifecycle into ATAK [MapComponent]s.
 */
class IdeaPlowLifecycle(private val pluginContext: Context) : Lifecycle {

    private val overlays = mutableListOf<MapComponent>()
    private var mapView: MapView? = null

    override fun onCreate(activity: Activity, transappsMapView: transapps.mapi.MapView?) {
        if (transappsMapView == null || transappsMapView.view !is MapView) {
            Log.w(TAG, "This plugin is only compatible with ATAK MapView")
            return
        }
        val atakMapView = transappsMapView.view as MapView
        mapView = atakMapView

        overlays.add(IdeaPlowMapComponent())

        for (component in overlays) {
            component.onCreate(pluginContext, activity.intent, atakMapView)
        }
    }

    override fun onDestroy() {
        val view = mapView ?: return
        for (component in overlays) {
            component.onDestroy(pluginContext, view)
        }
        overlays.clear()
        mapView = null
    }

    override fun onStart() {
        val view = mapView ?: return
        for (component in overlays) {
            component.onStart(pluginContext, view)
        }
    }

    override fun onStop() {
        val view = mapView ?: return
        for (component in overlays) {
            component.onStop(pluginContext, view)
        }
    }

    override fun onPause() {
        val view = mapView ?: return
        for (component in overlays) {
            component.onPause(pluginContext, view)
        }
    }

    override fun onResume() {
        val view = mapView ?: return
        for (component in overlays) {
            component.onResume(pluginContext, view)
        }
    }

    override fun onFinish() {
        // Intentionally empty
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        for (component in overlays) {
            component.onConfigurationChanged(configuration)
        }
    }

    companion object {
        private const val TAG = "IdeaPlowLifecycle"
    }
}
