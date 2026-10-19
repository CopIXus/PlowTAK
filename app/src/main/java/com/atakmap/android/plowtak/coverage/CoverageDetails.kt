package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.TreatSegment
import java.util.Locale
import kotlin.math.roundToInt

/** Plain-text lines for the legend, tap details, and the overdue list. */
object CoverageDetails {

    const val LEGEND =
        "Green fresh · Yellow due soon · Red overdue · Dim red untreated · " +
            "Solid plow · Dashed spreader or one direction · Chevrons show travel"

    fun tapSummary(segment: TreatSegment, freshness: Freshness, nowMs: Long): String {
        val ageMin = ((nowMs - segment.endTimeMs).coerceAtLeast(0L) / 60_000L).toInt()
        val heading = compass(segment.headingDeg)
        val spread = segment.spreadMaterial?.name?.lowercase(Locale.US) ?: segment.material.name.lowercase(Locale.US)
        val who = segment.callsign.ifBlank { segment.vehicleUid }
        val op = segment.operatorId.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        val road = segment.edgeId?.let { " · $it" } ?: ""
        return "$who$op\n$spread · $heading · ${segment.widthM.roundToInt()} m · $freshness · ${ageMin} min ago$road"
    }

    fun overdueLines(
        segments: Collection<TreatSegment>,
        freshnessOf: (TreatSegment) -> Freshness,
        nowMs: Long,
        limit: Int = 8
    ): List<String> {
        return segments
            .filter { freshnessOf(it) == Freshness.RED }
            .sortedBy { it.endTimeMs }
            .take(limit)
            .map { seg ->
                val ageMin = ((nowMs - seg.endTimeMs).coerceAtLeast(0L) / 60_000L).toInt()
                val who = seg.callsign.ifBlank { seg.vehicleUid }
                "$who ${compass(seg.headingDeg)} · ${ageMin} min"
            }
    }

    fun compass(headingDeg: Double): String {
        if (headingDeg.isNaN()) return "—"
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((headingDeg % 360.0 + 360.0) % 360.0) / 45.0).roundToInt() % 8
        return names[idx]
    }
}
