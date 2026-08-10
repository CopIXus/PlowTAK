package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.ReportLabels
import com.atakmap.android.plowtak.model.RoadCondition
import com.atakmap.android.plowtak.model.RoadConditionReport

/**
 * Per-unit mission GeoJSON of road-condition reports.
 * Each feature carries [RoadConditionReport.timeMs]; callers must drop
 * features older than the storm's road-condition TTL before upload/display.
 */
object ConditionMissionCodec {

    fun filename(vehicleUid: String): String =
        "${sanitizeUid(vehicleUid)}-conditions.geojson"

    fun encode(
        stormId: String,
        conditions: List<RoadConditionReport>,
        nowMs: Long,
        ttlMinutes: Int
    ): ByteArray {
        val ttlMs = ttlMinutes.coerceAtLeast(1) * 60_000L
        val live = conditions.filter { nowMs - it.timeMs <= ttlMs }
        // Do NOT include a per-tick timestamp here — that made the file hash
        // change every sync minute, stacking duplicate GeoJSON copies in the
        // mission (peers saw N identical Wet markers with the same report time).
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",")
        sb.append("\"properties\":{")
        sb.append("\"stormId\":").append(q(stormId)).append(',')
        sb.append("\"ttlMinutes\":").append(ttlMinutes)
        sb.append("},\"features\":[")
        live.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(c.lon).append(',').append(c.lat).append("]},")
            // ATAK Data Sync overlays label from properties.name; include
            // 24h local report time after the username for glanceable age.
            val displayName = ReportLabels.condition(
                c.condition.label, c.reporterCallsign, c.timeMs
            )
            sb.append("\"properties\":{")
            sb.append("\"name\":").append(q(displayName)).append(',')
            sb.append("\"title\":").append(q(displayName)).append(',')
            sb.append("\"uid\":").append(q(c.uid)).append(',')
            sb.append("\"condition\":").append(q(c.condition.wireName)).append(',')
            sb.append("\"callsign\":").append(q(c.reporterCallsign)).append(',')
            sb.append("\"reporterUid\":").append(q(c.reporterUid)).append(',')
            sb.append("\"timeMs\":").append(c.timeMs).append(',')
            sb.append("\"expiresAtMs\":").append(c.timeMs + ttlMs).append(',')
            sb.append("\"stormId\":").append(q(c.stormId))
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): List<RoadConditionReport> {
        val text = bytes.toString(Charsets.UTF_8)
        val root = MiniJson.parseObject(text) ?: return emptyList()
        val features = MiniJson.array(root["features"]) ?: return emptyList()
        val out = ArrayList<RoadConditionReport>()
        for (f in features) {
            val feat = MiniJson.obj(f) ?: continue
            val props = MiniJson.obj(feat["properties"]) ?: continue
            val geom = MiniJson.obj(feat["geometry"])
            val coords = MiniJson.array(geom?.get("coordinates"))
            val lon = MiniJson.double(coords?.getOrNull(0)) ?: continue
            val lat = MiniJson.double(coords?.getOrNull(1)) ?: continue
            val uid = MiniJson.string(props["uid"]) ?: continue
            val condition = RoadCondition.fromWireName(MiniJson.string(props["condition"]))
                ?: continue
            val timeMs = (props["timeMs"] as? Number)?.toLong() ?: continue
            val expiresAt = (props["expiresAtMs"] as? Number)?.toLong()
                ?: (timeMs + StormSessionDefaultTtl.MS)
            if (nowMs > expiresAt) continue
            out.add(
                RoadConditionReport(
                    uid = uid,
                    condition = condition,
                    reporterUid = MiniJson.string(props["reporterUid"]) ?: "",
                    reporterCallsign = MiniJson.string(props["callsign"]) ?: "",
                    lat = lat,
                    lon = lon,
                    timeMs = timeMs,
                    stormId = MiniJson.string(props["stormId"]) ?: ""
                )
            )
        }
        return out
    }

    /** Keep only reports still within [ttlMinutes] of [nowMs]. */
    fun filterFresh(
        conditions: List<RoadConditionReport>,
        nowMs: Long,
        ttlMinutes: Int
    ): List<RoadConditionReport> {
        val ttlMs = ttlMinutes.coerceAtLeast(1) * 60_000L
        return conditions.filter { nowMs - it.timeMs <= ttlMs }
    }

    private fun sanitizeUid(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unknown" }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private object StormSessionDefaultTtl {
        const val MS = 120L * 60_000L
    }
}
