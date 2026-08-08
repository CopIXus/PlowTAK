package com.atakmap.android.ideaplow.model

/** Material being dispensed when the spreader is on (v1.1 expands usage). */
enum class Material(val wireName: String, val label: String) {
    SALT("salt", "Salt"),
    SAND("sand", "Sand"),
    BRINE("brine", "Brine"),
    PREWET("prewet", "Pre-wet");

    companion object {
        fun fromWireName(name: String?): Material? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** What treatment a recorded swath segment represents. */
enum class MaterialMode(val wireName: String) {
    NONE("none"),
    PLOW_ONLY("plow"),
    SALT("salt"),
    PLOW_AND_SALT("plow+salt");

    companion object {
        fun fromWireName(name: String?): MaterialMode? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** Snapshot of the equipment channels, produced by an EquipmentProvider. */
data class EquipmentState(
    val bladeDown: Boolean = false,
    val saltOn: Boolean = false,
    val material: Material = Material.SALT,
    /** Active effective-width preset (standard blade / wing / tow). */
    val widthPreset: WidthPreset = WidthPreset.STANDARD,
    /**
     * Spreader application rate in lbs/mile, reported by a Bluetooth
     * controller (Phase 3); null when no hardware telemetry is present.
     */
    val rateLbsPerMi: Double? = null,
    /** Road surface temperature (deg F) from hardware; null when unknown. */
    val roadTempF: Double? = null
)
