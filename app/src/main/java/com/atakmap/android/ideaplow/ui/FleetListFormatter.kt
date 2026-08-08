package com.atakmap.android.ideaplow.ui

import com.atakmap.android.ideaplow.model.AlertEvent
import com.atakmap.android.ideaplow.model.AlertState
import com.atakmap.android.ideaplow.model.PlowVehicle

/** Shared line formatting for the supervisor/observer fleet + alert lists. */
object FleetListFormatter {

    fun vehicleLine(v: PlowVehicle, nowMs: Long, staleAfterMs: Long): String {
        val age = (nowMs - v.lastUpdateMs) / 1000
        val staleness = if (v.isStale(nowMs, staleAfterMs)) "  [STALE ${age}s]" else ""
        val equip = buildString {
            if (v.hasBlade) append(if (v.bladeDown) " ▼blade" else " ▲blade")
            if (v.hasSalt) append(if (v.saltOn) " ●salt" else " ○salt")
        }
        val operator = v.operatorName.takeIf { it.isNotEmpty() }?.let { "  op:$it" } ?: ""
        return "${v.callsign} — ${v.status.label}$equip$operator$staleness"
    }

    fun alertLine(a: AlertEvent, nowMs: Long): String {
        val age = (nowMs - a.timeMs) / 60_000
        val state = when (a.state) {
            AlertState.ACTIVE -> "!! ACTIVE"
            AlertState.ACKNOWLEDGED -> "ACK by ${a.handledBy}"
            AlertState.CLEARED -> "cleared"
        }
        return "${a.callsign} — $state (${age} min ago)"
    }
}
