package com.atakmap.android.plowtak.map

import android.util.Log
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.VehicleStatus
import com.atakmap.android.plowtak.model.VehicleType
import com.atakmap.android.plowtak.ops.FleetManager
import com.atakmap.android.maps.MapGroup
import com.atakmap.android.maps.MapView
import com.atakmap.android.maps.Marker
import com.atakmap.coremap.maps.coords.GeoPoint

/**
 * Owns one marker per remote fleet unit in the PlowTak MapGroup, styled by
 * vehicle type + status color, greyed out when stale. PlowTak PLI events
 * are dispatched internally too, so stock ATAK also shows plain markers —
 * these enriched markers carry the winter-ops iconography.
 */
class FleetMarkerManager(
    private val mapView: MapView,
    private val fleetManager: FleetManager
) : FleetManager.Listener {

    private var group: MapGroup? = null
    private val markers = HashMap<String, Marker>()

    fun start() {
        if (group != null) return
        val root = mapView.rootGroup
        val parent = root.findMapGroup(CoverageOverlay.GROUP_NAME)
            ?: root.addGroup(CoverageOverlay.GROUP_NAME)
        group = parent.findMapGroup(SUBGROUP) ?: parent.addGroup(SUBGROUP)
        fleetManager.addListener(this)
    }

    fun dispose() {
        fleetManager.removeListener(this)
        val g = group ?: return
        markers.values.forEach { m ->
            try {
                g.removeItem(m)
            } catch (e: Exception) {
                Log.w(TAG, "failed removing marker", e)
            }
        }
        markers.clear()
        group = null
    }

    override fun onFleetChanged(vehicles: List<PlowVehicle>) {
        mapView.post { sync(vehicles) }
    }

    /** Shared tick: grey out stale units, drop long-dead ones. */
    fun refreshStaleness(nowMs: Long) {
        mapView.post {
            val stale = fleetManager.staleUids(nowMs).toSet()
            for ((uid, marker) in markers) {
                applyStaleness(marker, uid in stale)
            }
            fleetManager.pruneDead(nowMs)
        }
    }

    private fun sync(vehicles: List<PlowVehicle>) {
        val g = group ?: return
        val liveUids = vehicles.map { it.uid }.toSet()

        // Remove markers for units no longer tracked.
        val gone = markers.keys.filter { it !in liveUids }
        for (uid in gone) {
            markers.remove(uid)?.let {
                try {
                    g.removeItem(it)
                } catch (e: Exception) {
                    Log.w(TAG, "failed removing marker $uid", e)
                }
            }
        }

        val now = System.currentTimeMillis()
        for (v in vehicles) {
            try {
                val marker = markers.getOrPut(v.uid) {
                    Marker(GeoPoint(v.lat, v.lon), "$MARKER_UID_PREFIX${v.uid}").also {
                        it.setMetaBoolean("addToObjList", false)
                        g.addItem(it)
                    }
                }
                marker.setPoint(GeoPoint(v.lat, v.lon))
                marker.type = typeFor(v.type)
                marker.title = labelFor(v)
                marker.setMetaString("callsign", v.callsign)
                marker.setMetaInteger("color", statusColor(v.status))
                if (!v.headingDeg.isNaN()) {
                    // SDK-fixup point: Marker.setTrack(course, speed) per 5.x API.
                    marker.setTrack(v.headingDeg, 0.0)
                }
                applyStaleness(marker, v.isStale(now, fleetManager.staleAfterMs))
            } catch (e: Exception) {
                Log.e(TAG, "failed syncing marker for ${v.uid}", e)
            }
        }
    }

    private fun applyStaleness(marker: Marker, stale: Boolean) {
        if (stale) {
            marker.setMetaInteger("color", COLOR_STALE)
            marker.setMetaBoolean("plowtak.stale", true)
        } else {
            marker.setMetaBoolean("plowtak.stale", false)
        }
    }

    companion object {
        private const val TAG = "PlowTakFleet"
        private const val SUBGROUP = "Fleet"
        private const val MARKER_UID_PREFIX = "plowtak-fleet-"

        private const val COLOR_STALE = 0xFF9E9E9E.toInt()

        fun typeFor(type: VehicleType): String = when (type) {
            VehicleType.PLOW -> "a-f-G-E-V-C"       // ground equipment vehicle
            VehicleType.SALT_ONLY -> "a-f-G-E-V-C"
            VehicleType.SUPERVISOR -> "a-f-G-U-C"   // command element
            VehicleType.OBSERVER -> "a-f-G-U"       // generic friendly ground
        }

        fun labelFor(v: PlowVehicle): String {
            val prefix = when (v.type) {
                VehicleType.PLOW -> "PLOW"
                VehicleType.SALT_ONLY -> "SALT"
                VehicleType.SUPERVISOR -> "SUP"
                VehicleType.OBSERVER -> "OBS"
            }
            return "${v.callsign} [$prefix ${v.status.label}]"
        }

        fun statusColor(status: VehicleStatus): Int = when (status) {
            VehicleStatus.TREATING -> 0xFF2ECC40.toInt()
            VehicleStatus.DEADHEAD -> 0xFF5FA8D3.toInt()
            VehicleStatus.LOADING -> 0xFFFFDC00.toInt()
            VehicleStatus.REFUELING -> 0xFFFF851B.toInt()
            VehicleStatus.ON_BREAK -> 0xFFB10DC9.toInt()
            VehicleStatus.OUT_OF_SERVICE -> 0xFFFF4136.toInt()
            VehicleStatus.OFF_DUTY -> 0xFF9E9E9E.toInt()
        }
    }
}
