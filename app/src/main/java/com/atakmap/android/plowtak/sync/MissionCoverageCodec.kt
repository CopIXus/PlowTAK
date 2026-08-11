package com.atakmap.android.plowtak.sync

import com.atakmap.android.plowtak.model.TreatSegment
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/**
 * Pure Kotlin GeoJSON encoder for TAK Data Sync coverage chunks.
 * No Android / ATAK imports — included in coretests.
 *
 * One FeatureCollection of treated LineStrings for the current UTC hour
 * window; bytes are optionally gzip-compressed for mission upload.
 */
object MissionCoverageCodec {

    /** Mission name: `plowtak-coverage-{sanitizedStormId}`. */
    fun missionName(stormId: String): String =
        "plowtak-coverage-${sanitizeStormId(stormId)}"

    /**
     * Prefer an explicit mission override from the storm session; otherwise
     * the default per-storm mission name.
     */
    fun effectiveMissionName(stormId: String, missionOverride: String = ""): String {
        val override = missionOverride.trim()
        if (override.isNotEmpty()) return sanitizeStormId(override)
        return missionName(stormId)
    }

    /** URL-safe storm id fragment (alphanumeric, dash, underscore). */
    fun sanitizeStormId(stormId: String): String {
        val cleaned = stormId.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        return cleaned.ifEmpty { "unknown" }
    }

    /** `{vehicleUid}-{yyyyMMddHH}-live.geojson` (+ `.gz` when compressed). */
    fun liveFilename(vehicleUid: String, timeMs: Long, gzip: Boolean = true): String {
        val safeUid = vehicleUid.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val hour = hourLabelUtc(timeMs)
        val base = "$safeUid-$hour-live.geojson"
        return if (gzip) "$base.gz" else base
    }

    fun hourLabelUtc(timeMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMddHH", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(timeMs))
    }

    /** Inclusive start of the UTC hour containing [timeMs]. */
    fun hourStartMs(timeMs: Long): Long {
        val hourMs = 3_600_000L
        return (timeMs / hourMs) * hourMs
    }

    fun hourEndMs(timeMs: Long): Long = hourStartMs(timeMs) + 3_600_000L

    /** Segments whose time range overlaps the UTC hour of [nowMs]. */
    fun segmentsInCurrentHour(segments: List<TreatSegment>, nowMs: Long): List<TreatSegment> {
        val start = hourStartMs(nowMs)
        val end = hourEndMs(nowMs)
        return segments.filter { it.startTimeMs < end && it.endTimeMs >= start }
    }

    fun encodeGeoJson(
        stormId: String,
        vehicleUid: String,
        generatedAtMs: Long,
        segments: List<TreatSegment>
    ): String {
        val features = segments.joinToString(",") { segmentFeature(it) }
        val header = listOf(
            "\"type\":\"FeatureCollection\"",
            "\"plowtak\":{" + props(
                mapOf(
                    "kind" to "mission-coverage-live",
                    "stormId" to stormId,
                    "vehicleUid" to vehicleUid,
                    "hour" to hourLabelUtc(generatedAtMs),
                    "generatedAt" to iso(generatedAtMs)
                )
            ) + "}"
        ).joinToString(",")
        return "{" + header + ",\"features\":[" + features + "]}"
    }

    fun encodeBytes(
        stormId: String,
        vehicleUid: String,
        generatedAtMs: Long,
        segments: List<TreatSegment>,
        gzip: Boolean = true
    ): ByteArray {
        val json = encodeGeoJson(stormId, vehicleUid, generatedAtMs, segments)
            .toByteArray(Charsets.UTF_8)
        return if (gzip) gzip(json) else json
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun gzip(raw: ByteArray): ByteArray {
        // Zero the gzip MTIME header (bytes 4–7) so identical JSON yields
        // identical gzip bytes — otherwise every 60s tick gets a new hash,
        // re-uploads, and deletes the previous mission content.
        val out = ByteArrayOutputStream(raw.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        val bytes = out.toByteArray()
        if (bytes.size >= 8) {
            bytes[4] = 0
            bytes[5] = 0
            bytes[6] = 0
            bytes[7] = 0
        }
        return bytes
    }

    private fun segmentFeature(seg: TreatSegment): String {
        val coords = seg.points.joinToString(",") { p ->
            "[${num(p.lon)},${num(p.lat)}]"
        }
        val label = buildString {
            append(seg.callsign.ifBlank { seg.vehicleUid }.ifBlank { "Plow" })
            append(" · ")
            append(seg.material.wireName)
        }
        val propMap = linkedMapOf(
            "type" to "segment",
            "name" to label,
            "title" to label,
            "id" to seg.id,
            "vehicle" to seg.vehicleUid,
            "callsign" to seg.callsign,
            "storm" to seg.stormId,
            "material" to seg.material.wireName,
            "start" to iso(seg.startTimeMs),
            "end" to iso(seg.endTimeMs)
        )
        val numeric = "\"widthM\":" + num(seg.widthM, 2) +
                ",\"startMs\":" + seg.startTimeMs +
                ",\"endMs\":" + seg.endTimeMs
        return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\"," +
                "\"coordinates\":[" + coords + "]},\"properties\":{" +
                props(propMap) + "," + numeric + "}}"
    }

    private fun props(map: Map<String, String>): String =
        map.entries.joinToString(",") { (k, v) -> jsonString(k) + ":" + jsonString(v) }

    private fun iso(timeMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(timeMs))
    }

    private fun num(v: Double, decimals: Int = 7): String =
        String.format(Locale.US, "%.${decimals}f", v)

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
