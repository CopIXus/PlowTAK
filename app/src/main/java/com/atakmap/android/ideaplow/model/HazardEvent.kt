package com.atakmap.android.ideaplow.model

/**
 * One-tap hazard drop types. [cotType] is the marker type used for the CoT
 * event so plain ATAK clients render something sensible; the specific hazard
 * kind rides in the `<__ideaplow><hazard .../></__ideaplow>` detail.
 */
enum class HazardType(val wireName: String, val label: String, val cotType: String) {
    STRANDED_VEHICLE("stranded", "Stranded vehicle", "a-n-G-E-V"),
    TREE_WIRES_DOWN("tree_wires", "Tree / wires down", "b-m-p-s-m"),
    ABANDONED_CAR("abandoned", "Abandoned car blocking", "a-n-G-E-V"),
    DRIFT_ICE("drift_ice", "Drift / ice patch", "b-m-p-s-m"),
    DAMAGE("damage", "Damage (sign/mailbox)", "b-m-p-s-m");

    companion object {
        fun fromWireName(name: String?): HazardType? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** A dropped hazard report. */
data class HazardEvent(
    val uid: String,
    val type: HazardType,
    val reporterUid: String,
    val reporterCallsign: String,
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val stormId: String = ""
)
