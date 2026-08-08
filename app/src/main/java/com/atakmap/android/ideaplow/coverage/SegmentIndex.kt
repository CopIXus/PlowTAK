package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.TreatSegment
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Simple grid-binning spatial index over [TreatSegment]s so proximity
 * queries (direction pairing, overlay recolor, tasking "overdue near here")
 * stay fast with thousands of segments instead of scanning the whole store.
 *
 * Cells are fixed-size in degrees (~550 m at the default). A segment is
 * registered in every cell its point bounding box covers; queries return a
 * coarse candidate set (bounding-box precision) that callers refine with
 * real distance checks. Not thread-safe on its own — the owning
 * CoverageStore synchronizes access.
 */
class SegmentIndex(
    private val cellSizeDeg: Double = DEFAULT_CELL_SIZE_DEG
) {

    private val cells = HashMap<Long, MutableSet<String>>()
    private val segments = HashMap<String, TreatSegment>()
    private val segmentCells = HashMap<String, List<Long>>()

    fun size(): Int = segments.size

    fun add(segment: TreatSegment) {
        remove(segment.id)
        val keys = coveredCells(segment)
        for (key in keys) {
            cells.getOrPut(key) { mutableSetOf() }.add(segment.id)
        }
        segments[segment.id] = segment
        segmentCells[segment.id] = keys
    }

    fun remove(id: String) {
        val keys = segmentCells.remove(id) ?: return
        segments.remove(id)
        for (key in keys) {
            val set = cells[key] ?: continue
            set.remove(id)
            if (set.isEmpty()) cells.remove(key)
        }
    }

    fun clear() {
        cells.clear()
        segments.clear()
        segmentCells.clear()
    }

    /** Coarse candidate set within [radiusM] of a point (bbox precision). */
    fun nearby(lat: Double, lon: Double, radiusM: Double): List<TreatSegment> {
        val latSpan = radiusM / METERS_PER_DEG_LAT
        val lonSpan = radiusM / max(1.0, METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))
        return collect(lat - latSpan, lat + latSpan, lon - lonSpan, lon + lonSpan)
    }

    /** Coarse candidate set within [marginM] of any point of [segment]. */
    fun nearSegment(segment: TreatSegment, marginM: Double): List<TreatSegment> {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in segment.points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        val latSpan = marginM / METERS_PER_DEG_LAT
        val lonSpan = marginM / max(1.0, METERS_PER_DEG_LAT * cos(Math.toRadians(minLat)))
        return collect(minLat - latSpan, maxLat + latSpan, minLon - lonSpan, maxLon + lonSpan)
            .filter { it.id != segment.id }
    }

    private fun collect(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double
    ): List<TreatSegment> {
        val out = LinkedHashMap<String, TreatSegment>()
        val minLatC = cellIndex(minLat)
        val maxLatC = cellIndex(maxLat)
        val minLonC = cellIndex(minLon)
        val maxLonC = cellIndex(maxLon)
        for (latC in minLatC..maxLatC) {
            for (lonC in minLonC..maxLonC) {
                val ids = cells[cellKey(latC, lonC)] ?: continue
                for (id in ids) {
                    segments[id]?.let { out[id] = it }
                }
            }
        }
        return out.values.toList()
    }

    private fun coveredCells(segment: TreatSegment): List<Long> {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in segment.points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        val keys = mutableListOf<Long>()
        for (latC in cellIndex(minLat)..cellIndex(maxLat)) {
            for (lonC in cellIndex(minLon)..cellIndex(maxLon)) {
                keys.add(cellKey(latC, lonC))
            }
        }
        return keys
    }

    private fun cellIndex(deg: Double): Int = floor(deg / cellSizeDeg).toInt()

    private fun cellKey(latC: Int, lonC: Int): Long =
        (latC.toLong() shl 32) or (lonC.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DEFAULT_CELL_SIZE_DEG = 0.005
        private const val METERS_PER_DEG_LAT = 111_320.0
    }
}
