package com.atakmap.android.plowtak.cot

/**
 * Pure decision for whether outbound CoT can leave the device.
 * No servers configured, or an unreadable server list, is disconnected —
 * events must queue instead of being dispatched into nowhere.
 */
object CotLinkState {
    fun isUp(serverCount: Int?, anyConnected: Boolean): Boolean {
        if (serverCount == null || serverCount <= 0) return false
        return anyConnected
    }
}
