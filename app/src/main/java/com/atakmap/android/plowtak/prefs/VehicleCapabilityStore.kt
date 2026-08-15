package com.atakmap.android.plowtak.prefs

import android.content.Context
import android.content.SharedPreferences
import com.atakmap.android.plowtak.model.ContractorId
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.VehicleType
import java.util.UUID

/**
 * Persists the per-device [VehicleCapability] profile via SharedPreferences.
 * [isConfigured] gates the first-run setup flow in the drop-down.
 */
class VehicleCapabilityStore(context: Context) {

    fun interface Listener {
        fun onCapabilityChanged(capability: VehicleCapability)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PlowTakPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    val isConfigured: Boolean
        get() = prefs.getBoolean(KEY_CONFIGURED, false)

    /**
     * Stable per-install vehicle UID used for CoT events, generated once.
     * Format: "PLOWTAK-<vehicleId or random>".
     */
    val vehicleUid: String
        get() {
            prefs.getString(KEY_UID, null)?.let { return it }
            val id = load().vehicleId.ifEmpty {
                UUID.randomUUID().toString().substring(0, 8)
            }
            val uid = "PLOWTAK-$id"
            prefs.edit().putString(KEY_UID, uid).apply()
            return uid
        }

    /**
     * UID this unit publishes under. Municipal units always use the
     * persistent [vehicleUid]; a contractor inside an active storm uses the
     * per-storm temporary `CTR-<storm>-<n>` UID so each engagement's records
     * stay separable for payment verification (Phase 3).
     */
    fun effectiveUid(activeStormId: String): String {
        if (activeStormId.isEmpty() || !load().contractor) return vehicleUid
        return ContractorId.uidFor(activeStormId, ContractorId.slotFor(vehicleUid))
    }

    fun save(capability: VehicleCapability) {
        val cap = VehicleCapability.sanitize(capability)
        prefs.edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putString(KEY_TYPE, cap.type.wireName)
            .putBoolean(KEY_HAS_BLADE, cap.hasBlade)
            .putBoolean(KEY_HAS_SALT, cap.hasSalt)
            .putBoolean(KEY_CAN_TREAT, cap.canTreat)
            .putBoolean(KEY_CAN_MANAGE, cap.canManageStorm)
            .putBoolean(KEY_CAN_DISTRESS, cap.canSendDistress)
            .putBoolean(KEY_PRESENCE, cap.publishPresence)
            .putFloat(KEY_WIDTH, cap.plowWidthM.toFloat())
            .putFloat(KEY_WING_LEFT_WIDTH, cap.wingLeftWidthM.toFloat())
            .putFloat(KEY_WING_RIGHT_WIDTH, cap.wingRightWidthM.toFloat())
            .putFloat(KEY_TOW_WIDTH, cap.towWidthM.toFloat())
            .putString(KEY_CALLSIGN, cap.callsign)
            .putString(KEY_VEHICLE_ID, cap.vehicleId)
            .putString(KEY_OBS_LABEL, cap.observerLabel)
            .putBoolean(KEY_CONTRACTOR, cap.contractor)
            .apply()
        listeners.toList().forEach { it.onCapabilityChanged(cap) }
    }

    /** Loads the stored profile; defaults to an unconfigured observer. */
    fun load(): VehicleCapability {
        val type = VehicleType.fromWireName(prefs.getString(KEY_TYPE, null))
            ?: return VehicleCapability.defaultsFor(VehicleType.OBSERVER)
        val defaults = VehicleCapability.defaultsFor(type)
        val legacyWing = if (prefs.contains(KEY_WING_WIDTH)) {
            prefs.getFloat(KEY_WING_WIDTH, 0f).toDouble()
        } else {
            null
        }
        val wingLeft = if (prefs.contains(KEY_WING_LEFT_WIDTH)) {
            prefs.getFloat(KEY_WING_LEFT_WIDTH, 0f).toDouble()
        } else {
            legacyWing ?: 0.0
        }
        val wingRight = if (prefs.contains(KEY_WING_RIGHT_WIDTH)) {
            prefs.getFloat(KEY_WING_RIGHT_WIDTH, 0f).toDouble()
        } else {
            legacyWing ?: 0.0
        }
        return VehicleCapability(
            type = type,
            hasBlade = prefs.getBoolean(KEY_HAS_BLADE, defaults.hasBlade),
            hasSalt = prefs.getBoolean(KEY_HAS_SALT, defaults.hasSalt),
            canTreat = prefs.getBoolean(KEY_CAN_TREAT, defaults.canTreat),
            canManageStorm = prefs.getBoolean(KEY_CAN_MANAGE, defaults.canManageStorm),
            canSendDistress = prefs.getBoolean(KEY_CAN_DISTRESS, defaults.canSendDistress),
            publishPresence = prefs.getBoolean(KEY_PRESENCE, defaults.publishPresence),
            plowWidthM = prefs.getFloat(KEY_WIDTH, defaults.plowWidthM.toFloat()).toDouble(),
            wingLeftWidthM = wingLeft,
            wingRightWidthM = wingRight,
            towWidthM = prefs.getFloat(KEY_TOW_WIDTH, 0f).toDouble(),
            callsign = prefs.getString(KEY_CALLSIGN, "") ?: "",
            vehicleId = prefs.getString(KEY_VEHICLE_ID, "") ?: "",
            observerLabel = prefs.getString(KEY_OBS_LABEL, "") ?: "",
            contractor = prefs.getBoolean(KEY_CONTRACTOR, false)
        )
    }

    companion object {
        private const val KEY_CONFIGURED = "plowtak.cap.configured"
        private const val KEY_UID = "plowtak.cap.uid"
        private const val KEY_TYPE = "plowtak.cap.type"
        private const val KEY_HAS_BLADE = "plowtak.cap.has_blade"
        private const val KEY_HAS_SALT = "plowtak.cap.has_salt"
        private const val KEY_CAN_TREAT = "plowtak.cap.can_treat"
        private const val KEY_CAN_MANAGE = "plowtak.cap.can_manage_storm"
        private const val KEY_CAN_DISTRESS = "plowtak.cap.can_distress"
        private const val KEY_PRESENCE = "plowtak.cap.publish_presence"
        private const val KEY_WIDTH = "plowtak.cap.plow_width_m"
        /** Legacy single wing width; migrated into both sides when L/R keys missing. */
        private const val KEY_WING_WIDTH = "plowtak.cap.wing_width_m"
        private const val KEY_WING_LEFT_WIDTH = "plowtak.cap.wing_left_width_m"
        private const val KEY_WING_RIGHT_WIDTH = "plowtak.cap.wing_right_width_m"
        private const val KEY_TOW_WIDTH = "plowtak.cap.tow_width_m"
        private const val KEY_CALLSIGN = "plowtak.cap.callsign"
        private const val KEY_VEHICLE_ID = "plowtak.cap.vehicle_id"
        private const val KEY_OBS_LABEL = "plowtak.cap.observer_label"
        private const val KEY_CONTRACTOR = "plowtak.cap.contractor"
    }
}
