package com.atakmap.android.ideaplow.gis

import com.atakmap.android.ideaplow.model.RoutePriority

/**
 * Parses agency-provided road centerline files into a [RoadNetwork].
 *
 * Supported formats:
 *  - **GeoJSON** (primary): a FeatureCollection of LineString /
 *    MultiLineString features with properties `lanes`, `priority`,
 *    `route_id`, `oneway`, `name` (all optional; several common aliases are
 *    accepted, see [propAny]).
 *  - **KML** (best-effort): Placemark LineStrings with attributes from
 *    `<ExtendedData>` `<SimpleData name="lanes">`-style fields. Styling-only
 *    KML imports geometry with neutral attributes.
 *  - **Shapefile: out of scope** — convert to GeoJSON first (`ogr2ogr -f
 *    GeoJSON roads.json roads.shp`); documented in the ops guide.
 *
 * Malformed features are skipped, never fatal: a partially attributed file
 * still yields a usable network.
 */
object RoadNetworkImporter {

    data class Result(
        val network: RoadNetwork,
        val imported: Int,
        val skipped: Int,
        /** Human-readable problem summary; empty when clean. */
        val warnings: List<String>
    ) {
        val ok: Boolean get() = imported > 0
    }

    /** Sniffs the format from content (JSON vs KML) and imports. */
    fun import(content: String): Result {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("{") -> importGeoJson(content)
            trimmed.startsWith("<") -> importKml(content)
            else -> Result(RoadNetwork.EMPTY, 0, 0, listOf("unrecognized format"))
        }
    }

    // ----------------------------------------------------------- GeoJSON

    fun importGeoJson(json: String): Result {
        val root = MiniJson.parseObject(json)
            ?: return Result(RoadNetwork.EMPTY, 0, 0, listOf("invalid JSON"))

        val features: List<Any?> = when (MiniJson.string(root["type"])) {
            "FeatureCollection" -> MiniJson.array(root["features"]) ?: emptyList()
            "Feature" -> listOf(root)
            else -> return Result(
                RoadNetwork.EMPTY, 0, 0,
                listOf("not a FeatureCollection or Feature")
            )
        }

        val roads = mutableListOf<Road>()
        var skipped = 0
        val warnings = mutableListOf<String>()

        for ((index, f) in features.withIndex()) {
            val feature = MiniJson.obj(f) ?: run { skipped++; null } ?: continue
            val geometry = MiniJson.obj(feature["geometry"])
            if (geometry == null) {
                skipped++
                continue
            }
            val props = MiniJson.obj(feature["properties"]) ?: emptyMap()
            val baseId = MiniJson.string(feature["id"])
                ?: MiniJson.string(propAny(props, "id", "road_id", "objectid"))
                ?: "road-$index"

            val lines: List<List<RoadPoint>> = when (MiniJson.string(geometry["type"])) {
                "LineString" ->
                    listOfNotNull(coordsToPoints(MiniJson.array(geometry["coordinates"])))
                "MultiLineString" ->
                    (MiniJson.array(geometry["coordinates"]) ?: emptyList())
                        .mapNotNull { coordsToPoints(MiniJson.array(it)) }
                else -> emptyList()
            }
            if (lines.isEmpty()) {
                skipped++
                continue
            }

            val lanes = MiniJson.int(propAny(props, "lanes", "lane_count", "num_lanes")) ?: 0
            val priority = parsePriority(propAny(props, "priority", "route_priority", "class"))
            val routeId = MiniJson.string(propAny(props, "route_id", "routeid", "route", "beat"))
                ?.trim() ?: ""
            val oneway = MiniJson.bool(propAny(props, "oneway", "one_way")) ?: false
            val name = MiniJson.string(propAny(props, "name", "road_name", "street"))?.trim() ?: ""

            for ((part, pts) in lines.withIndex()) {
                roads.add(
                    Road(
                        id = if (lines.size > 1) "$baseId-$part" else baseId,
                        name = name,
                        lanes = lanes.coerceIn(0, MAX_LANES),
                        priority = priority,
                        routeId = routeId,
                        oneway = oneway,
                        points = pts
                    )
                )
            }
        }

        if (skipped > 0) warnings.add("$skipped feature(s) skipped (bad geometry/format)")
        return Result(RoadNetwork(roads), roads.size, skipped, warnings)
    }

    /** First property value present under any of the given keys (case-insensitive). */
    private fun propAny(props: Map<String, Any?>, vararg keys: String): Any? {
        for (key in keys) {
            props.entries
                .firstOrNull { it.key.equals(key, ignoreCase = true) && it.value != null }
                ?.let { return it.value }
        }
        return null
    }

    /** GeoJSON positions are [lon, lat(, alt)]. Needs >= 2 valid positions. */
    private fun coordsToPoints(coords: List<Any?>?): List<RoadPoint>? {
        if (coords == null) return null
        val pts = coords.mapNotNull { c ->
            val pair = MiniJson.array(c) ?: return@mapNotNull null
            val lon = MiniJson.double(pair.getOrNull(0)) ?: return@mapNotNull null
            val lat = MiniJson.double(pair.getOrNull(1)) ?: return@mapNotNull null
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return@mapNotNull null
            RoadPoint(lat, lon)
        }
        return if (pts.size >= 2) pts else null
    }

    /** Accepts "p1"/"P1", "1", 1.0, "priority 1" style values. */
    fun parsePriority(v: Any?): RoutePriority? {
        val s = when (v) {
            null -> return null
            is Double -> v.toInt().toString()
            else -> v.toString().trim().lowercase()
        }
        RoutePriority.fromWireName(s)?.let { if (it != RoutePriority.DEFAULT) return it }
        val digit = s.filter { it.isDigit() }
        return when (digit) {
            "1" -> RoutePriority.P1
            "2" -> RoutePriority.P2
            "3" -> RoutePriority.P3
            else -> null
        }
    }

    // -------------------------------------------------------------- KML

    /**
     * Best-effort KML import via string scanning (no XML parser in the
     * pure layer): each `<Placemark>` contributes its `<LineString>`
     * `<coordinates>`; attributes read from `<SimpleData name="...">` /
     * `<Data name="..."><value>` when present. Good enough for exports from
     * Google Earth / ArcGIS "Layer to KML"; anything fancier should be
     * converted to GeoJSON.
     */
    fun importKml(kml: String): Result {
        val roads = mutableListOf<Road>()
        var skipped = 0

        var searchFrom = 0
        var index = 0
        while (true) {
            val pmStart = kml.indexOf("<Placemark", searchFrom)
            if (pmStart < 0) break
            val pmEnd = kml.indexOf("</Placemark>", pmStart)
            if (pmEnd < 0) break
            searchFrom = pmEnd + 12
            val placemark = kml.substring(pmStart, pmEnd)

            val coordText = tagText(placemark, "coordinates")
            if (coordText == null || !placemark.contains("<LineString")) {
                skipped++
                index++
                continue
            }
            // KML coordinates: "lon,lat[,alt]" tuples separated by whitespace.
            val pts = coordText.trim().split(Regex("\\s+")).mapNotNull { tuple ->
                val c = tuple.split(",")
                val lon = c.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = c.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                RoadPoint(lat, lon)
            }
            if (pts.size < 2) {
                skipped++
                index++
                continue
            }

            roads.add(
                Road(
                    id = "kml-$index",
                    name = tagText(placemark, "name")?.trim() ?: "",
                    lanes = (simpleData(placemark, "lanes")?.toIntOrNull() ?: 0)
                        .coerceIn(0, MAX_LANES),
                    priority = parsePriority(simpleData(placemark, "priority")),
                    routeId = simpleData(placemark, "route_id") ?: "",
                    oneway = simpleData(placemark, "oneway")
                        ?.lowercase() in setOf("true", "yes", "1"),
                    points = pts
                )
            )
            index++
        }

        val warnings = mutableListOf<String>()
        if (skipped > 0) warnings.add("$skipped placemark(s) skipped (no LineString)")
        if (roads.isEmpty() && skipped == 0) warnings.add("no placemarks found")
        return Result(RoadNetwork(roads), roads.size, skipped, warnings)
    }

    private fun tagText(xml: String, tag: String): String? {
        val open = xml.indexOf("<$tag")
        if (open < 0) return null
        val contentStart = xml.indexOf('>', open)
        if (contentStart < 0) return null
        val close = xml.indexOf("</$tag>", contentStart)
        if (close < 0) return null
        return xml.substring(contentStart + 1, close)
    }

    /** `<SimpleData name="x">v</SimpleData>` or `<Data name="x"><value>v`. */
    private fun simpleData(xml: String, name: String): String? {
        for (tag in listOf("SimpleData", "Data")) {
            val marker = "<$tag name=\"$name\""
            val at = xml.indexOf(marker, ignoreCase = true)
            if (at < 0) continue
            val end = xml.indexOf("</$tag>", at, ignoreCase = true)
            if (end < 0) continue
            val block = xml.substring(at, end)
            val text = if (tag == "Data") tagText(block, "value") else {
                val gt = block.indexOf('>')
                if (gt < 0) null else block.substring(gt + 1)
            }
            text?.trim()?.let { if (it.isNotEmpty()) return it }
        }
        return null
    }

    private const val MAX_LANES = 16
}
