package com.atakmap.android.plowtak.coverage

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

    /**
     * Closest point to p on the segment (a, b) with its distance, using a
     * local equirectangular projection centered on a. Result is
     * [lat, lon, distanceM].
     */
    fun closestPointOnSegment(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): DoubleArray {
        val cosLat = cos(Math.toRadians(aLat))
        val px = Math.toRadians(pLon - aLon) * cosLat * EARTH_RADIUS_M
        val py = Math.toRadians(pLat - aLat) * EARTH_RADIUS_M
        val bx = Math.toRadians(bLon - aLon) * cosLat * EARTH_RADIUS_M
        val by = Math.toRadians(bLat - aLat) * EARTH_RADIUS_M

        val segLenSq = bx * bx + by * by
        val t = if (segLenSq < 1e-9) 0.0
        else ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)

        val cx = t * bx
        val cy = t * by
        val dx = px - cx
        val dy = py - cy
        val lat = aLat + Math.toDegrees(cy / EARTH_RADIUS_M)
        val lon = aLon + Math.toDegrees(cx / (EARTH_RADIUS_M * cosLat))
        return doubleArrayOf(lat, lon, sqrt(dx * dx + dy * dy))
    }

    /**
     * Ray-casting point-in-polygon on raw lat/lon vertices. Fine for the
     * zone scale this plugin uses (hundreds of meters to a few km); not for
     * polygons spanning the antimeridian or poles.
     */
    fun pointInPolygon(lat: Double, lon: Double, polygon: List<Pair<Double, Double>>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (latI, lonI) = polygon[i]
            val (latJ, lonJ) = polygon[j]
            if ((latI > lat) != (latJ > lat)) {
                val intersectLon = lonI + (lat - latI) / (latJ - latI) * (lonJ - lonI)
                if (lon < intersectLon) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Smallest absolute angle between two bearings, degrees 0–180. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = ((a - b) % 360.0 + 360.0) % 360.0
        return if (d > 180.0) 360.0 - d else d
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
