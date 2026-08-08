package com.atakmap.android.ideaplow.prefs

import android.content.Context
import android.content.SharedPreferences
import com.atakmap.android.ideaplow.model.VehicleCapability
import com.atakmap.android.ideaplow.model.VehicleType
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
        context.getSharedPreferences(IdeaPlowPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    val isConfigured: Boolean
        get() = prefs.getBoolean(KEY_CONFIGURED, false)

    /**
     * Stable per-install vehicle UID used for CoT events, generated once.
     * Format: "IDEAPLOW-<vehicleId or random>".
     */
    val vehicleUid: String
        get() {
            prefs.getString(KEY_UID, null)?.let { return it }
            val id = load().vehicleId.ifEmpty {
                UUID.randomUUID().toString().substring(0, 8)
            }
            val uid = "IDEAPLOW-$id"
            prefs.edit().putString(KEY_UID, uid).apply()
            return uid
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
            .putString(KEY_CALLSIGN, cap.callsign)
            .putString(KEY_VEHICLE_ID, cap.vehicleId)
            .putString(KEY_OBS_LABEL, cap.observerLabel)
            .apply()
        listeners.toList().forEach { it.onCapabilityChanged(cap) }
    }

    /** Loads the stored profile; defaults to an unconfigured observer. */
    fun load(): VehicleCapability {
        val type = VehicleType.fromWireName(prefs.getString(KEY_TYPE, null))
            ?: return VehicleCapability.defaultsFor(VehicleType.OBSERVER)
        val defaults = VehicleCapability.defaultsFor(type)
        return VehicleCapability(
            type = type,
            hasBlade = prefs.getBoolean(KEY_HAS_BLADE, defaults.hasBlade),
            hasSalt = prefs.getBoolean(KEY_HAS_SALT, defaults.hasSalt),
            canTreat = prefs.getBoolean(KEY_CAN_TREAT, defaults.canTreat),
            canManageStorm = prefs.getBoolean(KEY_CAN_MANAGE, defaults.canManageStorm),
            canSendDistress = prefs.getBoolean(KEY_CAN_DISTRESS, defaults.canSendDistress),
            publishPresence = prefs.getBoolean(KEY_PRESENCE, defaults.publishPresence),
            plowWidthM = prefs.getFloat(KEY_WIDTH, defaults.plowWidthM.toFloat()).toDouble(),
            callsign = prefs.getString(KEY_CALLSIGN, "") ?: "",
            vehicleId = prefs.getString(KEY_VEHICLE_ID, "") ?: "",
            observerLabel = prefs.getString(KEY_OBS_LABEL, "") ?: ""
        )
    }

    companion object {
        private const val KEY_CONFIGURED = "ideaplow.cap.configured"
        private const val KEY_UID = "ideaplow.cap.uid"
        private const val KEY_TYPE = "ideaplow.cap.type"
        private const val KEY_HAS_BLADE = "ideaplow.cap.has_blade"
        private const val KEY_HAS_SALT = "ideaplow.cap.has_salt"
        private const val KEY_CAN_TREAT = "ideaplow.cap.can_treat"
        private const val KEY_CAN_MANAGE = "ideaplow.cap.can_manage_storm"
        private const val KEY_CAN_DISTRESS = "ideaplow.cap.can_distress"
        private const val KEY_PRESENCE = "ideaplow.cap.publish_presence"
        private const val KEY_WIDTH = "ideaplow.cap.plow_width_m"
        private const val KEY_CALLSIGN = "ideaplow.cap.callsign"
        private const val KEY_VEHICLE_ID = "ideaplow.cap.vehicle_id"
        private const val KEY_OBS_LABEL = "ideaplow.cap.observer_label"
    }
}
