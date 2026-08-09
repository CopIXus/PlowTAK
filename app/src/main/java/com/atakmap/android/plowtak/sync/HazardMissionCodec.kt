package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.model.HazardEvent

/** Mission GeoJSON FeatureCollection of active hazards. */
object HazardMissionCodec {

    const val FILENAME = "hazards-live.geojson"

    fun encode(stormId: String, hazards: List<HazardEvent>): ByteArray {
        val features = hazards.filter { it.stormId == stormId || stormId.isEmpty() }
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        features.forEachIndexed { i, h ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(h.lon).append(',').append(h.lat).append("]},")
            sb.append("\"properties\":{")
            sb.append("\"uid\":").append(q(h.uid)).append(',')
            sb.append("\"type\":").append(q(h.type.wireName)).append(',')
            sb.append("\"callsign\":").append(q(h.reporterCallsign)).append(',')
            sb.append("\"timeMs\":").append(h.timeMs).append(',')
            sb.append("\"stormId\":").append(q(h.stormId))
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
