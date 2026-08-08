package com.atakmap.android.plowtak.tracking

import android.util.Log
import com.atakmap.android.plowtak.coverage.GpsGate
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
 *
 * Quality control is delegated to the pure-Kotlin [GpsGate]: CE threshold,
 * teleport (speed-implausibility) rejection, and stationary-jitter
 * detection. Rejected fixes still produce a sample with [PositionSample.gpsOk]
 * false so the publisher keeps pacing, but recording must skip them.
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
        /** False when the fix failed the CE or teleport quality gates. */
        val gpsOk: Boolean,
        /** False while the gate judges the vehicle parked (jitter only). */
        val moving: Boolean = true,
        /** Speed estimate from the 1 Hz tick, m/s. */
        val speedMps: Double = 0.0
    )

    fun interface Listener {
        fun onPosition(sample: PositionSample)
    }

    private val listeners = mutableListOf<Listener>()
    private var executor: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastTimeMs = 0L
    private val gate = GpsGate()

    fun addListener(l: Listener) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: Listener) = synchronized(listeners) { listeners.remove(l) }

    fun start() {
        if (executor != null) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "PlowTak-SelfTracker").apply { isDaemon = true }
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
            val now = System.currentTimeMillis()
            // CE + teleport + stationary evaluation in the pure gate.
            val verdict = gate.evaluate(lat, lon, now, ce, ceThresholdM())
            val gpsOk = verdict.accepted

            val moved = if (lastLat.isNaN()) 0.0
            else com.atakmap.android.plowtak.coverage.GeoMath.distanceMeters(
                lastLat, lastLon, lat, lon
            )
            val dtS = if (lastTimeMs > 0) (now - lastTimeMs) / 1000.0 else 0.0
            val speed = if (dtS > 0.0) moved / dtS else 0.0
            lastLat = lat
            lastLon = lon
            lastTimeMs = now

            val s = PositionSample(
                lat, lon, heading, now, moved, gpsOk,
                moving = verdict.moving, speedMps = speed
            )
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
        private const val TAG = "PlowTakSelfTracker"
    }
}
