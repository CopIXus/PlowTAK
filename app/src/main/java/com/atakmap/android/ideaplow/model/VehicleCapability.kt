package com.atakmap.android.ideaplow.model

/**
 * Per-device capability profile. Gates UI panels, CoT publishing, and whether
 * this unit's GPS track ever paints coverage. Persisted by
 * `prefs/VehicleCapabilityStore`.
 */
data class VehicleCapability(
    val type: VehicleType,
    val hasBlade: Boolean,
    val hasSalt: Boolean,
    val canTreat: Boolean,
    val canManageStorm: Boolean,
    val canSendDistress: Boolean,
    val publishPresence: Boolean,
    /** Effective treated width in meters (blade / wing / spreader spread). */
    val plowWidthM: Double,
    /** Fleet-facing callsign, e.g. "Plow-12". */
    val callsign: String,
    /** Persistent per-truck identifier — distinct from the per-shift operator. */
    val vehicleId: String,
    /** Observer sub-label (Fire / EMS / Traffic / EOC); empty for other types. */
    val observerLabel: String = ""
) {
    companion object {

        /** Common plow width presets in meters (8 ft, 10 ft, 12 ft, wing 16 ft, tow 26 ft). */
        val WIDTH_PRESETS_M = listOf(2.4, 3.0, 3.7, 4.9, 7.9)
        const val DEFAULT_WIDTH_M = 3.0

        /**
         * Sensible defaults per vehicle type; the first-run UI starts from
         * these and lets the user adjust sub-options.
         */
        fun defaultsFor(
            type: VehicleType,
            callsign: String = "",
            vehicleId: String = ""
        ): VehicleCapability = when (type) {
            VehicleType.PLOW -> VehicleCapability(
                type = type,
                hasBlade = true, hasSalt = false,
                canTreat = true, canManageStorm = false,
                canSendDistress = true, publishPresence = true,
                plowWidthM = DEFAULT_WIDTH_M,
                callsign = callsign, vehicleId = vehicleId
            )
            VehicleType.SALT_ONLY -> VehicleCapability(
                type = type,
                hasBlade = false, hasSalt = true,
                canTreat = true, canManageStorm = false,
                canSendDistress = true, publishPresence = true,
                plowWidthM = DEFAULT_WIDTH_M,
                callsign = callsign, vehicleId = vehicleId
            )
            VehicleType.SUPERVISOR -> VehicleCapability(
                type = type,
                hasBlade = false, hasSalt = false,
                canTreat = false, canManageStorm = true,
                canSendDistress = true, publishPresence = true,
                plowWidthM = 0.0,
                callsign = callsign, vehicleId = vehicleId
            )
            VehicleType.OBSERVER -> VehicleCapability(
                type = type,
                hasBlade = false, hasSalt = false,
                canTreat = false, canManageStorm = false,
                // Default on for field units per plan; configurable in setup.
                canSendDistress = true,
                publishPresence = false,
                plowWidthM = 0.0,
                callsign = callsign, vehicleId = vehicleId
            )
        }

        /**
         * Normalizes a user-edited capability so illegal combinations cannot
         * be persisted (e.g. an observer with a blade, or a "treating"
         * supervisor).
         */
        fun sanitize(cap: VehicleCapability): VehicleCapability {
            val treatType = cap.type == VehicleType.PLOW || cap.type == VehicleType.SALT_ONLY
            val hasBlade = cap.hasBlade && cap.type == VehicleType.PLOW
            val hasSalt = cap.hasSalt && treatType
            return cap.copy(
                hasBlade = hasBlade,
                hasSalt = hasSalt || cap.type == VehicleType.SALT_ONLY,
                canTreat = treatType && (hasBlade || hasSalt || cap.type == VehicleType.SALT_ONLY),
                canManageStorm = cap.canManageStorm && cap.type == VehicleType.SUPERVISOR,
                plowWidthM = if (treatType) cap.plowWidthM.coerceAtLeast(1.0) else 0.0,
                observerLabel = if (cap.type == VehicleType.OBSERVER) cap.observerLabel else ""
            )
        }
    }
}
