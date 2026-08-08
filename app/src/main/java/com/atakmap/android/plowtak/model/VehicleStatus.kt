package com.atakmap.android.plowtak.model

/**
 * Operational status beyond treat/deadhead, shown on markers and the fleet
 * list and carried in the CoT `<status mode=.../>` attribute.
 */
enum class VehicleStatus(val wireName: String, val label: String) {
    TREATING("treating", "Treating"),
    DEADHEAD("deadhead", "Deadhead"),
    LOADING("loading", "Loading"),
    REFUELING("refueling", "Refueling"),
    ON_BREAK("on_break", "On break"),
    OUT_OF_SERVICE("out_of_service", "Out of service"),
    OFF_DUTY("off_duty", "Off duty");

    companion object {
        fun fromWireName(name: String?): VehicleStatus? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }

        /** Statuses a driver can set manually with one tap. */
        val MANUAL_OPTIONS = listOf(LOADING, REFUELING, ON_BREAK, OUT_OF_SERVICE)
    }
}
