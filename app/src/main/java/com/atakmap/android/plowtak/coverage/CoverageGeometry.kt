package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Render-only geometry for coverage lines. Stored tracks are never modified.
 * [offsetRight] shifts a centerline to the right of travel so opposite
 * directions do not overdraw, and [withChevrons] inserts a `>` into the
 * stroke every [CHEVRON_SPACING_M].
 */
object CoverageGeometry {

    const val CHEVRON_SPACING_M = 120.0
    const val MIN_OFFSET_M = 1.5

    data class Pt(val lat: Double, val lon: Double)

    fun offsetMetersFor(widthM: Double): Double = max(MIN_OFFSET_M, widthM / 2.0)

    /** Displace each vertex [offsetM] meters to the right of local travel. */
    fun offsetRight(points: List<TrackPoint>, offsetM: Double): List<Pt> {
        if (points.size < 2 || offsetM == 0.0) {
            return points.map { Pt(it.lat, it.lon) }
        }
        return points.mapIndexed { i, p ->
            val bearing = vertexBearing(points, i)
            move(p.lat, p.lon, bearing + 90.0, offsetM)
        }
    }

    /**
     * Insert a chevron every [spacingM] (and at least one) along [line].
     * At point P with unit heading u and right-normal n the inserted run is
     * `P - a*u + b*n, P, P - a*u - b*n, P` with a = 2*widthM, b = widthM.
     */
    fun withChevrons(
        line: List<Pt>,
        widthM: Double,
        spacingM: Double = CHEVRON_SPACING_M
    ): List<Pt> {
        if (line.size < 2) return line
        val total = lengthM(line)
        if (total < 1.0) return line
        val a = (2.0 * widthM).coerceAtMost(total / 4.0).coerceAtLeast(1.0)
        val b = (widthM).coerceAtMost(a / 2.0).coerceAtLeast(0.5)
        val spacing = spacingM.coerceAtLeast(a * 2)
        val targets = ArrayList<Double>()
        var d = spacing
        while (d < total - a) {
            targets.add(d)
            d += spacing
        }
        if (targets.isEmpty()) targets.add(total / 2.0)

        val out = ArrayList<Pt>(line.size + targets.size * 3)
        var walked = 0.0
        var next = 0
        out.add(line[0])
        for (i in 1 until line.size) {
            val seg = GeoMath.distanceMeters(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
            val segStart = walked
            val segEnd = walked + seg
            while (next < targets.size && targets[next] <= segEnd) {
                val t = if (seg < 1e-3) 0.0 else ((targets[next] - segStart) / seg).coerceIn(0.0, 1.0)
                val lat = line[i - 1].lat + (line[i].lat - line[i - 1].lat) * t
                val lon = line[i - 1].lon + (line[i].lon - line[i - 1].lon) * t
                val bearing = GeoMath.bearingDeg(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
                out.addAll(chevron(lat, lon, bearing, a, b))
                next++
            }
            out.add(line[i])
            walked = segEnd
        }
        return out
    }

    private fun chevron(lat: Double, lon: Double, bearingDeg: Double, a: Double, b: Double): List<Pt> {
        val back = move(lat, lon, bearingDeg + 180.0, a)
        val left = move(back.lat, back.lon, bearingDeg - 90.0, b)
        val right = move(back.lat, back.lon, bearingDeg + 90.0, b)
        val tip = Pt(lat, lon)
        return listOf(left, tip, right, tip)
    }

    private fun vertexBearing(points: List<TrackPoint>, i: Int): Double {
        val prev = if (i > 0) {
            GeoMath.bearingDeg(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        } else null
        val next = if (i < points.size - 1) {
            GeoMath.bearingDeg(points[i].lat, points[i].lon, points[i + 1].lat, points[i + 1].lon)
        } else null
        return when {
            prev != null && next != null -> averageBearing(prev, next)
            next != null -> next
            prev != null -> prev
            else -> 0.0
        }
    }

    private fun averageBearing(a: Double, b: Double): Double {
        val ar = Math.toRadians(a)
        val br = Math.toRadians(b)
        val x = cos(ar) + cos(br)
        val y = sin(ar) + sin(br)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    fun move(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pt {
        val br = Math.toRadians(bearingDeg)
        val dLat = (distM * cos(br)) / 111_320.0
        val dLon = (distM * sin(br)) /
            (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return Pt(lat + dLat, lon + dLon)
    }

    private fun lengthM(line: List<Pt>): Double {
        var len = 0.0
        for (i in 1 until line.size) {
            len += GeoMath.distanceMeters(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
        }
        return len
    }
}
