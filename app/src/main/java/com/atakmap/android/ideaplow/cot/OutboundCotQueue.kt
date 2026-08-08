package com.atakmap.android.ideaplow.cot

import android.util.Log
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.coremap.cot.event.CotEvent

/**
 * Best-effort offline queue for outbound CoT.
 *
 * Every event is dispatched *internally* immediately (local map always
 * reflects reality). External dispatch goes straight out when the TAK
 * connection looks up; otherwise events queue in memory and flush on
 * reconnect (drainable by a periodic tick).
 *
 * Documented limitations (see docs/cot-schema.md):
 *  - The queue is in-memory only; events pending at process death are lost.
 *    Coverage is safe regardless — segments persist in CoverageStore and can
 *    be re-shared — but a missed PLI is simply stale by then anyway.
 *  - "Connected" is inferred from CotMapComponent server status via
 *    reflection-tolerant best effort; when undetectable we optimistically
 *    dispatch (ATAK's own comms layer also buffers briefly).
 */
class OutboundCotQueue(
    private val maxQueued: Int = 500
) {

    private val queue = ArrayDeque<CotEvent>()
    private var lastConnected = true

    /** Dispatch internally now; externally now or when connectivity returns. */
    @Synchronized
    fun send(event: CotEvent, alsoInternal: Boolean = true) {
        if (alsoInternal) {
            try {
                CotMapComponent.getInternalDispatcher()?.dispatch(event)
            } catch (e: Exception) {
                Log.w(TAG, "internal dispatch failed for ${event.uid}", e)
            }
        }
        if (isConnected()) {
            flushLocked()
            dispatchExternal(event)
        } else {
            enqueue(event)
        }
    }

    /** Call periodically (shared timer) to flush after reconnect. */
    @Synchronized
    fun onTick() {
        val connected = isConnected()
        if (connected && !lastConnected) {
            Log.i(TAG, "TAK connection restored; flushing ${queue.size} queued events")
        }
        if (connected) flushLocked()
        lastConnected = connected
    }

    @Synchronized
    fun pendingCount(): Int = queue.size

    private fun enqueue(event: CotEvent) {
        // PLI events supersede each other — keep only the newest per uid+type.
        queue.removeAll { it.uid == event.uid && it.type == event.type }
        queue.addLast(event)
        while (queue.size > maxQueued) {
            queue.removeFirst()
        }
    }

    private fun flushLocked() {
        while (queue.isNotEmpty()) {
            dispatchExternal(queue.removeFirst())
        }
    }

    private fun dispatchExternal(event: CotEvent) {
        try {
            CotMapComponent.getExternalDispatcher()?.dispatch(event)
        } catch (e: Exception) {
            Log.w(TAG, "external dispatch failed for ${event.uid}", e)
        }
    }

    /**
     * Best-effort TAK server connectivity probe. SDK-fixup point: verify the
     * server-status API surface against the real 5.8 main.jar.
     */
    private fun isConnected(): Boolean {
        return try {
            val servers = CotMapComponent.getInstance()?.servers ?: return true
            if (servers.isEmpty()) return true // no server configured — don't queue forever
            servers.any { it.isConnected }
        } catch (e: Throwable) {
            true // undetectable — be optimistic
        }
    }

    companion object {
        private const val TAG = "IdeaPlowCotQueue"
    }
}
