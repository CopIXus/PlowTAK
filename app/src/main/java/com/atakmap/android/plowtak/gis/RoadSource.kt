package com.atakmap.android.plowtak.gis

import com.atakmap.android.plowtak.coverage.RoadSnapper
import com.atakmap.android.plowtak.model.RoutePriority

/** A road the coverage engine can ask about. */
data class RoadHit(
    val id: String,
    val name: String,
    val priority: RoutePriority?,
    val distanceM: Double,
    val plowClass: Boolean,
    val lat: Double,
    val lon: Double
)

/**
 * Where plow priority and untreated roads come from. A VNS GraphHopper pack
 * is the default; an agency GeoJSON/KML overrides it where it has a road.
 */
interface RoadSource {
    val label: String
    fun priorityAt(lat: Double, lon: Double): RoutePriority?
    fun nearest(lat: Double, lon: Double, maxDistM: Double = 40.0): RoadHit?
}

class GraphHopperRoadSource(val snapper: RoadSnapper, val packLabel: String) : RoadSource {
    override val label: String = "VNS pack: $packLabel"

    override fun priorityAt(lat: Double, lon: Double): RoutePriority? =
        nearest(lat, lon)?.priority

    override fun nearest(lat: Double, lon: Double, maxDistM: Double): RoadHit? {
        val snap = snapper.snap(lat, lon, maxDistM) ?: return null
        val flags = snapper.graph.edgeFlags(snap.edgeId)
        val cls = GhRoadClass.roadClass(flags)
        return RoadHit(
            id = "gh-${snap.edgeId}",
            name = "",
            priority = GhRoadClass.priority(cls),
            distanceM = snap.distanceM,
            plowClass = GhRoadClass.isPlowClass(cls),
            lat = snap.lat,
            lon = snap.lon
        )
    }
}

class GeoJsonRoadSource(val network: RoadNetwork, override val label: String) : RoadSource {
    override fun priorityAt(lat: Double, lon: Double): RoutePriority? =
        network.priorityAt(lat, lon)

    override fun nearest(lat: Double, lon: Double, maxDistM: Double): RoadHit? {
        val match = network.nearest(lat, lon, maxDistM) ?: return null
        return RoadHit(
            id = match.road.id,
            name = match.road.name,
            priority = match.road.priority,
            distanceM = match.distanceM,
            plowClass = true,
            lat = lat,
            lon = lon
        )
    }
}

/**
 * Agency file wins inside its match distance; the VNS pack fills the rest.
 */
class OverlayRoadSource(
    val agency: RoadSource?,
    val pack: RoadSource?
) : RoadSource {
    override val label: String = when {
        agency != null && pack != null -> "${agency.label}; ${pack.label}"
        agency != null -> agency.label
        pack != null -> pack.label
        else -> "None: install a VNS region or import a file"
    }

    override fun priorityAt(lat: Double, lon: Double): RoutePriority? =
        agency?.priorityAt(lat, lon) ?: pack?.priorityAt(lat, lon)

    override fun nearest(lat: Double, lon: Double, maxDistM: Double): RoadHit? =
        agency?.nearest(lat, lon, maxDistM) ?: pack?.nearest(lat, lon, maxDistM)
}
