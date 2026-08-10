package com.atakmap.android.plowtak.prefs

import android.content.Context
import android.util.Log
import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.coremap.filesystem.FileSystemUtils
import java.io.File

/**
 * Mirrors [plowtak_prefs] to ATAK shared storage so vehicle/setup survives
 * plugin uninstall/reinstall and clean APK sideloads.
 *
 * Path: `{atak}/tools/plowtak/settings.json` (via [FileSystemUtils.getItem]).
 * Normal in-place updates already keep SharedPreferences; this file is the
 * belt-and-suspenders copy for wipe/reinstall and fleet cloning.
 */
object PlowTakSettingsBackup {

    private const val TAG = "PlowTakSettingsBackup"
    const val RELATIVE_DIR = "plowtak"
    const val FILE_NAME = "settings.json"
    private const val KEY_VERSION = "_plowtak_settings_version"
    private const val VERSION = 1

    fun settingsFile(): File =
        File(File(FileSystemUtils.getItem("tools"), RELATIVE_DIR), FILE_NAME)

    /** Write current prefs snapshot. Fail-open. */
    fun export(pluginContext: Context) {
        try {
            val prefs = pluginContext.getSharedPreferences(
                PlowTakPreferences.PREFS_NAME, Context.MODE_PRIVATE
            )
            val file = settingsFile()
            file.parentFile?.mkdirs()
            val payload = LinkedHashMap<String, Any?>()
            payload[KEY_VERSION] = VERSION.toDouble()
            for ((k, v) in prefs.all) {
                if (k == null || v == null) continue
                // Skip ephemeral upload/pull hashes — recreated each storm.
                if (k.startsWith("plowtak.mission_cov.") || k.startsWith("plowtak.mission_pull.")) {
                    continue
                }
                payload[k] = when (v) {
                    is Boolean, is String -> v
                    is Int -> v.toDouble()
                    is Long -> v.toDouble()
                    is Float -> v.toDouble()
                    is Double -> v
                    else -> v.toString()
                }
            }
            file.writeText(encodeObject(payload))
            Log.i(TAG, "exported ${payload.size} keys → ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "settings export failed (fail-open)", t)
        }
    }

    /**
     * If SharedPreferences look unconfigured but a backup file exists,
     * restore it. Returns true when a restore was applied.
     */
    fun restoreIfNeeded(pluginContext: Context): Boolean {
        return try {
            val prefs = pluginContext.getSharedPreferences(
                PlowTakPreferences.PREFS_NAME, Context.MODE_PRIVATE
            )
            if (prefs.getBoolean("plowtak.cap.configured", false)) return false
            val file = settingsFile()
            if (!file.isFile || file.length() == 0L) return false
            val root = MiniJson.parseObject(file.readText()) ?: run {
                Log.w(TAG, "backup unreadable: ${file.absolutePath}")
                return false
            }
            val editor = prefs.edit()
            var count = 0
            for ((k, v) in root) {
                if (k.startsWith("_")) continue
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is String -> editor.putString(k, v)
                    is Double -> {
                        // Prefer int when whole number (cycle times, etc.).
                        if (k.contains("width") || k.contains("retention") ||
                            k.contains("gps_ce") || k.endsWith("_m")
                        ) {
                            editor.putFloat(k, v.toFloat())
                        } else if (v == v.toLong().toDouble()) {
                            editor.putInt(k, v.toInt())
                        } else {
                            editor.putFloat(k, v.toFloat())
                        }
                    }
                    null -> editor.remove(k)
                }
                count++
            }
            editor.apply()
            Log.i(TAG, "restored $count keys from ${file.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "settings restore failed (fail-open)", t)
            false
        }
    }

    private fun encodeObject(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(",\n")
            first = false
            sb.append("  ").append(MiniJson.quote(k)).append(": ")
            when (v) {
                null -> sb.append("null")
                is Boolean -> sb.append(v)
                is Number -> sb.append(v)
                is String -> sb.append(MiniJson.quote(v))
                else -> sb.append(MiniJson.quote(v.toString()))
            }
        }
        sb.append("\n}\n")
        return sb.toString()
    }
}
