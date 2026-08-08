package com.atakmap.android.ideaplow.model

/** Lifecycle of a distress alert so storms do not fill with stale SOS. */
enum class AlertState(val wireName: String) {
    ACTIVE("active"),
    ACKNOWLEDGED("acked"),
    CLEARED("cleared");

    companion object {
        fun fromWireName(name: String?): AlertState? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/**
 * A distress / need-assist alert from a fleet unit. [uid] is the CoT event
 * uid of the alert itself (stable per vehicle so re-sends update in place).
 */
data class AlertEvent(
    val uid: String,
    val vehicleUid: String,
    val callsign: String,
    val vehicleType: VehicleType,
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val state: AlertState = AlertState.ACTIVE,
    /** Callsign of the supervisor/unit that acknowledged or cleared. */
    val handledBy: String = "",
    /** Last equipment state, useful context for responders. */
    val bladeDown: Boolean = false,
    val saltOn: Boolean = false
) {
    companion object {
        fun makeUid(vehicleUid: String) = "$vehicleUid-distress"
    }
}
