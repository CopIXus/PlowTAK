package com.atakmap.android.plowtak.model

/**
 * Last-known picture of a remote fleet unit, assembled from inbound
 * `<__plowtak>` PLI CoT. Owned by `ops/FleetManager`.
 */
data class PlowVehicle(
    val uid: String,
    val callsign: String,
    val type: VehicleType,
    val status: VehicleStatus,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
    val lastUpdateMs: Long,
    val hasBlade: Boolean = false,
    val hasSalt: Boolean = false,
    val bladeDown: Boolean = false,
    val saltOn: Boolean = false,
    val stormId: String = "",
    val operatorId: String = "",
    val operatorName: String = "",
    /** Reloads this truck has reported for the current storm (from PLI). */
    val reloadCount: Int = 0
) {
    fun isStale(nowMs: Long, staleAfterMs: Long): Boolean =
        nowMs - lastUpdateMs > staleAfterMs
}
