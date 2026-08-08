package com.atakmap.android.ideaplow.ops

import com.atakmap.android.ideaplow.model.VehicleStatus

/**
 * Owns this unit's [VehicleStatus]. Manual statuses (LOADING, REFUELING,
 * ON_BREAK, OUT_OF_SERVICE) are sticky until cleared; otherwise the status is
 * derived: TREATING while the treat rule holds, DEADHEAD while on shift, and
 * OFF_DUTY with no active shift.
 *
 * Geofence auto-suggestions arrive via [suggest]; they are prompts only —
 * the driver stays authoritative (never auto-flips).
 */
class StatusManager {

    fun interface Listener {
        fun onStatusChanged(status: VehicleStatus)
    }

    fun interface SuggestionListener {
        /** UI should prompt: "Looks like you're at [reason] — set [suggested]?" */
        fun onStatusSuggested(suggested: VehicleStatus, reason: String)
    }

    private val listeners = mutableListOf<Listener>()
    var suggestionListener: SuggestionListener? = null

    private var manualStatus: VehicleStatus? = null
    private var treating: Boolean = false
    private var onShift: Boolean = false
    private var lastComputed: VehicleStatus = VehicleStatus.OFF_DUTY

    val current: VehicleStatus get() = lastComputed

    val manual: VehicleStatus? get() = manualStatus

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** One-tap manual status. */
    fun setManual(status: VehicleStatus) {
        manualStatus = status
        recompute()
    }

    /** "Back to driving" — resume derived status. */
    fun clearManual() {
        manualStatus = null
        recompute()
    }

    fun updateTreating(treating: Boolean) {
        this.treating = treating
        recompute()
    }

    fun updateShift(onShift: Boolean) {
        this.onShift = onShift
        if (!onShift) manualStatus = null
        recompute()
    }

    /** Surface a suggestion (e.g. entered salt dome → LOADING). Never auto-sets. */
    fun suggest(status: VehicleStatus, reason: String) {
        if (lastComputed == status) return
        suggestionListener?.onStatusSuggested(status, reason)
    }

    private fun recompute() {
        val next = manualStatus ?: when {
            !onShift -> VehicleStatus.OFF_DUTY
            treating -> VehicleStatus.TREATING
            else -> VehicleStatus.DEADHEAD
        }
        if (next != lastComputed) {
            lastComputed = next
            listeners.toList().forEach { it.onStatusChanged(next) }
        }
    }
}
