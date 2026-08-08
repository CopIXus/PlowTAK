package com.atakmap.android.ideaplow.model

/**
 * Driver-selectable effective-width presets. A wing or tow plow changes the
 * treated strip width mid-shift; the driver taps the preset and every swath
 * recorded from that moment strokes at the new width. Preset widths are
 * configured per vehicle in the capability settings.
 */
enum class WidthPreset(val wireName: String, val label: String) {
    STANDARD("standard", "Standard blade"),
    WING("wing", "Wing extended"),
    TOW("tow", "Tow plow");

    companion object {
        fun fromWireName(name: String?): WidthPreset? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}
