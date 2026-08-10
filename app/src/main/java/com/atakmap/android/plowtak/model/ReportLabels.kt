package com.atakmap.android.plowtak.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Display titles for hazard / road-condition reports.
 *
 * Used for CoT `contact@callsign` (local PlowTAK markers) and GeoJSON
 * `properties.name` (Data Sync peers — ATAK mission overlays read `name`,
 * and without it show "[Unnamed]").
 */
object ReportLabels {

    /** Hazard: "Stranded vehicle (Unit 12)". */
    fun hazard(typeLabel: String, reporterCallsign: String): String {
        val who = reporterCallsign.trim().ifEmpty { "Unit" }
        return "$typeLabel ($who)"
    }

    /**
     * Road condition: "Ice (Unit 12 14:35)" — 24h local clock after the
     * username so the report age is visible at a glance on the map.
     */
    fun condition(
        conditionLabel: String,
        reporterCallsign: String,
        timeMs: Long
    ): String {
        val who = reporterCallsign.trim().ifEmpty { "Unit" }
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(timeMs))
        return "$conditionLabel ($who $clock)"
    }
}
