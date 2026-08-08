package com.atakmap.android.plowtak.ops

import com.atakmap.android.plowtak.model.AlertEvent
import com.atakmap.android.plowtak.model.AlertState

/**
 * Distress alert book-keeping with the ack/clear workflow so storms don't
 * fill with stale SOS. State transitions come from local UI actions or
 * inbound CoT; the CoT layer observes this manager to broadcast local
 * transitions.
 */
class AlertManager {

    interface Listener {
        fun onAlertsChanged(alerts: List<AlertEvent>)
        /** A local action (raise/ack/clear) that must be broadcast over CoT. */
        fun onLocalTransition(alert: AlertEvent)
    }

    private val alerts = LinkedHashMap<String, AlertEvent>()
    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Alerts worth showing (active + acknowledged). */
    /** Every alert this session, terminal states included (export). */
    fun all(): List<AlertEvent> = alerts.values.toList()

    fun activeAlerts(): List<AlertEvent> =
        alerts.values.filter { it.state != AlertState.CLEARED }

    fun get(uid: String): AlertEvent? = alerts[uid]

    /** Local one-tap distress. */
    fun raiseLocal(alert: AlertEvent) {
        alerts[alert.uid] = alert.copy(state = AlertState.ACTIVE)
        notifyChanged()
        notifyLocal(alerts.getValue(alert.uid))
    }

    /** Supervisor acknowledges — responders en route. */
    fun acknowledge(uid: String, byCallsign: String): AlertEvent? =
        transition(uid, AlertState.ACKNOWLEDGED, byCallsign)

    /** Clear — resolved; also used by the sender to cancel their own SOS. */
    fun clear(uid: String, byCallsign: String): AlertEvent? =
        transition(uid, AlertState.CLEARED, byCallsign)

    /** Apply an alert received over CoT (no re-broadcast). */
    fun onRemote(alert: AlertEvent) {
        val existing = alerts[alert.uid]
        // Never resurrect a locally-cleared alert with an older ACTIVE update.
        if (existing != null && existing.state == AlertState.CLEARED &&
            alert.state == AlertState.ACTIVE && alert.timeMs <= existing.timeMs
        ) return
        alerts[alert.uid] = alert
        notifyChanged()
    }

    private fun transition(uid: String, to: AlertState, by: String): AlertEvent? {
        val existing = alerts[uid] ?: return null
        if (existing.state == to) return existing
        val updated = existing.copy(state = to, handledBy = by)
        alerts[uid] = updated
        notifyChanged()
        notifyLocal(updated)
        return updated
    }

    private fun notifyChanged() {
        val snapshot = activeAlerts()
        listeners.toList().forEach { it.onAlertsChanged(snapshot) }
    }

    private fun notifyLocal(alert: AlertEvent) {
        listeners.toList().forEach { it.onLocalTransition(alert) }
    }
}
