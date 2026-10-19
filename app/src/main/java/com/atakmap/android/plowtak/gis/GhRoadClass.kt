package com.atakmap.android.plowtak.gis

import com.atakmap.android.plowtak.model.RoutePriority

/**
 * GraphHopper 1.0 [com.graphhopper.routing.ev.RoadClass] ordinals and the
 * bit layout pinned by a VNS pack `properties` file:
 * `road_class|bits=5|shift=1`.
 */
object GhRoadClass {

    const val ROAD_CLASS_SHIFT = 1
    const val ROAD_CLASS_BITS = 5

    const val OTHER = 0
    const val MOTORWAY = 1
    const val TRUNK = 2
    const val PRIMARY = 3
    const val SECONDARY = 4
    const val TERTIARY = 5
    const val RESIDENTIAL = 6
    const val UNCLASSIFIED = 7
    const val SERVICE = 8
    const val ROAD = 9
    const val TRACK = 10
    const val LIVING_STREET = 16

    fun roadClass(flags: Int): Int =
        (flags ushr ROAD_CLASS_SHIFT) and ((1 shl ROAD_CLASS_BITS) - 1)

    /** Null when the class is not a plow-maintained road (driveway, path, track). */
    fun priority(roadClass: Int): RoutePriority? = when (roadClass) {
        MOTORWAY, TRUNK, PRIMARY -> RoutePriority.P1
        SECONDARY, TERTIARY -> RoutePriority.P2
        RESIDENTIAL, UNCLASSIFIED, LIVING_STREET, ROAD -> RoutePriority.P3
        else -> null
    }

    fun isPlowClass(roadClass: Int): Boolean = priority(roadClass) != null
}
