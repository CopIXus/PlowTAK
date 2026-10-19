package com.atakmap.android.plowtak.equipment

import com.atakmap.android.plowtak.model.EquipmentState

/**
 * Source of blade / spreader state. The driver toggles are the source.
 * Capability flags still gate which channels matter.
 */
interface EquipmentProvider {

    fun interface Listener {
        fun onEquipmentChanged(state: EquipmentState)
    }

    val state: EquipmentState

    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    /** Begin delivering state (connect hardware, etc.). Idempotent. */
    fun start()

    /** Stop and release resources. Idempotent. */
    fun stop()
}
