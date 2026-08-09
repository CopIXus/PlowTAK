package com.atakmap.android.plowtak.cot

import android.util.Log
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.coremap.cot.event.CotEvent
import java.io.File

/**
 * Best-effort offline queue for outbound CoT.
 *
 * Every event is dispatched *internally* immediately (local map always
 * reflects reality). External dispatch goes straight out when the TAK
 * connection looks up; otherwise events queue and flush on reconnect.
 *
 * When [persistFile] is set, the pending external queue is rewritten as
 * CotEvent XML lines so a process death does not drop ops events. Coverage
 * is still primarily protected by [com.atakmap.android.plowtak.coverage.CoverageStore]
 * re-share; this file covers alerts/tasks/storms that were waiting for uplink.
 */
class OutboundCotQueue(
    private val maxQueued: Int = 500,
    private val persistFile: File? = null
) {

    private val queue = ArrayDeque<CotEvent>()
    private var lastConnected = true

    init {
        loadPersisted()
    }

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
            persistLocked()
        }
    }

    /** Call periodically (shared timer) to flush after reconnect. */
    @Synchronized
    fun onTick() {
        val connected = isConnected()
        if (connected && !lastConnected) {
            Log.i(TAG, "TAK connection restored; flushing ${queue.size} queued events")
        }
        if (connected) {
            flushLocked()
            persistLocked()
        }
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
            CotMapComponent.getExternalDispatcher()?.dispatchToBroadcast(event)
        } catch (e: Exception) {
            Log.w(TAG, "external dispatch failed for ${event.uid}", e)
        }
    }

    private fun persistLocked() {
        val file = persistFile ?: return
        try {
            file.parentFile?.mkdirs()
            if (queue.isEmpty()) {
                if (file.exists()) file.delete()
                return
            }
            file.writeText(queue.joinToString("\n") { it.toString().replace("\n", " ") })
        } catch (e: Exception) {
            Log.w(TAG, "persist outbound queue failed", e)
        }
    }

    private fun loadPersisted() {
        val file = persistFile ?: return
        if (!file.isFile) return
        try {
            for (line in file.readLines()) {
                if (line.isBlank()) continue
                val event = CotEvent.parse(line) ?: continue
                if (event.isValid) queue.addLast(event)
            }
            Log.i(TAG, "restored ${queue.size} queued CoT events from disk")
        } catch (e: Exception) {
            Log.w(TAG, "load outbound queue failed", e)
        }
    }

    private fun isConnected(): Boolean {
        return try {
            val servers = CotMapComponent.getInstance()?.servers ?: return true
            if (servers.isEmpty()) return true
            servers.any { it.isConnected }
        } catch (e: Throwable) {
            true
        }
    }

    companion object {
        private const val TAG = "PlowTakCotQueue"
    }
}
