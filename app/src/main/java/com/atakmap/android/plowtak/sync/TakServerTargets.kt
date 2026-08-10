package com.atakmap.android.plowtak.sync

import android.content.Context
import android.util.Log
import com.atakmap.android.cot.CotMapComponent
import com.atakmap.comms.TAKServer

/**
 * Lists connected TAK servers and builds bases for [TakHttpClient2].
 *
 * Important: `TakHttpClient2.GetHttpClient(url)` **appends** `:{apiPort}/Marti`
 * itself via [com.atakmap.comms.SslNetCotPort.getServerApiPath]. Passing a URL
 * that already includes `:8443` produces the invalid
 * `https://host:8443:8443/Marti` and OkHttp's `HttpUrl.parse` returns null
 * ("Failed to create new builder").
 *
 * Pass scheme://host only (from [TAKServer.getURL] `(false)`). Paths passed to
 * get/put must be Marti-relative (`/api/missions/...`, `/sync/...`), not
 * `/Marti/...`.
 */
object TakServerTargets {

    private const val TAG = "PlowTakTakTargets"

    data class Target(
        /** Stable key — ATAK connect string. */
        val connectString: String,
        /** UI label (description or host). */
        val label: String,
        val connected: Boolean,
        /** scheme://host with no port — input for GetHttpClient. */
        val hostBase: String
    )

    fun listServers(): List<Target> {
        return try {
            val servers: Array<TAKServer> =
                CotMapComponent.getInstance()?.servers ?: return emptyList()
            servers.mapNotNull { s ->
                val hostBase = normalizeHostBase(s.getURL(false) ?: "") ?: ""
                val desc = s.description?.trim().orEmpty()
                val label = when {
                    desc.isNotEmpty() -> desc
                    hostBase.isNotEmpty() -> hostBase.removePrefix("https://").removePrefix("http://")
                    else -> s.connectString ?: ""
                }
                val connect = s.connectString ?: return@mapNotNull null
                if (connect.isEmpty()) return@mapNotNull null
                Target(
                    connectString = connect,
                    label = label,
                    connected = s.isConnected,
                    hostBase = hostBase
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun connectedServers(): List<Target> = listServers().filter { it.connected }

    /**
     * Resolve GetHttpClient host base for [preferredConnectString].
     * Empty preference → first connected server.
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
        val hostBase = normalizeHostBase(chosen.getURL(false) ?: "") ?: return null
        if (!hostBase.startsWith("http://", ignoreCase = true) &&
            !hostBase.startsWith("https://", ignoreCase = true)
        ) {
            Log.w(TAG, "server getURL produced non-http base: $hostBase")
            return null
        }
        if (!isValidHttpUrl(hostBase)) {
            Log.e(TAG, "invalid GetHttpClient host base: '$hostBase'")
            return null
        }
        Log.i(
            TAG,
            "Marti client base → $hostBase (TakHttpClient2 appends :{apiPort}/Marti) (${chosen.description})"
        )
        return ResolveResult(
            apiBaseUrl = hostBase,
            connectString = chosen.connectString ?: "",
            label = chosen.description?.takeIf { it.isNotBlank() } ?: hostBase,
            usedFallback = usedFallback
        )
    }

    /**
     * Strip trailing slash and any explicit `:port` so GetHttpClient can append
     * `:{apiPort}/Marti` once. IPv6 authorities (`[::1]`) are preserved.
     */
    internal fun normalizeHostBase(raw: String): String? {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!hostHasExplicitPort(s)) return s
        // https://host:8443 → https://host ; https://[::1]:8443 → https://[::1]
        val afterScheme = s.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return s
        val scheme = s.substringBefore("://")
        return if (afterScheme.startsWith("[")) {
            val host = afterScheme.substringBefore(']') + "]"
            "$scheme://$host"
        } else {
            val host = afterScheme.substringBefore(':').substringBefore('/')
            if (host.isEmpty()) s else "$scheme://$host"
        }
    }

    /** True when authority already includes `:port` (not an IPv6 bare host). */
    internal fun hostHasExplicitPort(hostBase: String): Boolean {
        val afterScheme = hostBase.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.startsWith("[")) {
            return afterScheme.contains("]:")
        }
        val authority = afterScheme.substringBefore('/')
        return authority.contains(':')
    }

    internal fun isValidHttpUrl(url: String): Boolean {
        return try {
            val u = java.net.URL(url)
            val protocolOk = u.protocol.equals("http", true) || u.protocol.equals("https", true)
            val hostOk = !u.host.isNullOrBlank()
            // GetHttpClient host base must NOT carry an API port.
            val portOk = u.port == -1
            protocolOk && hostOk && portOk
        } catch (_: Throwable) {
            false
        }
    }

    data class ResolveResult(
        /** scheme://host for [com.atakmap.comms.http.TakHttpClient2.GetHttpClient]. */
        val apiBaseUrl: String,
        val connectString: String,
        val label: String,
        val usedFallback: Boolean
    )
}
