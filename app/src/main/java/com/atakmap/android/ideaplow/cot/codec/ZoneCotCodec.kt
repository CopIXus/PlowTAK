package com.atakmap.android.ideaplow.cot.codec

import com.atakmap.android.ideaplow.model.SpecialZone
import com.atakmap.android.ideaplow.model.ZoneType
import com.atakmap.android.ideaplow.ops.ZoneManager
import java.util.Locale

/**
 * Detail codec for supervisor special zones so the whole fleet colors
 * bridges/ramps/hills/school zones with the same tightened cycle. Rides its
 * own bits-family type (ignored by stock ATAK); removal is a re-send with
 * `removed="true"`.
 *
 * ```
 * <__ideaplow>
 *   <zone id= name= kind= mult= lat= lon= radiusM= poly= removed= by= time=/>
 * </__ideaplow>
 * ```
 */
object ZoneCotCodec {

    const val ZONE_EVENT_TYPE = "b-i-x-ideaplow-zone"

    data class ZoneUpdate(val zone: SpecialZone, val removed: Boolean, val by: String)

    fun encode(zone: SpecialZone, removed: Boolean, byCallsign: String, timeMs: Long): DetailNode =
        DetailNode(
            DetailNode.IDEAPLOW, emptyMap(),
            listOf(
                DetailNode(
                    "zone", buildMap {
                        put("id", zone.id)
                        put("name", zone.name)
                        put("kind", zone.type.wireName)
                        put("mult", String.format(Locale.US, "%.2f", zone.cycleMultiplier))
                        put("lat", String.format(Locale.US, "%.7f", zone.centerLat))
                        put("lon", String.format(Locale.US, "%.7f", zone.centerLon))
                        put("radiusM", String.format(Locale.US, "%.1f", zone.radiusM))
                        if (zone.polygon.isNotEmpty()) {
                            put("poly", ZoneManager.encodePolygon(zone.polygon))
                        }
                        if (removed) put("removed", "true")
                        put("by", byCallsign)
                        put("time", timeMs.toString())
                    }
                )
            )
        )

    fun decode(node: DetailNode): ZoneUpdate? {
        val ideaplow = if (node.name == DetailNode.IDEAPLOW) node
        else node.firstChild(DetailNode.IDEAPLOW) ?: return null
        val zoneNode = ideaplow.firstChild("zone") ?: return null
        val id = zoneNode.attr("id") ?: return null
        val type = ZoneType.fromWireName(zoneNode.attr("kind")) ?: return null
        val lat = zoneNode.attrDouble("lat")
        val lon = zoneNode.attrDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return null

        return ZoneUpdate(
            zone = SpecialZone(
                id = id,
                name = zoneNode.attr("name") ?: "",
                type = type,
                cycleMultiplier = zoneNode.attrDouble("mult", type.defaultMultiplier)
                    .let { if (it.isNaN()) type.defaultMultiplier else it },
                centerLat = lat,
                centerLon = lon,
                radiusM = zoneNode.attrDouble("radiusM", 0.0).let { if (it.isNaN()) 0.0 else it },
                polygon = ZoneManager.decodePolygon(zoneNode.attr("poly") ?: "")
            ),
            removed = zoneNode.attrBool("removed"),
            by = zoneNode.attr("by") ?: ""
        )
    }
}
