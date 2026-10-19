package com.atakmap.android.plowtak.prefs

import android.content.Context
import android.content.SharedPreferences
import com.atakmap.android.plowtak.coverage.CycleTimes
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.TreatRule
import com.atakmap.android.plowtak.ops.KeyValuePersistence

/**
 * Typed wrapper over the plugin's SharedPreferences. Also implements the
 * [KeyValuePersistence] port used by the framework-free ops managers
 * (facilities, shifts, storm session).
 */
class PlowTakPreferences(context: Context) : KeyValuePersistence {

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

    /** EditTextPreference stores strings; in-app setters store ints. */
    private fun intPref(key: String, default: Int): Int = try {
        prefs.getInt(key, default)
    } catch (_: ClassCastException) {
        prefs.getString(key, null)?.toIntOrNull() ?: default
    }

    private fun floatPref(key: String, default: Float): Float = try {
        prefs.getFloat(key, default)
    } catch (_: ClassCastException) {
        prefs.getString(key, null)?.toFloatOrNull() ?: default
    }

    // --------------------------------------------------------- settings

    /** PLI report interval while moving, seconds (1–5 s typical). */
    var reportIntervalMovingS: Int
        get() = intPref(KEY_REPORT_MOVING, 3)
        set(v) = prefs.edit().putInt(KEY_REPORT_MOVING, v.coerceIn(1, 60)).apply()

    /** PLI report interval while stopped, seconds. */
    var reportIntervalStoppedS: Int
        get() = intPref(KEY_REPORT_STOPPED, 15)
        set(v) = prefs.edit().putInt(KEY_REPORT_STOPPED, v.coerceIn(5, 300)).apply()

    /** Red-after (minutes) for new storms / no-storm fallback. */
    var cycleTimeMinutes: Int
        get() = prefs.getInt(KEY_CYCLE_TIME, 60)
        set(v) = prefs.edit().putInt(KEY_CYCLE_TIME, v.coerceIn(5, 24 * 60)).apply()

    /** Green-until minutes for new storms. */
    var greenUntilMinutes: Int
        get() = prefs.getInt(KEY_GREEN_UNTIL, 30)
        set(v) = prefs.edit().putInt(KEY_GREEN_UNTIL, v.coerceIn(1, 24 * 60)).apply()

    /** Yellow-until minutes for new storms. */
    var yellowUntilMinutes: Int
        get() = prefs.getInt(KEY_YELLOW_UNTIL, 50)
        set(v) = prefs.edit().putInt(KEY_YELLOW_UNTIL, v.coerceIn(1, 24 * 60)).apply()

    /**
     * Default coverage clear window for **new** storms, hours.
     * **0 = never clear** (overdue lines stay red). Joined storms use
     * [com.atakmap.android.plowtak.model.StormSession.coverageRetentionHours].
     */
    var retentionHours: Double
        get() = prefs.getFloat(KEY_RETENTION_H, 8f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_RETENTION_H, v.toFloat().coerceIn(0f, 72f)).apply()

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
        get() = intPref(KEY_STALE_AFTER, 60)
        set(v) = prefs.edit().putInt(KEY_STALE_AFTER, v.coerceIn(10, 600)).apply()

    /** Ignore GPS fixes with circular error above this many meters. */
    var gpsCeThresholdM: Double
        get() = floatPref(KEY_GPS_CE, 25f).toDouble()
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

    /** Minutes added when the driver taps + on a tasking row (5–60). */
    var taskingSnoozeMinutes: Int
        get() = prefs.getInt(KEY_TASKING_SNOOZE, 15)
        set(v) = prefs.edit().putInt(KEY_TASKING_SNOOZE, v.coerceIn(5, 60)).apply()

    /** Voice (TTS) alerts for tasks / overdue / distress. */
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(v) = prefs.edit().putBoolean(KEY_TTS, v).apply()

    /** Night high-contrast palette for the driver panel. */
    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, false)
        set(v) = prefs.edit().putBoolean(KEY_NIGHT, v).apply()

    /** Seven taps on the Setup title. Keeps the demo fleet reachable. */
    var devModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEV_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_DEV_MODE, v).apply()

    /** Mini plow HUD on the map while on shift (bottom-left). */
    var mapHudEnabled: Boolean
        get() = prefs.getBoolean(KEY_MAP_HUD, true)
        set(v) = prefs.edit().putBoolean(KEY_MAP_HUD, v).apply()

    /**
     * How long a road-condition report stays on the map / in Data Sync
     * before it is deleted. Default 2 hours. Seeded onto new storms as
     * [com.atakmap.android.plowtak.model.StormSession.roadConditionTtlMinutes].
     */
    var roadConditionStaleMinutes: Int
        get() = prefs.getInt(KEY_COND_STALE, 120)
        set(v) = prefs.edit()
            .putInt(KEY_COND_STALE, v.coerceIn(15, 24 * 60))
            .apply()

    /**
     * Extra meters beyond half the plow width when a later pass supersedes
     * an earlier one. [com.atakmap.android.plowtak.coverage.SegmentSupersede.DEFAULT_LANE_SLACK_M]
     * is road-level; [com.atakmap.android.plowtak.coverage.SegmentSupersede.STRICT_LANE_SLACK_M]
     * keeps adjacent lanes distinct.
     */
    var laneSlackM: Double
        get() = floatPref(KEY_LANE_SLACK, 6f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_LANE_SLACK, v.toFloat().coerceIn(0f, 30f)).apply()

    /** True when [laneSlackM] is the strict-lanes setting. */
    var strictLanes: Boolean
        get() = laneSlackM <= 3.0
        set(v) {
            laneSlackM = if (v) 2.0 else 6.0
        }

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
        get() = intPref(KEY_MAX_PLOW_MPH, 35)
        set(v) = prefs.edit().putInt(KEY_MAX_PLOW_MPH, v.coerceIn(10, 70)).apply()

    /** Hard cap on retained coverage segments (marathon-storm safety). */
    var maxRetainedSegments: Int
        get() = intPref(KEY_MAX_SEGMENTS, 20_000)
        set(v) = prefs.edit().putInt(KEY_MAX_SEGMENTS, v.coerceIn(1_000, 100_000)).apply()

    /** Task escalation timer, minutes. */
    var taskEscalateMinutes: Int
        get() = intPref(KEY_TASK_ESCALATE, 5)
        set(v) = prefs.edit().putInt(KEY_TASK_ESCALATE, v.coerceIn(1, 60)).apply()

    // ------------------------------------------------------ Phase 3 settings

    /** Path of the imported agency road GeoJSON/KML (copied on-device). */
    var roadNetworkFile: String
        get() = prefs.getString(KEY_ROAD_NETWORK_FILE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ROAD_NETWORK_FILE, v).apply()

    // ----------------------------------------------- mission coverage sync

    /** SHA-256 of the last uploaded live coverage chunk (Data Sync). */
    var missionCoverageLastHash: String
        get() = prefs.getString(KEY_MISSION_COV_HASH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MISSION_COV_HASH, v).apply()

    /** Filename of the last uploaded live coverage chunk. */
    var missionCoverageLastFilename: String
        get() = prefs.getString(KEY_MISSION_COV_FILENAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MISSION_COV_FILENAME, v).apply()

    /**
     * Preferred TAK server for Data Sync mission uploads (ATAK connect string).
     * Empty = first connected server.
     */
    var dataSyncServerConnectString: String
        get() = prefs.getString(KEY_DATASYNC_SERVER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DATASYNC_SERVER, v.trim()).apply()

    /** Channel (server group) that owns new storms; persists across updates. */
    var stormChannel: String
        get() = prefs.getString(KEY_STORM_CHANNEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_STORM_CHANNEL, v.trim()).apply()

    /** Last operator name entered on the driver shift form (device-local). */
    var lastOperatorName: String
        get() = prefs.getString(KEY_LAST_OPERATOR_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_OPERATOR_NAME, v.trim()).apply()

    /** Last operator ID entered on the driver shift form (device-local). */
    var lastOperatorId: String
        get() = prefs.getString(KEY_LAST_OPERATOR_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_OPERATOR_ID, v.trim()).apply()

    companion object {
        const val PREFS_NAME = "plowtak_prefs"
        private const val KEY_REPORT_MOVING = "plowtak.report_interval_moving_s"
        private const val KEY_REPORT_STOPPED = "plowtak.report_interval_stopped_s"
        private const val KEY_CYCLE_TIME = "plowtak.cycle_time_min"
        private const val KEY_GREEN_UNTIL = "plowtak.green_until_min"
        private const val KEY_YELLOW_UNTIL = "plowtak.yellow_until_min"
        private const val KEY_RETENTION_H = "plowtak.retention_hours"
        private const val KEY_TREAT_RULE = "plowtak.treat_rule"
        private const val KEY_MATERIAL = "plowtak.material"
        private const val KEY_STALE_AFTER = "plowtak.stale_after_s"
        private const val KEY_GPS_CE = "plowtak.gps_ce_threshold_m"
        private const val KEY_CYCLE_P1 = "plowtak.cycle_p1_min"
        private const val KEY_CYCLE_P2 = "plowtak.cycle_p2_min"
        private const val KEY_CYCLE_P3 = "plowtak.cycle_p3_min"
        private const val KEY_TTS = "plowtak.tts_enabled"
        private const val KEY_TASKING_SNOOZE = "plowtak.tasking_snooze_min"
        private const val KEY_NIGHT = "plowtak.night_mode"
        private const val KEY_DEV_MODE = "plowtak.dev_mode"
        private const val KEY_MAP_HUD = "plowtak.map_hud_enabled"
        private const val KEY_COND_STALE = "plowtak.road_condition_stale_min"
        private const val KEY_LANE_SLACK = "plowtak.lane_slack_m"
        private const val KEY_DIRECTION_SPLIT = "plowtak.direction_split"
        private const val KEY_ROADSNAP = "plowtak.roadsnap_enabled"
        private const val KEY_ROADSNAP_DIR = "plowtak.roadsnap_dir"
        private const val KEY_MAX_PLOW_MPH = "plowtak.max_plow_speed_mph"
        private const val KEY_MAX_SEGMENTS = "plowtak.max_retained_segments"
        private const val KEY_TASK_ESCALATE = "plowtak.task_escalate_min"
        private const val KEY_ROAD_NETWORK_FILE = "plowtak.road_network_file"
        // Keep in sync with MissionCoverageSync.KEY_LAST_* (KeyValuePersistence).
        private const val KEY_MISSION_COV_HASH = "plowtak.mission_cov.last_hash"
        private const val KEY_MISSION_COV_FILENAME = "plowtak.mission_cov.last_filename"
        private const val KEY_DATASYNC_SERVER = "plowtak.datasync.server"
        private const val KEY_STORM_CHANNEL = "plowtak.storm.channel"
        private const val KEY_LAST_OPERATOR_NAME = "plowtak.last_operator_name"
        private const val KEY_LAST_OPERATOR_ID = "plowtak.last_operator_id"
    }
}
