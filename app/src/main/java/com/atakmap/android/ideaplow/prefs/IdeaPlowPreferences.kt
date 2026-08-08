package com.atakmap.android.ideaplow.prefs

import android.content.Context
import android.content.SharedPreferences
import com.atakmap.android.ideaplow.model.Material
import com.atakmap.android.ideaplow.model.TreatRule
import com.atakmap.android.ideaplow.ops.KeyValuePersistence

/**
 * Typed wrapper over the plugin's SharedPreferences. Also implements the
 * [KeyValuePersistence] port used by the framework-free ops managers
 * (facilities, shifts, storm session).
 */
class IdeaPlowPreferences(context: Context) : KeyValuePersistence {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------ KeyValuePersistence

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    // --------------------------------------------------------- settings

    /** PLI report interval while moving, seconds (1–5 s typical). */
    var reportIntervalMovingS: Int
        get() = prefs.getInt(KEY_REPORT_MOVING, 3)
        set(v) = prefs.edit().putInt(KEY_REPORT_MOVING, v.coerceIn(1, 60)).apply()

    /** PLI report interval while stopped, seconds. */
    var reportIntervalStoppedS: Int
        get() = prefs.getInt(KEY_REPORT_STOPPED, 15)
        set(v) = prefs.edit().putInt(KEY_REPORT_STOPPED, v.coerceIn(5, 300)).apply()

    /** Global cycle time, minutes (per-priority overrides are Phase 2). */
    var cycleTimeMinutes: Int
        get() = prefs.getInt(KEY_CYCLE_TIME, 45)
        set(v) = prefs.edit().putInt(KEY_CYCLE_TIME, v.coerceIn(5, 24 * 60)).apply()

    /** Coverage retention window, hours. */
    var retentionHours: Double
        get() = prefs.getFloat(KEY_RETENTION_H, 12f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_RETENTION_H, v.toFloat().coerceIn(1f, 72f)).apply()

    /** Configurable treating rule for capable units. */
    var treatRule: TreatRule
        get() = TreatRule.fromWireName(prefs.getString(KEY_TREAT_RULE, null)) ?: TreatRule.EITHER
        set(v) = prefs.edit().putString(KEY_TREAT_RULE, v.wireName).apply()

    /** Active spreader material. */
    var material: Material
        get() = Material.fromWireName(prefs.getString(KEY_MATERIAL, null)) ?: Material.SALT
        set(v) = prefs.edit().putString(KEY_MATERIAL, v.wireName).apply()

    /** Fleet units grey out after this many seconds without a report. */
    var staleAfterS: Int
        get() = prefs.getInt(KEY_STALE_AFTER, 60)
        set(v) = prefs.edit().putInt(KEY_STALE_AFTER, v.coerceIn(10, 600)).apply()

    /** Ignore GPS fixes with circular error above this many meters. */
    var gpsCeThresholdM: Double
        get() = prefs.getFloat(KEY_GPS_CE, 25f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_GPS_CE, v.toFloat().coerceIn(5f, 200f)).apply()

    companion object {
        const val PREFS_NAME = "ideaplow_prefs"
        private const val KEY_REPORT_MOVING = "ideaplow.report_interval_moving_s"
        private const val KEY_REPORT_STOPPED = "ideaplow.report_interval_stopped_s"
        private const val KEY_CYCLE_TIME = "ideaplow.cycle_time_min"
        private const val KEY_RETENTION_H = "ideaplow.retention_hours"
        private const val KEY_TREAT_RULE = "ideaplow.treat_rule"
        private const val KEY_MATERIAL = "ideaplow.material"
        private const val KEY_STALE_AFTER = "ideaplow.stale_after_s"
        private const val KEY_GPS_CE = "ideaplow.gps_ce_threshold_m"
    }
}
