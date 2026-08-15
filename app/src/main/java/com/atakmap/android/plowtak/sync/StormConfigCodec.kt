package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.coverage.StormDefaults
import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.StormSession

/** Mission file `storm-config.json` — shared plow-track timers + metadata. */
object StormConfigCodec {

    const val FILENAME = "storm-config.json"

    fun encode(session: StormSession): ByteArray {
        val s = session.sanitized()
        val json = buildString {
            append('{')
            append("\"id\":").append(quote(s.id)).append(',')
            append("\"agency\":").append(quote(s.agency)).append(',')
            append("\"label\":").append(quote(s.label)).append(',')
            append("\"channel\":").append(quote(s.channel)).append(',')
            append("\"mission\":").append(quote(s.missionName)).append(',')
            append("\"greenUntilMinutes\":").append(s.greenUntilMinutes).append(',')
            append("\"yellowUntilMinutes\":").append(s.yellowUntilMinutes).append(',')
            append("\"cycleMinutes\":").append(s.cycleMinutes).append(',')
            append("\"cycleP1Minutes\":").append(s.cycleP1Minutes).append(',')
            append("\"cycleP2Minutes\":").append(s.cycleP2Minutes).append(',')
            append("\"cycleP3Minutes\":").append(s.cycleP3Minutes).append(',')
            append("\"coverageRetentionHours\":")
                .append(s.coverageRetentionHours).append(',')
            append("\"roadConditionTtlMinutes\":")
                .append(s.roadConditionTtlMinutes).append(',')
            append("\"startTimeMs\":").append(s.startTimeMs).append(',')
            append("\"startedBy\":").append(quote(s.startedBy))
            append('}')
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): StormConfig? {
        val text = bytes.toString(Charsets.UTF_8)
        val map = MiniJson.parseObject(text) ?: return null
        val id = map["id"] as? String ?: return null
        val cycle = (map["cycleMinutes"] as? Number)?.toInt() ?: StormDefaults.RED_AFTER_MIN
        val hasGreen = map.containsKey("greenUntilMinutes")
        val hasYellow = map.containsKey("yellowUntilMinutes")
        val migrated = if (!hasGreen || !hasYellow) {
            com.atakmap.android.plowtak.coverage.FreshnessModel.fromLegacyCycle(cycle)
        } else {
            null
        }
        return StormConfig(
            id = id,
            agency = map["agency"] as? String ?: "",
            label = map["label"] as? String ?: "",
            channel = map["channel"] as? String ?: "",
            mission = map["mission"] as? String ?: "",
            greenUntilMinutes = (map["greenUntilMinutes"] as? Number)?.toInt()
                ?: migrated?.greenUntilMinutes
                ?: StormDefaults.GREEN_UNTIL_MIN,
            yellowUntilMinutes = (map["yellowUntilMinutes"] as? Number)?.toInt()
                ?: migrated?.yellowUntilMinutes
                ?: StormDefaults.YELLOW_UNTIL_MIN,
            cycleMinutes = cycle,
            cycleP1Minutes = (map["cycleP1Minutes"] as? Number)?.toInt() ?: 0,
            cycleP2Minutes = (map["cycleP2Minutes"] as? Number)?.toInt() ?: 0,
            cycleP3Minutes = (map["cycleP3Minutes"] as? Number)?.toInt() ?: 0,
            coverageRetentionHours = (map["coverageRetentionHours"] as? Number)?.toDouble()
                ?: StormSession.DEFAULT_COVERAGE_RETENTION_HOURS,
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
        val greenUntilMinutes: Int = StormDefaults.GREEN_UNTIL_MIN,
        val yellowUntilMinutes: Int = StormDefaults.YELLOW_UNTIL_MIN,
        val cycleMinutes: Int,
        val cycleP1Minutes: Int = 0,
        val cycleP2Minutes: Int = 0,
        val cycleP3Minutes: Int = 0,
        val coverageRetentionHours: Double =
            StormSession.DEFAULT_COVERAGE_RETENTION_HOURS,
        val roadConditionTtlMinutes: Int =
            StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES,
        val startTimeMs: Long,
        val startedBy: String
    ) {
        fun toSession(): StormSession = StormSession(
            id = id,
            startTimeMs = if (startTimeMs > 0) startTimeMs else System.currentTimeMillis(),
            startedBy = startedBy,
            label = label,
            agency = agency,
            missionName = mission,
            channel = channel,
            greenUntilMinutes = greenUntilMinutes,
            yellowUntilMinutes = yellowUntilMinutes,
            cycleMinutes = cycleMinutes,
            cycleP1Minutes = cycleP1Minutes,
            cycleP2Minutes = cycleP2Minutes,
            cycleP3Minutes = cycleP3Minutes,
            coverageRetentionHours = coverageRetentionHours,
            roadConditionTtlMinutes = roadConditionTtlMinutes
        ).sanitized()
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
