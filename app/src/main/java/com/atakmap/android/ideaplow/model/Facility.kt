package com.atakmap.android.ideaplow.model

/** Facility kinds a supervisor can define; salt domes drive reload logging. */
enum class FacilityType(val wireName: String, val label: String) {
    SALT_DOME("salt_dome", "Salt dome"),
    GARAGE("garage", "Garage"),
    FUEL("fuel", "Fuel point");

    companion object {
        fun fromWireName(name: String?): FacilityType? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** A supervisor-defined circular geofence around a facility. */
data class Facility(
    val id: String,
    val name: String,
    val type: FacilityType,
    val lat: Double,
    val lon: Double,
    val radiusM: Double
)

/**
 * Logged when a treating vehicle enters a salt dome geofence. Reload counts
 * approximate material used per truck until spreader telemetry exists.
 */
data class ReloadEvent(
    val facilityId: String,
    val facilityName: String,
    val timeMs: Long,
    val operatorId: String = ""
)
