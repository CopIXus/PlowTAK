package com.atakmap.android.plowtak.sync

import android.content.Context
import android.util.Log
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.ops.KeyValuePersistence
import com.atakmap.comms.http.TakHttpClient2
import com.atakmap.comms.http.TakHttpResponse
import java.net.URLEncoder

/**
 * Best-effort TAK Data Sync uploader: every 1 minute while a storm is joined,
 * replace this truck's live coverage GeoJSON, storm-config.json, and hazards
 * on the storm's mission.
 */
class MissionCoverageSync(
    private val appContext: Context,
    private val prefs: KeyValuePersistence,
    private val coverageStore: CoverageStore,
    private val vehicleUid: () -> String,
    private val activeStorm: () -> StormSession?,
    private val hazards: () -> List<HazardEvent> = { emptyList() },
    private val onStormConfigPulled: (StormConfigCodec.StormConfig) -> Unit = {}
) {

    fun onStormStarted(stormId: String) {
        if (stormId.isBlank()) return
        try {
            syncOnce(forceConfig = true)
        } catch (t: Throwable) {
            Log.w(TAG, "onStormStarted sync failed (fail-open)", t)
        }
    }

    fun tick() {
        try {
            syncOnce(forceConfig = false)
        } catch (t: Throwable) {
            Log.w(TAG, "mission coverage tick failed (fail-open)", t)
        }
    }

    fun dispose() {
        try {
            syncOnce(forceConfig = false)
        } catch (t: Throwable) {
            Log.w(TAG, "dispose sync failed (fail-open)", t)
        }
    }

    /** End storm: delete the Data Sync mission (fail-open). */
    fun deleteMissionFor(session: StormSession) {
        try {
            val preferred = prefs.getString(KEY_DATASYNC_SERVER).orEmpty()
            val resolved = TakServerTargets.resolveApiBaseUrl(appContext, preferred) ?: return
            val mission = MissionCoverageCodec.effectiveMissionName(session.id, session.missionName)
            val client = TakHttpClient2.GetHttpClient(resolved.apiBaseUrl)
            val path = "/Marti/api/missions/" + enc(mission)
            if (!client.delete(path)) {
                Log.w(TAG, "DELETE mission $mission failed (ignored)")
            } else {
                Log.i(TAG, "deleted Data Sync mission $mission")
            }
            prefs.remove(KEY_LAST_HASH)
            prefs.remove(KEY_LAST_FILENAME)
            prefs.remove(KEY_LAST_MISSION)
            prefs.remove(KEY_LAST_CONFIG_HASH)
            prefs.remove(KEY_LAST_HAZARD_HASH)
        } catch (t: Throwable) {
            Log.w(TAG, "deleteMissionFor failed (fail-open)", t)
        }
    }

    private fun syncOnce(forceConfig: Boolean) {
        val session = activeStorm() ?: return
        val stormId = session.id
        if (stormId.isBlank()) return

        val preferred = prefs.getString(KEY_DATASYNC_SERVER).orEmpty()
        val resolved = TakServerTargets.resolveApiBaseUrl(appContext, preferred) ?: run {
            Log.w(TAG, "no connected TAK server; skipping mission coverage upload")
            return
        }
        if (resolved.usedFallback) {
            Log.w(TAG, "preferred Data Sync server not connected; using ${resolved.label}")
        }
        val baseUrl = resolved.apiBaseUrl
        val uid = vehicleUid().ifBlank { return }
        val now = System.currentTimeMillis()
        val mission = MissionCoverageCodec.effectiveMissionName(stormId, session.missionName)
        val client = TakHttpClient2.GetHttpClient(baseUrl)
        if (!ensureMission(client, mission, session.channel)) return

        // storm-config.json
        val configBytes = StormConfigCodec.encode(session)
        val configHash = MissionCoverageCodec.sha256Hex(configBytes)
        if (forceConfig || configHash != prefs.getString(KEY_LAST_CONFIG_HASH)) {
            if (uploadContent(client, StormConfigCodec.FILENAME, configHash, uid, configBytes, "application/json")) {
                associateContent(client, mission, configHash)
                prefs.putString(KEY_LAST_CONFIG_HASH, configHash)
            }
        }

        // Coverage chunk
        val hourSegs = MissionCoverageCodec.segmentsInCurrentHour(coverageStore.all(), now)
        val filename = MissionCoverageCodec.liveFilename(uid, now, gzip = true)
        val bytes = MissionCoverageCodec.encodeBytes(stormId, uid, now, hourSegs, gzip = true)
        val hash = MissionCoverageCodec.sha256Hex(bytes)
        val lastHash = prefs.getString(KEY_LAST_HASH)
        val lastFilename = prefs.getString(KEY_LAST_FILENAME)
        if (hash != lastHash || filename != lastFilename) {
            if (uploadContent(client, filename, hash, uid, bytes, "application/geo+json", "gzip")) {
                associateContent(client, mission, hash)
                val prevHash = lastHash
                val prevMission = prefs.getString(KEY_LAST_MISSION)
                if (!prevHash.isNullOrBlank() && prevHash != hash) {
                    deleteOldContent(client, prevMission ?: mission, prevHash)
                }
                prefs.putString(KEY_LAST_HASH, hash)
                prefs.putString(KEY_LAST_FILENAME, filename)
                prefs.putString(KEY_LAST_MISSION, mission)
                Log.i(
                    TAG,
                    "uploaded mission coverage $filename (${bytes.size} B, ${hourSegs.size} segs) → " +
                        "$mission @ ${resolved.label}"
                )
            }
        }

        // Hazards
        val hazBytes = HazardMissionCodec.encode(stormId, hazards())
        val hazHash = MissionCoverageCodec.sha256Hex(hazBytes)
        if (hazHash != prefs.getString(KEY_LAST_HAZARD_HASH)) {
            if (uploadContent(client, HazardMissionCodec.FILENAME, hazHash, uid, hazBytes, "application/geo+json")) {
                associateContent(client, mission, hazHash)
                prefs.putString(KEY_LAST_HAZARD_HASH, hazHash)
            }
        }
    }

    private fun ensureMission(client: TakHttpClient2, mission: String, channel: String): Boolean {
        val path = "/Marti/api/missions/" + enc(mission) +
            if (channel.isNotBlank()) "?group=" + enc(channel) else ""
        try {
            val existing = client.get("/Marti/api/missions/" + enc(mission))
            if (!existing.isNullOrBlank()) return true
        } catch (t: Throwable) {
            Log.w(TAG, "GET mission $mission failed; will try PUT create", t)
        }
        return try {
            val createPath = "/Marti/api/missions/" + enc(mission) +
                if (channel.isNotBlank()) "?defaultRole=MISSION_SUBSCRIBER&group=" + enc(channel)
                else "?defaultRole=MISSION_SUBSCRIBER"
            val resp = client.put(createPath, "application/json", ByteArray(0), null)
            val ok = resp.isOk || resp.isCreated || resp.isStatus(409)
            if (!ok) {
                Log.w(TAG, "PUT mission $mission → ${resp.statusCode} ${resp.reasonPhrase}")
            }
            closeQuietly(resp)
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "PUT mission $mission failed (fail-open)", t)
            false
        }
    }

    private fun uploadContent(
        client: TakHttpClient2,
        filename: String,
        hash: String,
        creatorUid: String,
        bytes: ByteArray,
        contentType: String,
        contentEncoding: String? = null
    ): Boolean {
        val q = "hash=" + enc(hash) +
                "&filename=" + enc(filename) +
                "&creatorUid=" + enc(creatorUid)
        val path = "/Marti/sync/missionupload?$q"
        return try {
            val resp = client.put(path, contentType, bytes, contentEncoding)
            val ok = resp.isOk || resp.isCreated
            if (!ok) {
                Log.w(TAG, "missionupload $filename → ${resp.statusCode} ${resp.reasonPhrase}")
            }
            closeQuietly(resp)
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "missionupload failed for $filename (fail-open)", t)
            false
        }
    }

    private fun associateContent(client: TakHttpClient2, mission: String, hash: String) {
        val path = "/Marti/api/missions/" + enc(mission) + "/contents"
        val body = "{\"hashes\":[\"$hash\"],\"uids\":[]}".toByteArray(Charsets.UTF_8)
        try {
            val resp = client.put(path, "application/json", body, null)
            if (!resp.isOk && !resp.isCreated) {
                Log.w(TAG, "mission contents associate → ${resp.statusCode} ${resp.reasonPhrase}")
            }
            closeQuietly(resp)
        } catch (t: Throwable) {
            Log.w(TAG, "mission contents associate failed (fail-open)", t)
        }
    }

    private fun deleteOldContent(client: TakHttpClient2, mission: String, hash: String) {
        val path = "/Marti/api/missions/" + enc(mission) + "/contents?hash=" + enc(hash)
        try {
            if (!client.delete(path)) {
                Log.w(TAG, "DELETE old mission content hash=$hash failed (ignored)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DELETE old mission content failed (fail-open)", t)
        }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun closeQuietly(resp: TakHttpResponse?) {
        try {
            resp?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "PlowTakMissionSync"
        const val KEY_LAST_HASH = "plowtak.mission_cov.last_hash"
        const val KEY_LAST_FILENAME = "plowtak.mission_cov.last_filename"
        const val KEY_LAST_MISSION = "plowtak.mission_cov.last_mission"
        const val KEY_LAST_CONFIG_HASH = "plowtak.mission_cov.last_config_hash"
        const val KEY_LAST_HAZARD_HASH = "plowtak.mission_cov.last_hazard_hash"
        const val KEY_DATASYNC_SERVER = "plowtak.datasync.server"
        const val PERIOD_MS = 60_000L
    }
}
