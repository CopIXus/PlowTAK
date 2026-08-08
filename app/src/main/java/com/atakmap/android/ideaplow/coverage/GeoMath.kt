package com.atakmap.android.ideaplow.coverage

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small, dependency-free geodesy helpers. Accuracy targets are swath-scale
 * (meters over hundreds of meters), so haversine + a local equirectangular
 * projection are plenty.
 */
object GeoMath {

    const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in meters (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Perpendicular distance in meters from point p to the segment (a, b),
     * using a local equirectangular projection centered on a.
     */
    fun crossTrackMeters(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        val cosLat = cos(Math.toRadians(aLat))
        // Local meters east/north relative to a.
        val px = Math.toRadians(pLon - aLon) * cosLat * EARTH_RADIUS_M
        val py = Math.toRadians(pLat - aLat) * EARTH_RADIUS_M
        val bx = Math.toRadians(bLon - aLon) * cosLat * EARTH_RADIUS_M
        val by = Math.toRadians(bLat - aLat) * EARTH_RADIUS_M

        val segLenSq = bx * bx + by * by
        if (segLenSq < 1e-9) return sqrt(px * px + py * py)

        val t = ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /** Initial bearing in degrees true, 0–360, from point 1 to point 2. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }
}
