package com.atakmap.android.plowtak.sync

import android.content.Context
import android.preference.PreferenceManager
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.comms.TAKServer

/**
 * Lists connected TAK servers and builds Marti API base URLs for Data Sync.
 */
object TakServerTargets {

    data class Target(
        /** Stable key — ATAK connect string. */
        val connectString: String,
        /** UI label (description or host). */
        val label: String,
        val connected: Boolean,
        val hostBase: String
    )

    fun listServers(): List<Target> {
        return try {
            val servers: Array<TAKServer> =
                CotMapComponent.getInstance()?.servers ?: return emptyList()
            servers.map { s ->
                val hostBase = s.getURL(false) ?: ""
                val desc = s.description?.trim().orEmpty()
                val label = when {
                    desc.isNotEmpty() -> desc
                    hostBase.isNotEmpty() -> hostBase.removePrefix("https://").removePrefix("http://")
                    else -> s.connectString
                }
                Target(
                    connectString = s.connectString ?: "",
                    label = label,
                    connected = s.isConnected,
                    hostBase = hostBase
                )
            }.filter { it.connectString.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun connectedServers(): List<Target> = listServers().filter { it.connected }

    /**
     * Resolve Marti API base URL for [preferredConnectString].
     * Empty preference → first connected server.
     * Prefer the selected server when still connected; otherwise fall back to
     * the first connected server (logged by caller).
     */
    fun resolveApiBaseUrl(appContext: Context, preferredConnectString: String): ResolveResult? {
        val servers: Array<TAKServer> = try {
            CotMapComponent.getInstance()?.servers ?: return null
        } catch (_: Throwable) {
            return null
        }
        val connected = servers.filter { it.isConnected }
        if (connected.isEmpty()) return null

        val preferred = preferredConnectString.trim()
        val chosen = when {
            preferred.isEmpty() -> connected.first()
            else -> connected.firstOrNull { it.connectString == preferred }
                ?: connected.first()
        }
        val usedFallback = preferred.isNotEmpty() && chosen.connectString != preferred
        val hostBase = chosen.getURL(false) ?: return null
        val https = hostBase.startsWith("https", ignoreCase = true)
        val atakPrefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val port = if (https) {
            atakPrefs.getString(CotMapComponent.PREF_API_SECURE_PORT, "8443") ?: "8443"
        } else {
            atakPrefs.getString(CotMapComponent.PREF_API_UNSECURE_PORT, "8080") ?: "8080"
        }
        return ResolveResult(
            apiBaseUrl = "$hostBase:$port",
            connectString = chosen.connectString ?: "",
            label = chosen.description?.takeIf { it.isNotBlank() } ?: hostBase,
            usedFallback = usedFallback
        )
    }

    data class ResolveResult(
        val apiBaseUrl: String,
        val connectString: String,
        val label: String,
        val usedFallback: Boolean
    )
}
