package com.atakmap.android.plowtak.sync

import android.content.Context
import android.util.Log
import com.atakmap.android.plowtak.coverage.CoverageStore
import com.atakmap.android.plowtak.gis.MiniJson
import com.atakmap.android.plowtak.model.HazardEvent
import com.atakmap.android.plowtak.model.PlowVehicle
import com.atakmap.android.plowtak.model.RoadConditionReport
import com.atakmap.android.plowtak.model.SpecialZone
import com.atakmap.android.plowtak.model.StormSession
import com.atakmap.android.plowtak.model.TaskEvent
import com.atakmap.android.plowtak.model.TreatSegment
import com.atakmap.android.plowtak.ops.KeyValuePersistence
import com.atakmap.android.plowtak.ops.RouteAssignment
import com.atakmap.comms.http.TakHttpClient2
import com.atakmap.comms.http.TakHttpResponse
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * TAK Data Sync bridge while a storm is joined:
 *  - **Upload** this unit's coverage, status, hazards, conditions (TTL-filtered),
 *    and ops snapshot.
 *  - **Pull** peer files from the mission and hand them to [MissionPullSink].
 *
 * Real-unit location PLI stays on TAK CoT; everything else in this class is
 * mission content — not CoT broadcast.
 */
class MissionCoverageSync(
    private val appContext: Context,
    private val prefs: KeyValuePersistence,
    private val coverageStore: CoverageStore,
    private val vehicleUid: () -> String,
    private val activeStorm: () -> StormSession?,
    private val hazards: () -> List<HazardEvent> = { emptyList() },
    private val conditions: () -> List<RoadConditionReport> = { emptyList() },
    private val selfStatus: () -> PlowVehicle? = { null },
    private val routes: () -> List<RouteAssignment> = { emptyList() },
    private val zones: () -> List<SpecialZone> = { emptyList() },
    private val tasks: () -> List<TaskEvent> = { emptyList() },
    private val snoozes: () -> Map<String, Long> = { emptyMap() },
    private val cycleMinutesFor: ((TreatSegment) -> Int)? = null,
    private val sink: MissionPullSink = MissionPullSink.NOOP
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
        // Plugin teardown runs on the main thread (MapComponent.onDestroy).
        // Never call Marti/OkHttp here — StrictMode throws NetworkOnMainThreadException.
        // Normal ticks / onStormStarted already push; skip a final flush on unload.
    }

    /**
     * PlowTAK missions on the preferred/first connected server, for the
     * "select Data Sync" picker. Blocking — call from a background thread.
     */
    fun listPlowTakMissions(): List<String> {
        return try {
            val preferred = prefs.getString(KEY_DATASYNC_SERVER).orEmpty()
            val resolved = TakServerTargets.resolveApiBaseUrl(appContext, preferred)
                ?: return emptyList()
            val client = openClient(resolved)
            val body = client.get(client.getUrl("/api/missions"))
            if (body.isNullOrBlank()) return emptyList()
            val root = MiniJson.parseObject(body) ?: return emptyList()
            val data = MiniJson.array(root["data"]) ?: return emptyList()
            data.mapNotNull { item ->
                val m = MiniJson.obj(item) ?: return@mapNotNull null
                MiniJson.string(m["name"])
            }.filter { it.contains("plowtak", ignoreCase = true) }
                .distinct()
                .sorted()
        } catch (t: Throwable) {
            Log.w(TAG, "listPlowTakMissions failed", t)
            emptyList()
        }
    }

    /**
     * Fetch a mission's `storm-config.json` so a client can join a storm it
     * never heard on CoT. Blocking — call from a background thread.
     */
    fun fetchStormConfig(missionName: String): StormConfigCodec.StormConfig? {
        return try {
            val preferred = prefs.getString(KEY_DATASYNC_SERVER).orEmpty()
            val resolved = TakServerTargets.resolveApiBaseUrl(appContext, preferred) ?: return null
            val client = openClient(resolved)
            val entry = listMissionContents(client, missionName).firstOrNull {
                it.filename == StormConfigCodec.FILENAME ||
                    it.filename.endsWith("storm-config.json")
            } ?: return null
            val bytes = downloadContent(client, entry.hash) ?: return null
            StormConfigCodec.decode(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "fetchStormConfig($missionName) failed", t)
            null
        }
    }

    /**
     * End storm: clear local upload bookkeeping only. The Data Sync mission
     * is deliberately NEVER deleted from the plugin — a mission (and all the
     * storm data in it) can only be removed by an admin on the TAK server,
     * so nobody can accidentally destroy a storm mid-operation.
     */
    fun onStormEnded() {
        clearUploadPrefs()
    }

    private fun syncOnce(forceConfig: Boolean) {
        val session = activeStorm() ?: return
        val stormId = session.id
        if (stormId.isBlank()) return

        val preferred = prefs.getString(KEY_DATASYNC_SERVER).orEmpty()
        val resolved = TakServerTargets.resolveApiBaseUrl(appContext, preferred) ?: run {
            Log.w(TAG, "no connected TAK server / invalid Marti API URL; skipping mission sync")
            return
        }
        if (resolved.usedFallback) {
            Log.w(TAG, "preferred Data Sync server not connected; using ${resolved.label}")
        }
        Log.i(TAG, "syncOnce → GetHttpClient ${resolved.apiBaseUrl} (${resolved.label})")
        val uid = vehicleUid().ifBlank { return }
        val now = System.currentTimeMillis()
        val mission = MissionCoverageCodec.effectiveMissionName(stormId, session.missionName)
        val client = try {
            openClient(resolved)
        } catch (t: Throwable) {
            Log.e(TAG, "TakHttpClient2 failed for ${resolved.apiBaseUrl}", t)
            return
        }
        if (!ensureMission(client, mission, session.channel)) {
            Log.w(TAG, "ensureMission failed for $mission — no Data Sync upload this tick")
            return
        }

        // Pull peers first so local merge sees remote ops before we overwrite.
        pullMission(client, mission, uid, now, session)

        // Purge expired local conditions before encoding.
        val ttl = session.roadConditionTtlMinutes
        val freshConditions = ConditionMissionCodec.filterFresh(conditions(), now, ttl)
        if (freshConditions.size != conditions().size) {
            sink.onConditionsPruned(freshConditions)
        }

        uploadIfChanged(
            client, mission, StormConfigCodec.FILENAME,
            StormConfigCodec.encode(session), uid, "application/json",
            KEY_LAST_CONFIG_HASH, force = forceConfig
        )

        // Upload EVERY hour bucket that has local segments, not just the
        // current hour: coverage painted while offline is persisted to disk
        // (CoverageStore) and catches up here when connectivity returns —
        // same recover-on-reconnect behavior as the Data Sync plugin.
        uploadCoverageHistory(client, mission, stormId, uid, now)

        selfStatus()?.let { status ->
            uploadIfChanged(
                client, mission, UnitStatusMissionCodec.statusFilename(uid),
                UnitStatusMissionCodec.encodeStatus(status, stormId), uid, "application/json",
                KEY_LAST_STATUS_HASH
            )
        }

        val myHazards = hazards().filter {
            it.reporterUid == uid || it.reporterUid.isEmpty()
        }
        uploadIfChanged(
            client, mission, HazardMissionCodec.filename(uid),
            HazardMissionCodec.encode(stormId, myHazards), uid, "application/geo+json",
            KEY_LAST_HAZARD_HASH,
            associateUids = myHazards.map { it.uid }
        )

        val myConditions = freshConditions.filter {
            it.reporterUid == uid || it.reporterUid.isEmpty()
        }
        uploadIfChanged(
            client, mission, ConditionMissionCodec.filename(uid),
            ConditionMissionCodec.encode(stormId, myConditions, now, ttl),
            uid, "application/geo+json", KEY_LAST_CONDITION_HASH
        )

        uploadIfChanged(
            client, mission, OpsMissionCodec.filename(uid),
            OpsMissionCodec.encode(stormId, routes(), zones(), tasks(), snoozes()),
            uid, "application/json", KEY_LAST_OPS_HASH
        )
    }

    /** One file attached to a mission. */
    internal data class MissionContentEntry(
        val filename: String,
        val hash: String,
        val creatorUid: String
    )

    /**
     * List a mission's attached files. TAK Server's documented listing is the
     * mission detail (`GET /api/missions/{name}` → `data[0].contents[]`);
     * older builds also answer `GET …/contents`, so fall back to that.
     */
    private fun listMissionContents(
        client: TakHttpClient2,
        mission: String
    ): List<MissionContentEntry> {
        val detail = try {
            client.get(client.getUrl("/api/missions/" + enc(mission)))
        } catch (t: Throwable) {
            Log.w(TAG, "GET mission detail $mission failed", t)
            null
        }
        // Note: there is no GET mapping for /api/missions/{name}/contents on
        // current TAK Server (only PUT/DELETE) — the detail response is the
        // only way to enumerate contents.
        return parseContents(detail)
    }

    private fun parseContents(body: String?): List<MissionContentEntry> {
        if (body.isNullOrBlank()) return emptyList()
        val root = MiniJson.parseObject(body) ?: return emptyList()
        // Mission detail: {"data":[{...,"contents":[{"data":{name,hash,...},"creatorUid":...}]}]}
        // Contents listing: {"contents":[...]} or {"data":[...]}
        val rawContents: List<Any?> =
            MiniJson.array(root["contents"])
                ?: MiniJson.array(root["data"])?.let { dataArr ->
                    val firstObj = dataArr.firstOrNull()?.let { MiniJson.obj(it) }
                    // Detail response: unwrap mission → contents.
                    MiniJson.array(firstObj?.get("contents")) ?: dataArr
                }
                ?: emptyList()
        val out = ArrayList<MissionContentEntry>()
        for (item in rawContents) {
            val entry = MiniJson.obj(item) ?: continue
            val data = MiniJson.obj(entry["data"]) ?: entry
            val hash = MiniJson.string(data["hash"])
                ?: MiniJson.string(entry["hash"])
                ?: continue
            val filename = MiniJson.string(data["name"])
                ?: MiniJson.string(data["filename"])
                ?: MiniJson.string(entry["name"])
                ?: ""
            val creator = MiniJson.string(data["creatorUid"])
                ?: MiniJson.string(entry["creatorUid"])
                ?: ""
            out.add(MissionContentEntry(filename, hash, creator))
        }
        return out
    }

    private fun pullMission(
        client: TakHttpClient2,
        mission: String,
        selfUid: String,
        nowMs: Long,
        session: StormSession
    ) {
        val entries = listMissionContents(client, mission)
        for ((filename, hash, creator) in entries) {
            // Skip our own uploads (we already have them locally).
            if (creator == selfUid) continue
            val lastSeen = prefs.getString(KEY_PULL_PREFIX + hash)
            if (lastSeen == "1") continue
            val bytes = downloadContent(client, hash) ?: continue
            try {
                applyPulled(filename, bytes, nowMs, session)
                prefs.putString(KEY_PULL_PREFIX + hash, "1")
            } catch (t: Throwable) {
                Log.w(TAG, "apply pulled $filename failed", t)
            }
        }
    }

    private fun applyPulled(
        filename: String,
        bytes: ByteArray,
        nowMs: Long,
        session: StormSession
    ) {
        when {
            filename == StormConfigCodec.FILENAME || filename.endsWith("storm-config.json") -> {
                val cfg = StormConfigCodec.decode(bytes) ?: return
                sink.onStormConfigPulled(cfg)
            }
            filename.endsWith("-status.json") -> {
                val v = UnitStatusMissionCodec.decodeStatus(bytes) ?: return
                sink.onUnitStatusPulled(v)
            }
            filename.endsWith("-hazards.geojson") || filename == HazardMissionCodec.FILENAME -> {
                sink.onHazardsPulled(HazardMissionCodec.decode(bytes))
            }
            filename.endsWith("-conditions.geojson") -> {
                val ttl = session.roadConditionTtlMinutes
                val list = ConditionMissionCodec.decode(bytes, nowMs)
                sink.onConditionsPulled(ConditionMissionCodec.filterFresh(list, nowMs, ttl))
            }
            filename.endsWith("-ops.json") -> {
                val snap = OpsMissionCodec.decode(bytes) ?: return
                sink.onOpsPulled(snap)
            }
            filename.endsWith("-live.geojson.gz") || filename.endsWith("-live.geojson") -> {
                val jsonBytes = decodeMaybeGzip(filename, bytes)
                val segs = decodeCoverageGeoJson(jsonBytes)
                if (segs.isNotEmpty()) sink.onCoveragePulled(segs)
            }
        }
    }

    private fun downloadContent(client: TakHttpClient2, hash: String): ByteArray? {
        return try {
            val path = client.getUrl("/sync/content?hash=" + enc(hash))
            val resp = client.executeGet(path, emptyMap())
            if (!resp.isOk) {
                Log.w(TAG, "download hash=$hash → ${resp.statusCode}")
                closeQuietly(resp)
                return null
            }
            val body = resp.body ?: run {
                closeQuietly(resp)
                return null
            }
            val out = ByteArrayOutputStream()
            body.use { it.copyTo(out) }
            closeQuietly(resp)
            out.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "download content failed hash=$hash", t)
            null
        }
    }

    private fun ensureMission(client: TakHttpClient2, mission: String, channel: String): Boolean {
        try {
            // Absolute URL required: TakHttpClient2 never joins base + path itself.
            val existing = client.get(client.getUrl("/api/missions/" + enc(mission)))
            if (!existing.isNullOrBlank()) {
                Log.i(TAG, "Data Sync mission exists: $mission")
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET mission $mission failed; will try PUT create", t)
        }
        val uid = vehicleUid().ifBlank { "plowtak" }
        val group = channel.trim()
        // tool=public marks the mission for the Data Sync plugin's mission
        // list. Try with the storm channel as group first; some servers 500
        // when the group doesn't exactly match a server group, so fall back
        // to creating without a group (server applies the cert's defaults).
        val queryVariants = buildList {
            if (group.isNotEmpty()) {
                add(
                    "?creatorUid=" + enc(uid) +
                        "&tool=public" +
                        "&group=" + enc(group) +
                        "&defaultRole=MISSION_SUBSCRIBER"
                )
            }
            add("?creatorUid=" + enc(uid) + "&tool=public&defaultRole=MISSION_SUBSCRIBER")
            add("?creatorUid=" + enc(uid))
        }
        for (query in queryVariants) {
            try {
                val createPath = client.getUrl("/api/missions/" + enc(mission) + query)
                Log.i(TAG, "creating Data Sync mission $mission via PUT $createPath")
                // Body MUST be valid JSON: TAK Server runs Jackson on the body
                // whenever Content-Type contains application/json, and a
                // zero-byte body throws -> generic 500 code 6 (MissionApi.java
                // doCreateMissionAllowDupe). "{}" parses to an empty Mission so
                // the query parameters drive the create.
                val resp = client.put(
                    createPath, "application/json", "{}".toByteArray(), null
                )
                val ok = resp.isOk || resp.isCreated || resp.isStatus(409)
                if (ok) {
                    Log.i(TAG, "Data Sync mission ready: $mission (HTTP ${resp.statusCode})")
                    closeQuietly(resp)
                    return true
                }
                val errorBody = try {
                    resp.getStringEntity()
                } catch (_: Throwable) {
                    ""
                }
                Log.w(
                    TAG,
                    "PUT mission $mission → ${resp.statusCode} ${resp.reasonPhrase}" +
                        " body=${errorBody?.take(500)}"
                )
                closeQuietly(resp)
            } catch (t: Throwable) {
                Log.w(TAG, "PUT mission $mission failed (fail-open)", t)
            }
        }
        return false
    }

    /**
     * Upload one plain GeoJSON chunk per UTC hour that has self segments.
     * Deterministic encoding (generatedAt = hour start) keeps hashes stable,
     * so unchanged hours are skipped and only new painting re-uploads.
     * Plain `.geojson` (no gzip / Content-Encoding) so Marti, Data Sync, and
     * peer pulls all see the same bytes — gzip+Content-Encoding caused
     * ADD→REMOVE churn and decode failures on peers.
     */
    private fun uploadCoverageHistory(
        client: TakHttpClient2,
        mission: String,
        stormId: String,
        uid: String,
        nowMs: Long
    ) {
        val session = activeStorm()
        val cycleMinutes = session?.cycleMinutes ?: 45
        val retentionHours = session?.coverageRetentionHours
            ?: StormSession.DEFAULT_COVERAGE_RETENTION_HOURS
        val segs = coverageStore.all().filter { it.vehicleUid == uid }
        if (segs.isEmpty()) return
        val earliest = maxOf(
            segs.minOf { it.startTimeMs },
            nowMs - MAX_COVERAGE_LOOKBACK_MS
        )
        var hourStart = MissionCoverageCodec.hourStartMs(earliest)
        val lastHour = MissionCoverageCodec.hourStartMs(nowMs)
        val uploaded = ArrayList<String>()
        while (hourStart <= lastHour) {
            val hourEnd = hourStart + 3_600_000L
            val inHour = segs.filter { it.startTimeMs < hourEnd && it.endTimeMs >= hourStart }
            if (inHour.isNotEmpty()) {
                val filename = MissionCoverageCodec.liveFilename(uid, hourStart, gzip = false)
                // Wall-clock styleNowMs so older hour files recolor green→red.
                val bytes = MissionCoverageCodec.encodeBytes(
                    stormId, uid, hourStart, inHour,
                    gzip = false,
                    styleNowMs = nowMs,
                    cycleMinutes = cycleMinutes,
                    retentionHours = retentionHours,
                    cycleMinutesFor = cycleMinutesFor
                )
                uploadHourChunk(client, mission, filename, bytes, uid)
                uploaded.add(filename)
            }
            hourStart = hourEnd
        }
        if (uploaded.isNotEmpty()) {
            prefs.putString(KEY_COV_INDEX, uploaded.joinToString(","))
        }
    }

    private fun uploadHourChunk(
        client: TakHttpClient2,
        mission: String,
        filename: String,
        bytes: ByteArray,
        uid: String
    ) {
        val hashKey = KEY_COV_HASH_PREFIX + filename
        val serverHashKey = hashKey + ".server"
        val hash = MissionCoverageCodec.sha256Hex(bytes)
        val prevHash = prefs.getString(hashKey)
        if (hash == prevHash) return
        // No Content-Encoding — body is plain GeoJSON matching the filename.
        val serverHash =
            uploadContent(client, filename, hash, uid, bytes, "application/geo+json", null)
        if (serverHash != null) {
            associateContent(client, mission, serverHash)
            val prevServerHash = prefs.getString(serverHashKey)
            if (!prevServerHash.isNullOrBlank() && prevServerHash != serverHash) {
                deleteOldContent(client, mission, prevServerHash)
            }
            // Drop legacy gzip twins from older builds (same hour, .geojson.gz).
            if (!filename.endsWith(".gz")) {
                purgeOtherHashesForFilename(client, mission, "$filename.gz", keepHash = "")
            }
            prefs.putString(hashKey, hash)
            prefs.putString(serverHashKey, serverHash)
            Log.i(TAG, "uploaded coverage $filename (${bytes.size} B) → $mission")
        }
    }

    private fun uploadIfChanged(
        client: TakHttpClient2,
        mission: String,
        filename: String,
        bytes: ByteArray,
        creatorUid: String,
        contentType: String,
        hashKey: String,
        force: Boolean = false,
        contentEncoding: String? = null,
        associateUids: List<String> = emptyList()
    ) {
        val hash = MissionCoverageCodec.sha256Hex(bytes)
        if (!force && hash == prefs.getString(hashKey)) return
        val serverHashKey = hashKey + ".server"
        val serverHash =
            uploadContent(client, filename, hash, creatorUid, bytes, contentType, contentEncoding)
        if (serverHash != null) {
            associateContent(client, mission, serverHash, associateUids)
            // Replace prior versions of this same logical file. Without this,
            // every hash change left another copy in the mission and Data Sync
            // peers rendered N identical markers (e.g. five "Wet" at one time).
            val prevServerHash = prefs.getString(serverHashKey)
            if (!prevServerHash.isNullOrBlank() && prevServerHash != serverHash) {
                deleteOldContent(client, mission, prevServerHash)
            }
            purgeOtherHashesForFilename(client, mission, filename, serverHash)
            prefs.putString(hashKey, hash)
            prefs.putString(serverHashKey, serverHash)
            Log.i(TAG, "uploaded $filename (${bytes.size} B) → $mission")
        }
    }

    /**
     * Drop every mission-content hash that still uses [filename] except
     * [keepHash]. Cleans orphans left before we tracked server hashes.
     */
    private fun purgeOtherHashesForFilename(
        client: TakHttpClient2,
        mission: String,
        filename: String,
        keepHash: String
    ) {
        try {
            val entries = listMissionContents(client, mission)
            for (entry in entries) {
                if (entry.filename == filename && entry.hash != keepHash) {
                    deleteOldContent(client, mission, entry.hash)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "purge stale $filename hashes failed (fail-open)", t)
        }
    }

    private fun uploadNamedIfChanged(
        client: TakHttpClient2,
        mission: String,
        filename: String,
        bytes: ByteArray,
        creatorUid: String,
        contentType: String,
        contentEncoding: String?,
        hashKey: String,
        filenameKey: String,
        missionKey: String
    ) {
        val hash = MissionCoverageCodec.sha256Hex(bytes)
        val serverHashKey = hashKey + ".server"
        val lastHash = prefs.getString(hashKey)
        val lastFilename = prefs.getString(filenameKey)
        if (hash == lastHash && filename == lastFilename) return
        val serverHash =
            uploadContent(client, filename, hash, creatorUid, bytes, contentType, contentEncoding)
        if (serverHash != null) {
            associateContent(client, mission, serverHash)
            val prevServerHash = prefs.getString(serverHashKey) ?: lastHash
            val prevMission = prefs.getString(missionKey)
            // Replace only within the same file (same hour); when the hour
            // rolls the filename changes and the old chunk stays in the
            // mission so late subscribers still see the whole storm.
            if (!prevServerHash.isNullOrBlank() && prevServerHash != serverHash &&
                filename == lastFilename
            ) {
                deleteOldContent(client, prevMission ?: mission, prevServerHash)
            }
            prefs.putString(hashKey, hash)
            prefs.putString(serverHashKey, serverHash)
            prefs.putString(filenameKey, filename)
            prefs.putString(missionKey, mission)
            Log.i(TAG, "uploaded $filename (${bytes.size} B) → $mission")
        }
    }

    /**
     * Upload bytes to Enterprise Sync. TAK Server rejects PUT on both
     * /sync/upload and /sync/missionupload with 405 (UploadServlet.doPut),
     * and /sync/missionupload POST only accepts multipart/form-data. The
     * raw-body path is POST /sync/upload with Metadata-field query params
     * (unrecognized params are a 400). The server computes the stored
     * file's hash itself and returns it in the response JSON; that hash is
     * the one the mission-content association must reference.
     *
     * @return the server-assigned content hash, or null on failure.
     */
    private fun uploadContent(
        client: TakHttpClient2,
        filename: String,
        hash: String,
        creatorUid: String,
        bytes: ByteArray,
        contentType: String,
        contentEncoding: String? = null
    ): String? {
        val q = "name=" + enc(filename) +
                "&MIMEType=" + enc(contentType) +
                "&CreatorUid=" + enc(creatorUid)
        val path = client.getUrl("/sync/upload?$q")
        return try {
            val resp = client.post(path, contentType, bytes, contentEncoding)
            if (!resp.isOk && !resp.isCreated) {
                Log.w(TAG, "sync upload $filename → ${resp.statusCode} ${resp.reasonPhrase}")
                closeQuietly(resp)
                return null
            }
            val body = try {
                resp.getStringEntity()
            } catch (_: Throwable) {
                null
            }
            closeQuietly(resp)
            val serverHash = body
                ?.let { MiniJson.parseObject(it) }
                ?.let { MiniJson.string(it["Hash"]) }
            if (serverHash == null) {
                Log.w(TAG, "sync upload $filename: no Hash in response; using local hash")
            } else if (serverHash != hash) {
                Log.i(TAG, "sync upload $filename: server hash differs from local")
            }
            serverHash ?: hash
        } catch (t: Throwable) {
            Log.w(TAG, "sync upload failed for $filename (fail-open)", t)
            null
        }
    }

    private fun associateContent(
        client: TakHttpClient2,
        mission: String,
        hash: String,
        uids: List<String> = emptyList()
    ) {
        val path = client.getUrl("/api/missions/" + enc(mission) + "/contents")
        val uidJson = uids.filter { it.isNotBlank() }
            .distinct()
            .joinToString(",") { "\"${it.replace("\"", "").replace("\\", "")}\"" }
        val body = "{\"hashes\":[\"$hash\"],\"uids\":[$uidJson]}".toByteArray(Charsets.UTF_8)
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
        val path = client.getUrl("/api/missions/" + enc(mission) + "/contents?hash=" + enc(hash))
        try {
            if (!client.delete(path)) {
                Log.w(TAG, "DELETE old mission content hash=$hash failed (ignored)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DELETE old mission content failed (fail-open)", t)
        }
    }

    private fun clearUploadPrefs() {
        prefs.getString(KEY_COV_INDEX)?.split(',')?.forEach { name ->
            if (name.isNotBlank()) {
                prefs.remove(KEY_COV_HASH_PREFIX + name)
                prefs.remove(KEY_COV_HASH_PREFIX + name + ".server")
            }
        }
        listOf(
            KEY_COV_INDEX,
            KEY_LAST_HASH, KEY_LAST_FILENAME, KEY_LAST_MISSION,
            KEY_LAST_CONFIG_HASH, KEY_LAST_HAZARD_HASH, KEY_LAST_CONDITION_HASH,
            KEY_LAST_STATUS_HASH, KEY_LAST_OPS_HASH
        ).forEach {
            prefs.remove(it)
            prefs.remove("$it.server")
        }
    }

    /**
     * ATAK appends `:{apiPort}/Marti` to [TakServerTargets.ResolveResult.apiBaseUrl].
     * Prefer the connect-string overload so client certs match the TAK server.
     */
    private fun openClient(resolved: TakServerTargets.ResolveResult): TakHttpClient2 {
        val connect = resolved.connectString
        return if (connect.isNotBlank()) {
            TakHttpClient2.GetHttpClient(resolved.apiBaseUrl, connect)
        } else {
            TakHttpClient2.GetHttpClient(resolved.apiBaseUrl)
        }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun sanitize(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "unknown" }

    private fun closeQuietly(resp: TakHttpResponse?) {
        try {
            resp?.close()
        } catch (_: Throwable) {
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    /**
     * Peer downloads may be still-gzipped (older uploads) or already plain
     * JSON (Marti stripped Content-Encoding). Prefer gunzip for `.gz` names,
     * but fall back to raw bytes when the payload is already JSON.
     */
    private fun decodeMaybeGzip(filename: String, bytes: ByteArray): ByteArray {
        if (!filename.endsWith(".gz")) return bytes
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return gunzip(bytes)
        }
        // Already plain (or server transparently decompressed).
        return try {
            gunzip(bytes)
        } catch (_: Throwable) {
            bytes
        }
    }

    /** Best-effort GeoJSON coverage FeatureCollection → [TreatSegment] list. */
    private fun decodeCoverageGeoJson(bytes: ByteArray): List<TreatSegment> {
        val root = MiniJson.parseObject(bytes.toString(Charsets.UTF_8)) ?: return emptyList()
        val features = MiniJson.array(root["features"]) ?: return emptyList()
        val out = ArrayList<TreatSegment>()
        for (f in features) {
            val feat = MiniJson.obj(f) ?: continue
            val props = MiniJson.obj(feat["properties"]) ?: continue
            val geom = MiniJson.obj(feat["geometry"])
            val coords = MiniJson.array(geom?.get("coordinates")) ?: continue
            val points = ArrayList<com.atakmap.android.plowtak.model.TrackPoint>()
            val startMs = (props["startMs"] as? Number)?.toLong() ?: 0L
            val endMs = (props["endMs"] as? Number)?.toLong() ?: startMs
            coords.forEachIndexed { idx, c ->
                val pair = MiniJson.array(c) ?: return@forEachIndexed
                val lon = MiniJson.double(pair.getOrNull(0)) ?: return@forEachIndexed
                val lat = MiniJson.double(pair.getOrNull(1)) ?: return@forEachIndexed
                val t = if (coords.size <= 1) startMs
                else startMs + ((endMs - startMs) * idx / (coords.size - 1))
                points.add(com.atakmap.android.plowtak.model.TrackPoint(lat, lon, t, Double.NaN))
            }
            if (points.size < 2) continue
            val id = MiniJson.string(props["id"]) ?: continue
            val vehicle = MiniJson.string(props["vehicle"]) ?: continue
            val material = com.atakmap.android.plowtak.model.MaterialMode.fromWireName(
                MiniJson.string(props["material"])
            ) ?: com.atakmap.android.plowtak.model.MaterialMode.PLOW_ONLY
            out.add(
                TreatSegment(
                    id = id,
                    vehicleUid = vehicle,
                    callsign = MiniJson.string(props["callsign"]) ?: vehicle,
                    stormId = MiniJson.string(props["storm"]) ?: "",
                    operatorId = "",
                    material = material,
                    widthM = MiniJson.double(props["widthM"]) ?: 3.0,
                    points = points,
                    startTimeMs = startMs,
                    endTimeMs = endMs
                )
            )
        }
        return out
    }

    companion object {
        private const val TAG = "PlowTakMissionSync"
        const val KEY_LAST_HASH = "plowtak.mission_cov.last_hash"
        const val KEY_LAST_FILENAME = "plowtak.mission_cov.last_filename"
        const val KEY_LAST_MISSION = "plowtak.mission_cov.last_mission"
        const val KEY_LAST_CONFIG_HASH = "plowtak.mission_cov.last_config_hash"
        const val KEY_LAST_HAZARD_HASH = "plowtak.mission_cov.last_hazard_hash"
        const val KEY_LAST_CONDITION_HASH = "plowtak.mission_cov.last_condition_hash"
        const val KEY_LAST_STATUS_HASH = "plowtak.mission_cov.last_status_hash"
        const val KEY_LAST_OPS_HASH = "plowtak.mission_cov.last_ops_hash"
        const val KEY_DATASYNC_SERVER = "plowtak.datasync.server"
        private const val KEY_PULL_PREFIX = "plowtak.mission_pull."
        private const val KEY_COV_HASH_PREFIX = "plowtak.mission_cov.hour."
        private const val KEY_COV_INDEX = "plowtak.mission_cov.hour_index"
        /** Cap offline catch-up to 3 days of hourly chunks. */
        private const val MAX_COVERAGE_LOOKBACK_MS = 72L * 3_600_000L
        const val PERIOD_MS = 60_000L
    }
}

/** Callbacks when mission content is pulled from Data Sync. */
interface MissionPullSink {
    fun onStormConfigPulled(cfg: StormConfigCodec.StormConfig) {}
    fun onUnitStatusPulled(vehicle: PlowVehicle) {}
    fun onHazardsPulled(hazards: List<HazardEvent>) {}
    fun onConditionsPulled(conditions: List<RoadConditionReport>) {}
    fun onConditionsPruned(fresh: List<RoadConditionReport>) {}
    fun onOpsPulled(snapshot: OpsMissionCodec.Snapshot) {}
    fun onCoveragePulled(segments: List<TreatSegment>) {}

    companion object {
        val NOOP = object : MissionPullSink {}
    }
}
