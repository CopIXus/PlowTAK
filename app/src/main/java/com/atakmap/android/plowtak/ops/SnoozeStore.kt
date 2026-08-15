package com.atakmap.android.plowtak.ops

/**
 * Fleet-shared due-time extensions for tasking rows.
 * Merge rule: max [dueByMs] wins so the longest snooze sticks.
 */
class SnoozeStore(
    private val persistence: KeyValuePersistence? = null
) {

    fun interface Listener {
        fun onSnoozesChanged(dueBy: Map<String, Long>)
    }

    private val dueBy = LinkedHashMap<String, Long>()
    private val listeners = mutableListOf<Listener>()

    init {
        persistence?.getString(PERSIST_KEY)?.lineSequence()?.forEach { line ->
            val bar = line.indexOf('|')
            if (bar <= 0) return@forEach
            val id = line.substring(0, bar)
            val ms = line.substring(bar + 1).toLongOrNull() ?: return@forEach
            dueBy[id] = ms
        }
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun all(): Map<String, Long> = dueBy.toMap()

    fun dueBy(id: String): Long? = dueBy[id]

    /**
     * Extend [id] by [minutes] from max(now, current due).
     * Returns the new dueByMs.
     */
    fun bump(id: String, minutes: Int, nowMs: Long): Long {
        val step = minutes.coerceIn(5, 60) * 60_000L
        val base = maxOf(nowMs, dueBy[id] ?: nowMs)
        val next = base + step
        dueBy[id] = next
        persist()
        notifyChanged()
        return next
    }

    /** Merge remote snoozes (max dueBy wins). Returns true if anything changed. */
    fun mergeRemote(remote: Map<String, Long>): Boolean {
        var changed = false
        for ((id, ms) in remote) {
            val cur = dueBy[id]
            if (cur == null || ms > cur) {
                dueBy[id] = ms
                changed = true
            }
        }
        if (changed) {
            persist()
            notifyChanged()
        }
        return changed
    }

    /** Drop expired snoozes older than [nowMs] (optional compaction). */
    fun pruneExpired(nowMs: Long) {
        val doomed = dueBy.filterValues { it <= nowMs }.keys.toList()
        if (doomed.isEmpty()) return
        for (id in doomed) dueBy.remove(id)
        persist()
        notifyChanged()
    }

    private fun notifyChanged() {
        val snap = dueBy.toMap()
        listeners.toList().forEach { it.onSnoozesChanged(snap) }
    }

    private fun persist() {
        val p = persistence ?: return
        if (dueBy.isEmpty()) {
            p.remove(PERSIST_KEY)
            return
        }
        p.putString(
            PERSIST_KEY,
            dueBy.entries.joinToString("\n") { (id, ms) ->
                id.replace("|", "_") + "|" + ms
            }
        )
    }

    companion object {
        const val PERSIST_KEY = "plowtak.tasking_snoozes"
    }
}
