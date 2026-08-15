package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.model.StormSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone

/**
 * Tracks the storm this device is **reporting into**, plus a catalog of
 * storms heard over CoT so operators can pick among concurrent agency sessions.
 *
 * Remote storms are catalogued but not auto-joined (multi-agency safe).
 * Ending a remote session updates the catalog and ends the local join when
 * ids match.
 */
class StormSessionManager(
    private val persistence: KeyValuePersistence
) {

    fun interface Listener {
        fun onSessionChanged(session: StormSession?)
    }

    private val listeners = mutableListOf<Listener>()
    /** Storm this unit is tagging coverage / Data Sync against. */
    private var session: StormSession? = null
    /** Heard storms keyed by id (insertion order; capped). */
    private val catalog = LinkedHashMap<String, StormSession>()

    init {
        load()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    val current: StormSession? get() = session

    val activeStormId: String get() = session?.takeIf { it.isActive }?.id ?: ""

    /** Active joined session, or null. */
    fun activeSession(): StormSession? = session?.takeIf { it.isActive }

    /** Catalog for pickers: active storms first, then recently ended. */
    fun knownStorms(): List<StormSession> {
        val values = catalog.values.toList()
        val active = values.filter { it.isActive }.sortedByDescending { it.startTimeMs }
        val ended = values.filter { !it.isActive }.sortedByDescending { it.startTimeMs }
        return active + ended
    }

    /** Start and join a new storm (any unit). */
    fun startSession(
        startedBy: String,
        nowMs: Long,
        label: String = "",
        agency: String = "",
        missionName: String = "",
        channel: String = "",
        cycleMinutes: Int = 45,
        cycleP1Minutes: Int = 0,
        cycleP2Minutes: Int = 0,
        cycleP3Minutes: Int = 0,
        coverageRetentionHours: Double = StormSession.DEFAULT_COVERAGE_RETENTION_HOURS,
        roadConditionTtlMinutes: Int = StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES
    ): StormSession {
        val s = StormSession(
            id = generateId(nowMs),
            startTimeMs = nowMs,
            startedBy = startedBy,
            label = label.trim(),
            agency = agency.trim(),
            missionName = missionName.trim(),
            channel = channel.trim(),
            cycleMinutes = cycleMinutes.coerceIn(5, 24 * 60),
            cycleP1Minutes = cycleP1Minutes.coerceIn(0, 24 * 60),
            cycleP2Minutes = cycleP2Minutes.coerceIn(0, 24 * 60),
            cycleP3Minutes = cycleP3Minutes.coerceIn(0, 24 * 60),
            coverageRetentionHours = coverageRetentionHours.coerceIn(0.0, 72.0),
            roadConditionTtlMinutes = roadConditionTtlMinutes.coerceIn(15, 24 * 60)
        )
        remember(s)
        session = s
        save()
        notifyChanged()
        return s
    }

    /** Update cycle minutes on the joined storm (persisted + catalogued). */
    fun updateCycleMinutes(minutes: Int): StormSession? =
        updateCoverageSettings(cycleMinutes = minutes)

    /** Update road-condition Data Sync TTL on the joined storm. */
    fun updateRoadConditionTtlMinutes(minutes: Int): StormSession? =
        updateCoverageSettings(roadConditionTtlMinutes = minutes)

    /**
     * Patch coverage / cycle settings on the joined storm. Null args leave
     * the current value unchanged.
     */
    fun updateCoverageSettings(
        cycleMinutes: Int? = null,
        cycleP1Minutes: Int? = null,
        cycleP2Minutes: Int? = null,
        cycleP3Minutes: Int? = null,
        coverageRetentionHours: Double? = null,
        roadConditionTtlMinutes: Int? = null
    ): StormSession? {
        val active = session?.takeIf { it.isActive } ?: return null
        val next = active.copy(
            cycleMinutes = cycleMinutes?.coerceIn(5, 24 * 60) ?: active.cycleMinutes,
            cycleP1Minutes = cycleP1Minutes?.coerceIn(0, 24 * 60) ?: active.cycleP1Minutes,
            cycleP2Minutes = cycleP2Minutes?.coerceIn(0, 24 * 60) ?: active.cycleP2Minutes,
            cycleP3Minutes = cycleP3Minutes?.coerceIn(0, 24 * 60) ?: active.cycleP3Minutes,
            coverageRetentionHours = coverageRetentionHours?.coerceIn(0.0, 72.0)
                ?: active.coverageRetentionHours,
            roadConditionTtlMinutes = roadConditionTtlMinutes?.coerceIn(15, 24 * 60)
                ?: active.roadConditionTtlMinutes
        )
        if (next == active) return active
        remember(next)
        session = next
        save()
        notifyChanged()
        return next
    }

    /** Supervisor: end the storm this device started / is joined to. */
    fun endSession(nowMs: Long): StormSession? {
        val active = session?.takeIf { it.isActive } ?: return null
        val ended = active.copy(endTimeMs = nowMs)
        remember(ended)
        session = ended
        save()
        notifyChanged()
        return ended
    }

    /** Explicitly join [remote] as the reporting storm. */
    fun join(remote: StormSession): Boolean {
        remember(remote)
        if (session == remote) return false
        session = remote
        save()
        notifyChanged()
        return true
    }

    fun joinById(id: String): Boolean {
        val s = catalog[id] ?: return false
        return join(s)
    }

    /** Stop reporting into a storm without ending it for the fleet. */
    fun leave() {
        if (session == null) return
        session = null
        save()
        notifyChanged()
    }

    /**
     * Catalog a remote storm announcement. Updates the joined session only when
     * the same id ends (or metadata refreshes for the joined id). Does **not**
     * auto-join a different agency’s storm.
     */
    fun noteRemote(remote: StormSession): Boolean {
        remember(remote)
        val local = session
        val changed = when {
            local != null && remote.id == local.id -> {
                // Same storm: adopt end / refreshed metadata.
                if (local != remote) {
                    session = remote
                    true
                } else false
            }
            else -> false
        }
        save()
        if (changed) notifyChanged()
        return changed
    }

    /** @deprecated Use [noteRemote]; kept for call-site clarity during transition. */
    fun adoptRemote(remote: StormSession): Boolean = noteRemote(remote)

    private fun remember(s: StormSession) {
        catalog.remove(s.id)
        catalog[s.id] = s
        while (catalog.size > MAX_CATALOG) {
            val oldest = catalog.keys.firstOrNull() ?: break
            // Prefer dropping ended entries first.
            val drop = catalog.entries.firstOrNull { !it.value.isActive }?.key ?: oldest
            if (drop == session?.id) {
                // Never drop the joined storm from the map; stop trimming.
                break
            }
            catalog.remove(drop)
        }
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.onSessionChanged(session) }
    }

    // ------------------------------------------------------- persistence

    private fun save() {
        val s = session
        if (s == null) persistence.remove(KEY_SESSION)
        else persistence.putString(KEY_SESSION, encodeSession(s))

        val catalogBlob = catalog.values.joinToString("\n") { encodeSession(it) }
        if (catalogBlob.isEmpty()) persistence.remove(KEY_CATALOG)
        else persistence.putString(KEY_CATALOG, catalogBlob)
    }

    private fun load() {
        persistence.getString(KEY_CATALOG)?.lineSequence()?.forEach { line ->
            if (line.isNotBlank()) decodeSession(line)?.let { remember(it) }
        }
        val joined = persistence.getString(KEY_SESSION)?.let { decodeSession(it) }
        if (joined != null) {
            remember(joined)
            session = joined
        }
    }

    companion object {
        const val KEY_SESSION = "plowtak.storm_session"
        const val KEY_CATALOG = "plowtak.storm_catalog"
        private const val MAX_CATALOG = 32

        fun generateId(nowMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            return "${fmt.format(Date(nowMs))}-${nowMs / 1000}"
        }

        fun encodeSession(s: StormSession): String =
            listOf(
                esc(s.id),
                s.startTimeMs.toString(),
                s.endTimeMs.toString(),
                esc(s.startedBy),
                esc(s.label),
                esc(s.agency),
                esc(s.missionName),
                esc(s.channel),
                s.cycleMinutes.toString(),
                s.roadConditionTtlMinutes.toString(),
                s.cycleP1Minutes.toString(),
                s.cycleP2Minutes.toString(),
                s.cycleP3Minutes.toString(),
                s.coverageRetentionHours.toString()
            ).joinToString("|")

        fun decodeSession(line: String): StormSession? {
            val f = line.split("|")
            if (f.size < 4) return null
            val start = f[1].toLongOrNull() ?: return null
            val end = f[2].toLongOrNull() ?: return null
            return StormSession(
                id = unesc(f[0]),
                startTimeMs = start,
                endTimeMs = end,
                startedBy = unesc(f[3]),
                label = if (f.size > 4) unesc(f[4]) else "",
                agency = if (f.size > 5) unesc(f[5]) else "",
                missionName = if (f.size > 6) unesc(f[6]) else "",
                channel = if (f.size > 7) unesc(f[7]) else "",
                cycleMinutes = if (f.size > 8) f[8].toIntOrNull() ?: 45 else 45,
                roadConditionTtlMinutes = if (f.size > 9) {
                    f[9].toIntOrNull() ?: StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES
                } else {
                    StormSession.DEFAULT_ROAD_CONDITION_TTL_MINUTES
                },
                cycleP1Minutes = if (f.size > 10) f[10].toIntOrNull() ?: 0 else 0,
                cycleP2Minutes = if (f.size > 11) f[11].toIntOrNull() ?: 0 else 0,
                cycleP3Minutes = if (f.size > 12) f[12].toIntOrNull() ?: 0 else 0,
                coverageRetentionHours = if (f.size > 13) {
                    f[13].toDoubleOrNull() ?: StormSession.DEFAULT_COVERAGE_RETENTION_HOURS
                } else {
                    StormSession.DEFAULT_COVERAGE_RETENTION_HOURS
                }
            )
        }

        private fun esc(s: String) = s.replace("|", "&#124;").replace("\n", "&#10;")
        private fun unesc(s: String) = s.replace("&#124;", "|").replace("&#10;", "\n")
    }
}
