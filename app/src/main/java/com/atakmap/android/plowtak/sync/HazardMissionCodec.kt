package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.plowtak.model.ReportLabels

/** Per-unit mission GeoJSON FeatureCollection of hazards this truck reported. */
object HazardMissionCodec {

    fun filename(vehicleUid: String): String =
        "${sanitizeUid(vehicleUid)}-hazards.geojson"

    /** @deprecated Prefer [filename]; kept for older mission content names. */
    const val FILENAME = "hazards-live.geojson"

    fun encode(stormId: String, hazards: List<HazardEvent>): ByteArray {
        val features = hazards.filter { it.stormId == stormId || stormId.isEmpty() || it.stormId.isEmpty() }
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        features.forEachIndexed { i, h ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(h.lon).append(',').append(h.lat).append("]},")
            // ATAK Data Sync / mission overlays label features from
            // properties.name — without it peers see "[Unnamed]".
            val displayName = ReportLabels.hazard(h.type.label, h.reporterCallsign)
            sb.append("\"properties\":{")
            sb.append("\"name\":").append(q(displayName)).append(',')
            sb.append("\"title\":").append(q(displayName)).append(',')
            sb.append("\"uid\":").append(q(h.uid)).append(',')
            sb.append("\"type\":").append(q(h.type.wireName)).append(',')
            sb.append("\"callsign\":").append(q(h.reporterCallsign)).append(',')
            sb.append("\"reporterUid\":").append(q(h.reporterUid)).append(',')
            sb.append("\"timeMs\":").append(h.timeMs).append(',')
            sb.append("\"stormId\":").append(q(h.stormId)).append(',')
            sb.append("\"photoFile\":").append(q(h.photoFile))
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<HazardEvent> {
        val root = MiniJson.parseObject(bytes.toString(Charsets.UTF_8)) ?: return emptyList()
        val features = MiniJson.array(root["features"]) ?: return emptyList()
        val out = ArrayList<HazardEvent>()
        for (f in features) {
            val feat = MiniJson.obj(f) ?: continue
            val props = MiniJson.obj(feat["properties"]) ?: continue
            val geom = MiniJson.obj(feat["geometry"])
            val coords = MiniJson.array(geom?.get("coordinates"))
            val lon = MiniJson.double(coords?.getOrNull(0)) ?: continue
            val lat = MiniJson.double(coords?.getOrNull(1)) ?: continue
            val uid = MiniJson.string(props["uid"]) ?: continue
            val type = HazardType.fromWireName(MiniJson.string(props["type"])) ?: continue
            out.add(
                HazardEvent(
                    uid = uid,
                    type = type,
                    reporterUid = MiniJson.string(props["reporterUid"]) ?: "",
                    reporterCallsign = MiniJson.string(props["callsign"]) ?: "",
                    lat = lat,
                    lon = lon,
                    timeMs = (props["timeMs"] as? Number)?.toLong() ?: 0L,
                    stormId = MiniJson.string(props["stormId"]) ?: "",
                    photoFile = MiniJson.string(props["photoFile"]) ?: ""
                )
            )
        }
        return out
    }

    private fun sanitizeUid(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unknown" }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
