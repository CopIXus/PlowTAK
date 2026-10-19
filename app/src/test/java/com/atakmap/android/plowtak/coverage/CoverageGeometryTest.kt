package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.TrackPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class CoverageGeometryTest {

    private fun line(headingDeg: Double, meters: Double = 400.0): List<TrackPoint> {
        val n = 9
        val lat0 = 36.85
        val lon0 = -75.98
        val step = meters / (n - 1)
        val br = Math.toRadians(headingDeg)
        return (0 until n).map { i ->
            val d = i * step
            val lat = lat0 + (d * cos(br)) / 111_320.0
            val lon = lon0 + (d * kotlin.math.sin(br)) /
                (111_320.0 * cos(Math.toRadians(lat0)))
            TrackPoint(lat, lon, i * 1000L, headingDeg)
        }
    }

    @Test
    fun `offset is to the right of travel`() {
        for (heading in listOf(0.0, 90.0, 180.0, 270.0)) {
            val pts = line(heading)
            val shifted = CoverageGeometry.offsetRight(pts, 4.0)
            val mid = pts.size / 2
            val bearing = GeoMath.bearingDeg(
                pts[mid].lat, pts[mid].lon, shifted[mid].lat, shifted[mid].lon
            )
            val diff = GeoMath.angleDiffDeg(bearing, (heading + 90.0) % 360.0)
            assertTrue("heading $heading offset bearing $bearing", diff < 25.0)
        }
    }

    @Test
    fun `chevron tip points along travel`() {
        val pts = line(90.0, 300.0)
        val shifted = CoverageGeometry.offsetRight(pts, 2.0)
        val with = CoverageGeometry.withChevrons(shifted, widthM = 3.0, spacingM = 120.0)
        assertTrue(with.size > shifted.size)
        // A chevron is four extra visits of the tip (left, tip, right, tip), so
        // the tip appears as a point that is east of both neighbors inserted with it.
        var sawTip = false
        for (i in 1 until with.size - 1) {
            val prev = with[i - 1]
            val here = with[i]
            val next = with[i + 1]
            val back = GeoMath.distanceMeters(prev.lat, prev.lon, here.lat, here.lon) < 8.0 &&
                GeoMath.distanceMeters(next.lat, next.lon, here.lat, here.lon) < 8.0
            if (!back) continue
            if (here.lon > prev.lon && here.lon > next.lon) sawTip = true
        }
        assertTrue(sawTip)
    }
}
