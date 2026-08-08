package com.atakmap.android.ideaplow.prefs

import android.content.Context
import android.content.SharedPreferences
import com.atakmap.android.ideaplow.coverage.CycleTimes
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

    // ------------------------------------------------------ Phase 2 settings

    /** Per-priority cycle-time override, minutes; 0 = use the default. */
    var cycleP1Minutes: Int
        get() = prefs.getInt(KEY_CYCLE_P1, 0)
        set(v) = prefs.edit().putInt(KEY_CYCLE_P1, v.coerceIn(0, 24 * 60)).apply()

    var cycleP2Minutes: Int
        get() = prefs.getInt(KEY_CYCLE_P2, 0)
        set(v) = prefs.edit().putInt(KEY_CYCLE_P2, v.coerceIn(0, 24 * 60)).apply()

    var cycleP3Minutes: Int
        get() = prefs.getInt(KEY_CYCLE_P3, 0)
        set(v) = prefs.edit().putInt(KEY_CYCLE_P3, v.coerceIn(0, 24 * 60)).apply()

    /** Full per-priority cycle model for CycleResolver. */
    fun cycleTimes(): CycleTimes = CycleTimes(
        defaultMinutes = cycleTimeMinutes,
        p1Minutes = cycleP1Minutes,
        p2Minutes = cycleP2Minutes,
        p3Minutes = cycleP3Minutes
    )

    /** Voice (TTS) alerts for tasks / overdue / distress. */
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(v) = prefs.edit().putBoolean(KEY_TTS, v).apply()

    /** Night high-contrast palette for the driver panel. */
    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, false)
        set(v) = prefs.edit().putBoolean(KEY_NIGHT, v).apply()

    /** Direction-aware coverage coloring (one-way passes render dashed). */
    var directionSplitEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIRECTION_SPLIT, true)
        set(v) = prefs.edit().putBoolean(KEY_DIRECTION_SPLIT, v).apply()

    /** Optional GPS road-snapping against a GraphHopper pack; default OFF. */
    var roadSnapEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROADSNAP, false)
        set(v) = prefs.edit().putBoolean(KEY_ROADSNAP, v).apply()

    /** Directory of the GraphHopper pack (contains nodes/edges/geometry). */
    var roadSnapDir: String
        get() = prefs.getString(KEY_ROADSNAP_DIR, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ROADSNAP_DIR, v).apply()

    /** Max plausible plowing speed for the sanity prompt, mph. */
    var maxPlowSpeedMph: Int
        get() = prefs.getInt(KEY_MAX_PLOW_MPH, 35)
        set(v) = prefs.edit().putInt(KEY_MAX_PLOW_MPH, v.coerceIn(10, 70)).apply()

    /** Hard cap on retained coverage segments (marathon-storm safety). */
    var maxRetainedSegments: Int
        get() = prefs.getInt(KEY_MAX_SEGMENTS, 20_000)
        set(v) = prefs.edit().putInt(KEY_MAX_SEGMENTS, v.coerceIn(1_000, 100_000)).apply()

    /** Task escalation timer, minutes. */
    var taskEscalateMinutes: Int
        get() = prefs.getInt(KEY_TASK_ESCALATE, 5)
        set(v) = prefs.edit().putInt(KEY_TASK_ESCALATE, v.coerceIn(1, 60)).apply()

    // ------------------------------------------------------ Phase 3 settings

    /** Path of the imported agency road GeoJSON/KML (copied on-device). */
    var roadNetworkFile: String
        get() = prefs.getString(KEY_ROAD_NETWORK_FILE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ROAD_NETWORK_FILE, v).apply()

    /** Bluetooth equipment link enabled (blade/spreader controller). */
    var btEquipmentEnabled: Boolean
        get() = prefs.getBoolean(KEY_BT_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_BT_ENABLED, v).apply()

    /** MAC address of the chosen controller; empty = none picked. */
    var btDeviceAddress: String
        get() = prefs.getString(KEY_BT_ADDRESS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BT_ADDRESS, v).apply()

    /** Friendly name of the picked device, for the settings UI. */
    var btDeviceName: String
        get() = prefs.getString(KEY_BT_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BT_NAME, v).apply()

    /** Use BLE GATT instead of classic SPP for the controller link. */
    var btUseBle: Boolean
        get() = prefs.getBoolean(KEY_BT_BLE, false)
        set(v) = prefs.edit().putBoolean(KEY_BT_BLE, v).apply()

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
        private const val KEY_CYCLE_P1 = "ideaplow.cycle_p1_min"
        private const val KEY_CYCLE_P2 = "ideaplow.cycle_p2_min"
        private const val KEY_CYCLE_P3 = "ideaplow.cycle_p3_min"
        private const val KEY_TTS = "ideaplow.tts_enabled"
        private const val KEY_NIGHT = "ideaplow.night_mode"
        private const val KEY_DIRECTION_SPLIT = "ideaplow.direction_split"
        private const val KEY_ROADSNAP = "ideaplow.roadsnap_enabled"
        private const val KEY_ROADSNAP_DIR = "ideaplow.roadsnap_dir"
        private const val KEY_MAX_PLOW_MPH = "ideaplow.max_plow_speed_mph"
        private const val KEY_MAX_SEGMENTS = "ideaplow.max_retained_segments"
        private const val KEY_TASK_ESCALATE = "ideaplow.task_escalate_min"
        private const val KEY_ROAD_NETWORK_FILE = "ideaplow.road_network_file"
        private const val KEY_BT_ENABLED = "ideaplow.bt_enabled"
        private const val KEY_BT_ADDRESS = "ideaplow.bt_address"
        private const val KEY_BT_NAME = "ideaplow.bt_name"
        private const val KEY_BT_BLE = "ideaplow.bt_use_ble"
    }
}
