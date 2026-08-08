package com.atakmap.android.plowtak.model

/**
 * Quick road-condition states a driver can report at their current
 * location. Feeds the supervisor picture and post-storm records (511-style
 * feed later).
 */
enum class RoadCondition(val wireName: String, val label: String) {
    BARE("bare", "Bare"),
    WET("wet", "Wet"),
    SLUSH("slush", "Slush"),
    SNOW_COVERED("snow_covered", "Snow-covered"),
    ICE("ice", "Ice");

    companion object {
        fun fromWireName(name: String?): RoadCondition? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** One road-condition report dropped at the reporter's position. */
data class RoadConditionReport(
    val uid: String,
    val condition: RoadCondition,
    val reporterUid: String,
    val reporterCallsign: String,
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val stormId: String = ""
) {
    companion object {
        fun makeUid(reporterUid: String, nowMs: Long) = "plowtak-cond-$reporterUid-$nowMs"
    }
}
