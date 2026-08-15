package com.atakmap.android.plowtak.coverage

import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.MaterialMode
import com.atakmap.android.plowtak.model.TreatSegment
import java.util.Locale

/**
 * Pure (no Android) stroke colors / widths for treated coverage lines.
 * Shared by the local map overlay and Data Sync GeoJSON so peers see the
 * same green / yellow / red as the truck that painted the pass.
 */
object CoverageStyle {

    const val COLOR_GREEN = 0xC02ECC40.toInt()
    const val COLOR_YELLOW = 0xC0FFDC00.toInt()
    const val COLOR_RED = 0xC0FF4136.toInt()

    fun colorFor(freshness: Freshness, segment: TreatSegment? = null): Int {
        val bucket = if (freshness == Freshness.EXPIRED) Freshness.RED else freshness
        val base = when (bucket) {
            Freshness.GREEN -> COLOR_GREEN
            Freshness.YELLOW -> COLOR_YELLOW
            Freshness.RED, Freshness.EXPIRED -> COLOR_RED
        }
        if (segment?.material == MaterialMode.SALT) {
            val tint = when (segment.spreadMaterial) {
                Material.SAND -> 0xC0C2B280.toInt()
                Material.GRAVEL -> 0xC0888888.toInt()
                Material.BRINE, Material.PREWET -> 0xC04FC3F7.toInt()
                else -> 0xC064B5F6.toInt()
            }
            return when (bucket) {
                Freshness.GREEN -> tint
                Freshness.YELLOW -> 0xC0FFB74D.toInt()
                Freshness.RED, Freshness.EXPIRED -> COLOR_RED
            }
        }
        return base
    }

    fun strokeWeightFor(segment: TreatSegment): Double {
        val base = (segment.widthM * 1.5).coerceIn(3.0, 12.0)
        return if (segment.material == MaterialMode.SALT) (base * 0.55).coerceIn(2.0, 7.0)
        else base
    }

    fun strokeWeightFor(widthM: Double): Double =
        (widthM * 1.5).coerceIn(3.0, 12.0)

    /** `#RRGGBB` from an ARGB int (alpha ignored). */
    fun strokeHex(argb: Int): String =
        String.format(Locale.US, "#%06X", argb and 0x00FFFFFF)

    /** 0–1 opacity from the ARGB alpha byte. */
    fun strokeOpacity(argb: Int): Double =
        ((argb ushr 24) and 0xFF) / 255.0

    /** GDAL / ATAK GeoJSON OGR pen style. */
    fun ogrStyle(argb: Int, widthPx: Double): String {
        val w = widthPx.coerceIn(1.0, 24.0)
        return "PEN(c:${strokeHex(argb)},w:${String.format(Locale.US, "%.1f", w)}px)"
    }

    /**
     * GeoJSON property fragment for ATAK Data Sync / GDAL / Mapbox simplestyle.
     * Includes string + numeric keys so stock ATAK paints the same color as
     * the PlowTAK overlay.
     */
    fun geoJsonStyleProps(argb: Int, widthPx: Double): String {
        val hex = strokeHex(argb)
        val opacity = String.format(Locale.US, "%.2f", strokeOpacity(argb))
        val width = String.format(Locale.US, "%.1f", widthPx.coerceIn(1.0, 24.0))
        return "\"stroke\":" + quote(hex) +
                ",\"stroke-width\":" + width +
                ",\"stroke-opacity\":" + opacity +
                ",\"strokeColor\":" + argb +
                ",\"strokeWeight\":" + width +
                ",\"ogr_style\":" + quote(ogrStyle(argb, widthPx))
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
