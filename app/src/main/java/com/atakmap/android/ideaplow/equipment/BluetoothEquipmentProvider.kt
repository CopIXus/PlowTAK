package com.atakmap.android.ideaplow.equipment

import com.atakmap.android.ideaplow.model.EquipmentState

/**
 * Phase 3 stub for Bluetooth plow/spreader controllers. Not implemented —
 * the settings UI shows a disabled "Bluetooth equipment (coming soon)"
 * placeholder only when the vehicle has a blade or spreader.
 *
 * Planned Phase 3 surface: device picker, connection supervision, and
 * blade-position / spreader-rate channels mapped onto [EquipmentState].
 * Recording must keep working from manual toggles if BT drops.
 */
class BluetoothEquipmentProvider : EquipmentProvider {

    override val state: EquipmentState = EquipmentState()

    override fun addListener(l: EquipmentProvider.Listener) { /* stub */ }
    override fun removeListener(l: EquipmentProvider.Listener) { /* stub */ }

    override fun start() {
        // Intentionally inert until Phase 3.
    }

    override fun stop() {
        // Intentionally inert until Phase 3.
    }

    companion object {
        /** Feature flag for the settings placeholder. */
        const val IMPLEMENTED = false
    }
}
