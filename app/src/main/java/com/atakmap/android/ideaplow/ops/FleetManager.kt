package com.atakmap.android.ideaplow.ops

import com.atakmap.android.ideaplow.model.PlowVehicle

/**
 * Last-known state of every remote fleet unit, keyed by uid. Fed by the CoT
 * listener; consumed by fleet markers and the supervisor/observer lists.
 * Stale sweep is driven externally (shared recolor timer).
 */
class FleetManager(
    /** Units stop being "live" after this long without a report. */
    var staleAfterMs: Long = 60_000L
) {

    fun interface Listener {
        fun onFleetChanged(vehicles: List<PlowVehicle>)
    }

    private val vehicles = LinkedHashMap<String, PlowVehicle>()
    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun update(vehicle: PlowVehicle) {
        vehicles[vehicle.uid] = vehicle
        notifyChanged()
    }

    fun get(uid: String): PlowVehicle? = vehicles[uid]

    fun all(): List<PlowVehicle> = vehicles.values.toList()

    fun staleUids(nowMs: Long): List<String> =
        vehicles.values.filter { it.isStale(nowMs, staleAfterMs) }.map { it.uid }

    /** Drop units unseen for [dropAfterMs]; returns removed uids. */
    fun pruneDead(nowMs: Long, dropAfterMs: Long = 30 * 60_000L): List<String> {
        val dead = vehicles.values
            .filter { nowMs - it.lastUpdateMs > dropAfterMs }
            .map { it.uid }
        if (dead.isNotEmpty()) {
            dead.forEach { vehicles.remove(it) }
            notifyChanged()
        }
        return dead
    }

    private fun notifyChanged() {
        val snapshot = vehicles.values.toList()
        listeners.toList().forEach { it.onFleetChanged(snapshot) }
    }
}
