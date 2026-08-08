package com.atakmap.android.ideaplow.coverage

import com.atakmap.android.ideaplow.model.TreatSegment
import java.io.File

/**
 * In-memory coverage record with flat-file persistence for offline
 * continuity. One append-only file per storm session (JSON-free line format
 * via [SegmentCodec]) under [storageDir]; the file is compacted on prune.
 *
 * Thread-safety: all mutators synchronize on this instance; listeners are
 * invoked on the calling thread.
 */
class CoverageStore(
    private val storageDir: File?
) {

    interface Listener {
        /** A new segment entered the store (local or merged remote). */
        fun onSegmentAdded(segment: TreatSegment, local: Boolean)
        /** Segments were removed (expiry / storm change). */
        fun onSegmentsRemoved(ids: Collection<String>)
    }

    private val segments = LinkedHashMap<String, TreatSegment>()
    private val listeners = mutableListOf<Listener>()
    /** Local segments not yet shared over CoT; drained by the publisher. */
    private val pendingShare = ArrayDeque<TreatSegment>()

    var currentStormId: String = ""
        private set

    fun addListener(l: Listener) = synchronized(this) { listeners.add(l) }
    fun removeListener(l: Listener) = synchronized(this) { listeners.remove(l) }

    /** Switch the active storm scope, loading any persisted records for it. */
    fun setStorm(stormId: String) {
        val removed: List<String>
        synchronized(this) {
            if (stormId == currentStormId && segments.isNotEmpty()) return
            currentStormId = stormId
            removed = segments.keys.toList()
            segments.clear()
            pendingShare.clear()
            loadFromDisk(stormId)
        }
        if (removed.isNotEmpty()) notifyRemoved(removed)
    }

    /** Add a locally recorded segment; persists and queues it for sharing. */
    fun addLocal(segment: TreatSegment) {
        val added: Boolean
        synchronized(this) {
            added = segments.put(segment.id, segment) == null
            if (added) {
                pendingShare.addLast(segment)
                appendToDisk(segment)
            }
        }
        if (added) notifyAdded(segment, local = true)
    }

    /**
     * Merge a segment received over CoT. Deduped by id; caller is responsible
     * for capability gating (only canTreat units' coverage reaches here).
     */
    fun mergeRemote(segment: TreatSegment): Boolean {
        val added: Boolean
        synchronized(this) {
            if (currentStormId.isNotEmpty() && segment.stormId.isNotEmpty() &&
                segment.stormId != currentStormId
            ) return false
            added = segments.put(segment.id, segment) == null
            if (added) appendToDisk(segment)
        }
        if (added) notifyAdded(segment, local = false)
        return added
    }

    fun all(): List<TreatSegment> = synchronized(this) { segments.values.toList() }

    fun size(): Int = synchronized(this) { segments.size }

    /** Drain up to [max] local segments awaiting CoT share. */
    fun drainPendingShare(max: Int): List<TreatSegment> = synchronized(this) {
        val out = mutableListOf<TreatSegment>()
        while (out.size < max && pendingShare.isNotEmpty()) {
            out.add(pendingShare.removeFirst())
        }
        out
    }

    /** Re-queue segments whose share attempt failed. */
    fun requeueForShare(segs: List<TreatSegment>) = synchronized(this) {
        segs.asReversed().forEach { pendingShare.addFirst(it) }
    }

    /** Drop segments older than the retention window; compacts the file. */
    fun pruneExpired(model: FreshnessModel, nowMs: Long) {
        val removed: List<String>
        synchronized(this) {
            removed = segments.values
                .filter { model.classify(it.endTimeMs, nowMs) == Freshness.EXPIRED }
                .map { it.id }
            if (removed.isEmpty()) return
            removed.forEach { segments.remove(it) }
            rewriteDisk()
        }
        notifyRemoved(removed)
    }

    // ------------------------------------------------------------------ disk

    private fun stormFile(stormId: String): File? {
        val dir = storageDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val safe = if (stormId.isEmpty()) "no-storm"
        else stormId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "coverage-$safe.txt")
    }

    private fun loadFromDisk(stormId: String) {
        val file = stormFile(stormId) ?: return
        if (!file.isFile) return
        try {
            file.useLines { lines ->
                lines.forEach { line ->
                    SegmentCodec.decode(line)?.let { segments[it.id] = it }
                }
            }
        } catch (e: Exception) {
            // Corrupt store must never take down recording; start fresh.
        }
    }

    private fun appendToDisk(segment: TreatSegment) {
        val file = stormFile(currentStormId) ?: return
        try {
            file.appendText(SegmentCodec.encode(segment) + "\n")
        } catch (e: Exception) {
            // Best-effort persistence; in-memory copy remains authoritative.
        }
    }

    private fun rewriteDisk() {
        val file = stormFile(currentStormId) ?: return
        try {
            file.writeText(
                segments.values.joinToString("\n") { SegmentCodec.encode(it) } + "\n"
            )
        } catch (e: Exception) {
            // Best-effort.
        }
    }

    // ------------------------------------------------------------- listeners

    private fun notifyAdded(segment: TreatSegment, local: Boolean) {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { it.onSegmentAdded(segment, local) }
    }

    private fun notifyRemoved(ids: Collection<String>) {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { it.onSegmentsRemoved(ids) }
    }
}
