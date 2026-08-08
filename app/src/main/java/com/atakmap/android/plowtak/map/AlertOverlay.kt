package com.atakmap.android.plowtak.map

import android.util.Log
import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState
import com.atakmap.android.plowtak.ops.AlertManager
import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.coremap.maps.coords.GeoPoint

/**
 * Pulsing distress markers. ACTIVE alerts pulse red on the shared tick;
 * ACKNOWLEDGED alerts hold steady yellow; CLEARED alerts are removed.
 */
class AlertOverlay(
    private val mapView: MapView,
    private val alertManager: AlertManager
) : AlertManager.Listener {

    private var group: MapGroup? = null
    private val markers = HashMap<String, Marker>()
    private var pulseOn = false

    fun start() {
        if (group != null) return
        val root = mapView.rootGroup
        val parent = root.findMapGroup(CoverageOverlay.GROUP_NAME)
            ?: root.addGroup(CoverageOverlay.GROUP_NAME)
        group = parent.findMapGroup(SUBGROUP) ?: parent.addGroup(SUBGROUP)
        alertManager.addListener(this)
    }

    fun dispose() {
        alertManager.removeListener(this)
        val g = group ?: return
        markers.values.forEach { m ->
            try {
                g.removeItem(m)
            } catch (e: Exception) {
                Log.w(TAG, "failed removing alert marker", e)
            }
        }
        markers.clear()
        group = null
    }

    override fun onAlertsChanged(alerts: List<AlertEvent>) {
        mapView.post { sync(alerts) }
    }

    override fun onLocalTransition(alert: AlertEvent) {
        // Broadcast handled by the CoT layer; display follows onAlertsChanged.
    }

    /** Shared fast tick (~1 s) drives the pulse. */
    fun pulse() {
        mapView.post {
            pulseOn = !pulseOn
            for ((uid, marker) in markers) {
                val alert = alertManager.get(uid) ?: continue
                if (alert.state == AlertState.ACTIVE) {
                    marker.setMetaInteger("color", if (pulseOn) COLOR_ACTIVE else COLOR_ACTIVE_DIM)
                    marker.title = "MAYDAY ${alert.callsign}" + if (pulseOn) " !" else ""
                }
            }
        }
    }

    private fun sync(alerts: List<AlertEvent>) {
        val g = group ?: return
        val liveUids = alerts.map { it.uid }.toSet()

        markers.keys.filter { it !in liveUids }.forEach { uid ->
            markers.remove(uid)?.let {
                try {
                    g.removeItem(it)
                } catch (e: Exception) {
                    Log.w(TAG, "failed removing alert $uid", e)
                }
            }
        }

        for (alert in alerts) {
            try {
                val marker = markers.getOrPut(alert.uid) {
                    Marker(GeoPoint(alert.lat, alert.lon), "$MARKER_UID_PREFIX${alert.uid}").also {
                        it.type = "b-a-o-tbl"
                        it.setMetaBoolean("addToObjList", false)
                        g.addItem(it)
                    }
                }
                marker.setPoint(GeoPoint(alert.lat, alert.lon))
                when (alert.state) {
                    AlertState.ACTIVE -> {
                        marker.title = "MAYDAY ${alert.callsign}"
                        marker.setMetaInteger("color", COLOR_ACTIVE)
                    }
                    AlertState.ACKNOWLEDGED -> {
                        marker.title = "ACKED ${alert.callsign} (${alert.handledBy})"
                        marker.setMetaInteger("color", COLOR_ACKED)
                    }
                    AlertState.CLEARED -> { /* removed by liveUids filter */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed syncing alert ${alert.uid}", e)
            }
        }
    }

    companion object {
        private const val TAG = "PlowTakAlerts"
        private const val SUBGROUP = "Alerts"
        private const val MARKER_UID_PREFIX = "plowtak-alert-"

        private const val COLOR_ACTIVE = 0xFFFF4136.toInt()
        private const val COLOR_ACTIVE_DIM = 0xFF801A14.toInt()
        private const val COLOR_ACKED = 0xFFFFDC00.toInt()
    }
}
