package com.atakmap.android.plowtak.sync

import android.content.Context
import android.util.Log
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.ops.KeyValuePersistence
import com.atakmap.comms.http.TakHttpClient2
import com.atakmap.comms.http.TakHttpResponse
import java.net.URLEncoder

/**
 * Best-effort TAK Data Sync uploader: every 5 minutes while a storm is
 * joined, replace this truck's live GeoJSON chunk on the storm's mission
 * (default `plowtak-coverage-{stormId}`, or an explicit mission override).
 *
 * Uploads go to the **user-selected** TAK server (see
 * [KEY_DATASYNC_SERVER]); if that server is down, the first connected
 * server is used as a soft fallback. Multi-server fan-out is intentionally
 * out of scope for now.
 *
 * Fail-open — any server / mission API failure is logged and ignored so
 * local coverage recording never crashes.
 */
class MissionCoverageSync(
    private val appContext: Context,
    private val prefs: KeyValuePersistence,
    private val coverageStore: CoverageStore,
    private val vehicleUid: () -> String,
    private val activeStorm: () -> StormSession?
) {

    /** Ensure mission exists and push the current hour chunk (fail-open). */
    fun onStormStarted(stormId: String) {
        if (stormId.isBlank()) return
        try {
            syncOnce()
        } catch (t: Throwable) {
            Log.w(TAG, "onStormStarted sync failed (fail-open)", t)
        }
    }

    /** Periodic tick from the controller timer — no-op without an active storm. */
    fun tick() {
        try {
            syncOnce()
        } catch (t: Throwable) {
            Log.w(TAG, "mission coverage tick failed (fail-open)", t)
        }
    }

    /** Optional final attempt; never throws. */
    fun dispose() {
        try {
            syncOnce()
        } catch (t: Throwable) {
            Log.w(TAG, "dispose sync failed (fail-open)", t)
        }
    }

    private fun syncOnce() {
        val session = activeStorm() ?: return
        val stormId = session.id
        if (stormId.isBlank()) return

        val preferred = prefs.getString(KEY_DATASYNC_SERVER).orEmpty()
        val resolved = TakServerTargets.resolveApiBaseUrl(appContext, preferred) ?: run {
            Log.w(TAG, "no connected TAK server; skipping mission coverage upload")
            return
        }
        if (resolved.usedFallback) {
            Log.w(
                TAG,
                "preferred Data Sync server not connected; using ${resolved.label}"
            )
        }
        val baseUrl = resolved.apiBaseUrl
        val uid = vehicleUid().ifBlank { return }
        val now = System.currentTimeMillis()
        val hourSegs = MissionCoverageCodec.segmentsInCurrentHour(coverageStore.all(), now)
        val filename = MissionCoverageCodec.liveFilename(uid, now, gzip = true)
        val bytes = MissionCoverageCodec.encodeBytes(
            stormId, uid, now, hourSegs, gzip = true
        )
        val hash = MissionCoverageCodec.sha256Hex(bytes)
        val lastHash = prefs.getString(KEY_LAST_HASH)
        val lastFilename = prefs.getString(KEY_LAST_FILENAME)
        if (hash == lastHash && filename == lastFilename) {
            Log.d(TAG, "coverage chunk unchanged ($filename); skip upload")
            return
        }

        val mission = MissionCoverageCodec.effectiveMissionName(stormId, session.missionName)
        val client = TakHttpClient2.GetHttpClient(baseUrl)
        if (!ensureMission(client, mission)) return
        if (!uploadContent(client, filename, hash, uid, bytes)) return
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

    private fun ensureMission(client: TakHttpClient2, mission: String): Boolean {
        val path = "/Marti/api/missions/" + enc(mission)
        try {
            val existing = client.get(path)
            if (!existing.isNullOrBlank()) return true
        } catch (t: Throwable) {
            Log.w(TAG, "GET mission $mission failed; will try PUT create", t)
        }
        return try {
            val resp = client.put(path, "application/json", ByteArray(0), null)
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
        bytes: ByteArray
    ): Boolean {
        val q = "hash=" + enc(hash) +
                "&filename=" + enc(filename) +
                "&creatorUid=" + enc(creatorUid)
        val path = "/Marti/sync/missionupload?$q"
        return try {
            // Raw body + Content-Encoding gzip; servers that require multipart
            // assetfile will fail here — fail-open with a warning.
            val resp = client.put(path, "application/geo+json", bytes, "gzip")
            val ok = resp.isOk || resp.isCreated
            if (!ok) {
                Log.w(
                    TAG,
                    "missionupload $filename → ${resp.statusCode} ${resp.reasonPhrase}"
                )
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
                Log.w(
                    TAG,
                    "mission contents associate → ${resp.statusCode} ${resp.reasonPhrase}"
                )
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
        /** ATAK connect string of the preferred Data Sync server (empty = first connected). */
        const val KEY_DATASYNC_SERVER = "plowtak.datasync.server"
        const val PERIOD_MS = 5L * 60_000L
    }
}
