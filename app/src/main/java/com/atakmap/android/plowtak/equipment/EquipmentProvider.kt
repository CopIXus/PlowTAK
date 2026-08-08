package com.atakmap.android.plowtak.equipment

import com.atakmap.android.plowtak.model.EquipmentState

/**
 * Source of blade / spreader state. Phase 1 ships [ManualEquipmentProvider]
 * (driver toggles); Phase 3 adds [BluetoothEquipmentProvider] for plow and
 * spreader controllers. Capability flags still gate which channels matter —
 * and recording must never block on an unavailable provider.
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
