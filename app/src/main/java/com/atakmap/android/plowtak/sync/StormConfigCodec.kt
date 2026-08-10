package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.StormSession

/** Mission file `storm-config.json` — shared cycle + metadata for a storm. */
object StormConfigCodec {

    const val FILENAME = "storm-config.json"

    fun encode(session: StormSession): ByteArray {
        val json = buildString {
            append('{')
            append("\"id\":").append(quote(session.id)).append(',')
            append("\"agency\":").append(quote(session.agency)).append(',')
            append("\"label\":").append(quote(session.label)).append(',')
            append("\"channel\":").append(quote(session.channel)).append(',')
            append("\"mission\":").append(quote(session.missionName)).append(',')
            append("\"cycleMinutes\":").append(session.cycleMinutes).append(',')
            append("\"roadConditionTtlMinutes\":")
                .append(session.roadConditionTtlMinutes).append(',')
            append("\"startTimeMs\":").append(session.startTimeMs).append(',')
            append("\"startedBy\":").append(quote(session.startedBy))
            append('}')
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): StormConfig? {
        val text = bytes.toString(Charsets.UTF_8)
        val map = MiniJson.parseObject(text) ?: return null
        val id = map["id"] as? String ?: return null
        return StormConfig(
            id = id,
            agency = map["agency"] as? String ?: "",
            label = map["label"] as? String ?: "",
            channel = map["channel"] as? String ?: "",
            mission = map["mission"] as? String ?: "",
            cycleMinutes = (map["cycleMinutes"] as? Number)?.toInt() ?: 45,
            roadConditionTtlMinutes = (map["roadConditionTtlMinutes"] as? Number)?.toInt()
                ?: StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES,
            startTimeMs = (map["startTimeMs"] as? Number)?.toLong() ?: 0L,
            startedBy = map["startedBy"] as? String ?: ""
        )
    }

    data class StormConfig(
        val id: String,
        val agency: String,
        val label: String,
        val channel: String,
        val mission: String,
        val cycleMinutes: Int,
        val roadConditionTtlMinutes: Int =
            StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES,
        val startTimeMs: Long,
        val startedBy: String
    )

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
