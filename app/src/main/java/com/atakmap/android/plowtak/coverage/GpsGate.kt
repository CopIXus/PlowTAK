package com.atakmap.android.plowtak.coverage

/**
 * GPS-quality gate ahead of swath recording. Extends the CE (circular
 * error) threshold check with:
 *
 *  - **Teleport rejection** — a fix implying an implausible speed from the
 *    last accepted fix is dropped instead of painting a jump across town.
 *    Two consecutive fixes that agree with *each other* re-base the track
 *    (real re-acquire after a tunnel / cold start).
 *  - **Stationary jitter suppression** — fixes are still accepted while
 *    parked, but `moving=false` tells the recorder not to paint swath blobs
 *    at red lights and turnarounds.
 *
 * Pure Kotlin state machine; the ATAK-side SelfTracker feeds it raw fixes.
 */
class GpsGate(
    private val config: Config = Config()
) {

    data class Config(
        /** Fix-over-fix speed above this is a teleport (default ~125 mph). */
        val maxSpeedMps: Double = 56.0,
        /** Time gap after which any jump is accepted as a re-base. */
        val rebaseAfterMs: Long = 60_000L,
        /** Movement under this radius counts as standing still. */
        val stationaryRadiusM: Double = 8.0,
        /** Still inside the radius for this long => stationary. */
        val stationaryWindowMs: Long = 20_000L
    )

    enum class Reason { OK, BAD_CE, TELEPORT, STATIONARY }

    data class Verdict(
        /** False: drop the fix entirely (bad CE / teleport). */
        val accepted: Boolean,
        /** False while parked — accepted fixes that must not paint swath. */
        val moving: Boolean,
        val reason: Reason
    )

    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastTimeMs = 0L

    private var rejectedLat = Double.NaN
    private var rejectedLon = Double.NaN
    private var rejectedTimeMs = 0L

    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var anchorTimeMs = 0L

    fun reset() {
        lastLat = Double.NaN
        lastLon = Double.NaN
        lastTimeMs = 0L
        rejectedLat = Double.NaN
        rejectedLon = Double.NaN
        rejectedTimeMs = 0L
        anchorLat = Double.NaN
        anchorLon = Double.NaN
        anchorTimeMs = 0L
    }

    /**
     * Evaluate one fix. [ceM] may be NaN when the source does not report
     * accuracy; NaN passes the CE check (matching Phase 1 behavior).
     */
    fun evaluate(
        lat: Double,
        lon: Double,
        timeMs: Long,
        ceM: Double,
        ceThresholdM: Double
    ): Verdict {
        // CE gate first: a low-quality fix must not move any anchors.
        if (!ceM.isNaN() && ceM > ceThresholdM) {
            return Verdict(accepted = false, moving = true, reason = Reason.BAD_CE)
        }

        // Teleport gate against the last accepted fix.
        if (!lastLat.isNaN()) {
            val dtMs = timeMs - lastTimeMs
            if (dtMs in 1 until config.rebaseAfterMs) {
                val dist = GeoMath.distanceMeters(lastLat, lastLon, lat, lon)
                val speed = dist / (dtMs / 1000.0)
                if (speed > config.maxSpeedMps) {
                    // Consistent with the previously rejected fix? Then the
                    // *old* position was stale — re-base to the new track.
                    val consistentWithRejected = !rejectedLat.isNaN() &&
                            plausibleFrom(rejectedLat, rejectedLon, rejectedTimeMs, lat, lon, timeMs)
                    if (!consistentWithRejected) {
                        rejectedLat = lat
                        rejectedLon = lon
                        rejectedTimeMs = timeMs
                        return Verdict(accepted = false, moving = true, reason = Reason.TELEPORT)
                    }
                    resetAnchor(lat, lon, timeMs)
                }
            }
        }
        rejectedLat = Double.NaN
        rejectedLon = Double.NaN
        rejectedTimeMs = 0L

        lastLat = lat
        lastLon = lon
        lastTimeMs = timeMs

        // Stationary detection on accepted fixes.
        if (anchorLat.isNaN()) {
            resetAnchor(lat, lon, timeMs)
            return Verdict(accepted = true, moving = true, reason = Reason.OK)
        }
        val fromAnchor = GeoMath.distanceMeters(anchorLat, anchorLon, lat, lon)
        if (fromAnchor > config.stationaryRadiusM) {
            resetAnchor(lat, lon, timeMs)
            return Verdict(accepted = true, moving = true, reason = Reason.OK)
        }
        return if (timeMs - anchorTimeMs >= config.stationaryWindowMs) {
            Verdict(accepted = true, moving = false, reason = Reason.STATIONARY)
        } else {
            Verdict(accepted = true, moving = true, reason = Reason.OK)
        }
    }

    private fun plausibleFrom(
        fromLat: Double, fromLon: Double, fromTimeMs: Long,
        lat: Double, lon: Double, timeMs: Long
    ): Boolean {
        val dtMs = timeMs - fromTimeMs
        if (dtMs <= 0) return false
        val dist = GeoMath.distanceMeters(fromLat, fromLon, lat, lon)
        return dist / (dtMs / 1000.0) <= config.maxSpeedMps
    }

    private fun resetAnchor(lat: Double, lon: Double, timeMs: Long) {
        anchorLat = lat
        anchorLon = lon
        anchorTimeMs = timeMs
    }
}
