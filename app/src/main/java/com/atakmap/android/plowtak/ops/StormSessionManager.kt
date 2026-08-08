package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.model.StormSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tracks the active [StormSession]. Supervisors start/end sessions; every
 * client adopts sessions seen over CoT so the fleet converges without a
 * server-side authority. Convergence rule: a remote session with a *newer*
 * start time wins; an end broadcast for the current id ends it everywhere.
 */
class StormSessionManager(
    private val persistence: KeyValuePersistence
) {

    fun interface Listener {
        fun onSessionChanged(session: StormSession?)
    }

    private val listeners = mutableListOf<Listener>()
    private var session: StormSession? = null

    init {
        load()
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    val current: StormSession? get() = session

    val activeStormId: String get() = session?.takeIf { it.isActive }?.id ?: ""

    /** Supervisor action. Returns the new session. */
    fun startSession(startedBy: String, nowMs: Long): StormSession {
        val s = StormSession(
            id = generateId(nowMs),
            startTimeMs = nowMs,
            startedBy = startedBy
        )
        session = s
        save()
        notifyChanged()
        return s
    }

    /** Supervisor action. Returns the ended session for broadcast, or null. */
    fun endSession(nowMs: Long): StormSession? {
        val active = session?.takeIf { it.isActive } ?: return null
        val ended = active.copy(endTimeMs = nowMs)
        session = ended
        save()
        notifyChanged()
        return ended
    }

    /**
     * Adopt a session announced by another unit. Returns true if local state
     * changed (caller should re-scope coverage).
     */
    fun adoptRemote(remote: StormSession): Boolean {
        val local = session
        val changed: Boolean = when {
            // End broadcast for the session we're in.
            local != null && remote.id == local.id ->
                if (!remote.isActive && local.isActive) {
                    session = local.copy(endTimeMs = remote.endTimeMs)
                    true
                } else false
            // A different active session that started later wins.
            remote.isActive && (local == null || remote.startTimeMs > local.startTimeMs) -> {
                session = remote
                true
            }
            else -> false
        }
        if (changed) {
            save()
            notifyChanged()
        }
        return changed
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it.onSessionChanged(session) }
    }

    // ------------------------------------------------------- persistence

    private fun save() {
        val s = session ?: run { persistence.remove(KEY_SESSION); return }
        persistence.putString(
            KEY_SESSION,
            listOf(esc(s.id), s.startTimeMs, s.endTimeMs, esc(s.startedBy)).joinToString("|")
        )
    }

    private fun load() {
        val f = persistence.getString(KEY_SESSION)?.split("|") ?: return
        if (f.size == 4) {
            val start = f[1].toLongOrNull() ?: return
            val end = f[2].toLongOrNull() ?: return
            session = StormSession(unesc(f[0]), start, end, unesc(f[3]))
        }
    }

    private fun esc(s: String) = s.replace("|", "&#124;")
    private fun unesc(s: String) = s.replace("&#124;", "|")

    companion object {
        const val KEY_SESSION = "plowtak.storm_session"

        /** e.g. "2026-01-15-1736951234" — readable date + epoch uniqueness. */
        fun generateId(nowMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getDefault()
            return "${fmt.format(Date(nowMs))}-${nowMs / 1000}"
        }
    }
}
