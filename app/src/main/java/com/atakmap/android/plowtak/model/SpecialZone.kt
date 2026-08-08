package com.atakmap.android.plowtak.model

import com.atakmap.android.plowtak.coverage.GeoMath

/**
 * Special-revisit zone kinds. Bridges/ramps freeze first and school zones
 * carry higher exposure, so each kind defaults to a shorter cycle-time
 * multiplier (supervisors can override per zone).
 */
enum class ZoneType(val wireName: String, val label: String, val defaultMultiplier: Double) {
    BRIDGE("bridge", "Bridge", 0.5),
    RAMP("ramp", "Ramp", 0.5),
    HILL("hill", "Hill", 0.75),
    SCHOOL("school", "School zone", 0.5);

    companion object {
        fun fromWireName(name: String?): ZoneType? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/**
 * A supervisor-defined special zone: circle (default) or polygon. Segments
 * whose points fall inside use the stricter cycle time
 * (base cycle x [cycleMultiplier], see `coverage/CycleResolver`).
 */
data class SpecialZone(
    val id: String,
    val name: String,
    val type: ZoneType,
    /** 0 < multiplier <= 1; applied to the resolved base cycle time. */
    val cycleMultiplier: Double,
    /** Circle center — also used as the label/anchor point for polygons. */
    val centerLat: Double,
    val centerLon: Double,
    /** Circle radius; ignored when [polygon] has >= 3 vertices. */
    val radiusM: Double,
    /** Optional polygon boundary as (lat, lon) vertices. */
    val polygon: List<Pair<Double, Double>> = emptyList()
) {
    val isPolygon: Boolean get() = polygon.size >= 3

    fun contains(lat: Double, lon: Double): Boolean =
        if (isPolygon) GeoMath.pointInPolygon(lat, lon, polygon)
        else GeoMath.distanceMeters(lat, lon, centerLat, centerLon) <= radiusM
}
