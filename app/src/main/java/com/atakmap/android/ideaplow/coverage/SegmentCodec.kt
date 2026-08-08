package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.MaterialMode
import com.atakmap.android.ideaplow.model.TrackPoint
import com.atakmap.android.ideaplow.model.TreatSegment
import java.util.Locale

/**
 * Compact, dependency-free line codec for [TreatSegment]. Used for both the
 * flat-file offline store (one segment per line) and the `points` attribute
 * of coverage CoT details.
 *
 * Line format (pipe-delimited, v1):
 *   1|id|vehicleUid|callsign|stormId|operatorId|material|widthM|startMs|points
 * Points format:
 *   lat,lon,dtMs,heading;lat,lon,dtMs,heading;...
 * where dtMs is the offset from startMs (keeps lines short) and heading is
 * empty when unknown.
 */
object SegmentCodec {

    private const val VERSION = "1"
    private const val FIELD_SEP = "|"
    private const val ESCAPED_PIPE = "&#124;"

    fun encode(seg: TreatSegment): String {
        return listOf(
            VERSION,
            escape(seg.id),
            escape(seg.vehicleUid),
            escape(seg.callsign),
            escape(seg.stormId),
            escape(seg.operatorId),
            seg.material.wireName,
            formatDouble(seg.widthM),
            seg.startTimeMs.toString(),
            encodePoints(seg.points, seg.startTimeMs)
        ).joinToString(FIELD_SEP)
    }

    /** Returns null on any malformed input rather than throwing. */
    fun decode(line: String): TreatSegment? {
        val f = line.trim().split(FIELD_SEP)
        if (f.size != 10 || f[0] != VERSION) return null
        return try {
            val startMs = f[8].toLong()
            val points = decodePoints(f[9], startMs)
            if (points.size < 2) return null
            TreatSegment(
                id = unescape(f[1]),
                vehicleUid = unescape(f[2]),
                callsign = unescape(f[3]),
                stormId = unescape(f[4]),
                operatorId = unescape(f[5]),
                material = MaterialMode.fromWireName(f[6]) ?: MaterialMode.NONE,
                widthM = f[7].toDouble(),
                points = points,
                startTimeMs = startMs,
                endTimeMs = points.last().timeMs
            )
        } catch (e: NumberFormatException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun encodePoints(points: List<TrackPoint>, baseTimeMs: Long): String =
        points.joinToString(";") { p ->
            val h = if (p.headingDeg.isNaN()) "" else formatDouble(p.headingDeg)
            "${formatCoord(p.lat)},${formatCoord(p.lon)},${p.timeMs - baseTimeMs},$h"
        }

    fun decodePoints(encoded: String, baseTimeMs: Long): List<TrackPoint> =
        encoded.split(";").mapNotNull { chunk ->
            val c = chunk.split(",")
            if (c.size != 4) return@mapNotNull null
            try {
                TrackPoint(
                    lat = c[0].toDouble(),
                    lon = c[1].toDouble(),
                    timeMs = baseTimeMs + c[2].toLong(),
                    headingDeg = if (c[3].isEmpty()) Double.NaN else c[3].toDouble()
                )
            } catch (e: NumberFormatException) {
                null
            }
        }

    /** ~1.1 cm precision — plenty for a plow swath, keeps payloads small. */
    private fun formatCoord(v: Double): String = String.format(Locale.US, "%.7f", v)

    private fun formatDouble(v: Double): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun escape(s: String) = s.replace(FIELD_SEP, ESCAPED_PIPE)
    private fun unescape(s: String) = s.replace(ESCAPED_PIPE, FIELD_SEP)
}
