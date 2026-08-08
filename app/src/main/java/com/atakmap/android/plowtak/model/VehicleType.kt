package com.atakmap.android.plowtak.model

/**
 * What this unit *is*. Chosen once at first run (editable in settings) and
 * carried in the `<vehicle type=.../>` attribute of the `<__plowtak>` CoT
 * detail. Wire names are lowercase and stable — do not rename without a
 * schema version bump.
 */
enum class VehicleType(val wireName: String) {
    PLOW("plow"),
    SALT_ONLY("saltonly"),
    SUPERVISOR("supervisor"),
    OBSERVER("observer");

    companion object {
        fun fromWireName(name: String?): VehicleType? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}
