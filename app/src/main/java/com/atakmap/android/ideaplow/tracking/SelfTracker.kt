package com.atakmap.android.ideaplow.tracking

import android.util.Log
import com.atakmap.android.maps.MapView
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Samples the ATAK self marker position on a fixed 1 Hz tick and fans it out
 * to listeners (swath recording, geofences, CoT publisher pacing). This is
 * the only class that reads GPS — everything downstream consumes plain
 * doubles, keeping the engine SDK-free.
 */
class SelfTracker(
    private val mapView: MapView,
    /** Fixes with CE above this are reported with [PositionSample.gpsOk] false. */
    private val ceThresholdM: () -> Double
) {

    data class PositionSample(
        val lat: Double,
        val lon: Double,
        /** Degrees true; NaN when the self marker has no track. */
        val headingDeg: Double,
        val timeMs: Long,
        /** Distance from previous sample, meters; 0 for the first. */
        val movedM: Double,
        /** False when the fix failed the CE quality gate. */
        val gpsOk: Boolean
    )

    fun interface Listener {
        fun onPosition(sample: PositionSample)
    }

    private val listeners = mutableListOf<Listener>()
    private var executor: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    fun addListener(l: Listener) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: Listener) = synchronized(listeners) { listeners.remove(l) }

    fun start() {
        if (executor != null) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "IdeaPlow-SelfTracker").apply { isDaemon = true }
        }
        executor = exec
        task = exec.scheduleWithFixedDelay({ sample() }, 1, 1, TimeUnit.SECONDS)
    }

    fun stop() {
        task?.cancel(false)
        task = null
        executor?.shutdownNow()
        executor = null
    }

    private fun sample() {
        try {
            val self = mapView.selfMarker ?: return
            val point = self.point ?: return
            if (!point.isValid) return

            val lat = point.latitude
            val lon = point.longitude
            val heading = try {
                val h = self.trackHeading
                if (h.isNaN()) Double.NaN else (h + 360.0) % 360.0
            } catch (e: Exception) {
                Double.NaN
            }
            // CE (circular error) gate — bad fixes must not paint wrong lanes.
            val ce = try {
                point.ce
            } catch (e: Exception) {
                Double.NaN
            }
            val gpsOk = ce.isNaN() || ce <= ceThresholdM()

            val moved = if (lastLat.isNaN()) 0.0
            else com.atakmap.android.ideaplow.coverage.GeoMath.distanceMeters(
                lastLat, lastLon, lat, lon
            )
            lastLat = lat
            lastLon = lon

            val s = PositionSample(lat, lon, heading, System.currentTimeMillis(), moved, gpsOk)
            val snapshot = synchronized(listeners) { listeners.toList() }
            snapshot.forEach {
                try {
                    it.onPosition(s)
                } catch (e: Exception) {
                    Log.e(TAG, "position listener failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "self sample failed", e)
        }
    }

    companion object {
        private const val TAG = "IdeaPlowSelfTracker"
    }
}
