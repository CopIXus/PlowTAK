package com.atakmap.android.ideaplow.equipment

import com.atakmap.android.ideaplow.model.EquipmentState
import com.atakmap.android.ideaplow.model.Material
import com.atakmap.android.ideaplow.model.WidthPreset

/**
 * Driver-operated equipment state, wired to the big glove-friendly toggles in
 * the DriverPanel. Framework-free; the UI calls the setters.
 */
class ManualEquipmentProvider(
    initial: EquipmentState = EquipmentState()
) : EquipmentProvider {

    private val listeners = mutableListOf<EquipmentProvider.Listener>()

    override var state: EquipmentState = initial
        private set

    override fun addListener(l: EquipmentProvider.Listener) { listeners.add(l) }
    override fun removeListener(l: EquipmentProvider.Listener) { listeners.remove(l) }

    override fun start() { /* nothing to connect */ }
    override fun stop() { /* nothing to release */ }

    fun setBladeDown(down: Boolean) = update(state.copy(bladeDown = down))

    fun setSaltOn(on: Boolean) = update(state.copy(saltOn = on))

    fun setMaterial(material: Material) = update(state.copy(material = material))

    fun setWidthPreset(preset: WidthPreset) = update(state.copy(widthPreset = preset))

    /**
     * Merge a Bluetooth controller report (Phase 3). The hardware state
     * lands on the same channels as manual taps, so the driver's toggles
     * stay authoritative: a tap after a BT report simply wins because it is
     * newer, and recording never depends on BT being connected at all.
     * The width preset is deliberately untouched — controllers don't know
     * about wings/tow plows.
     */
    fun applyHardware(hw: EquipmentState) = update(
        state.copy(
            bladeDown = hw.bladeDown,
            saltOn = hw.saltOn,
            material = hw.material,
            rateLbsPerMi = hw.rateLbsPerMi,
            roadTempF = hw.roadTempF
        )
    )

    private fun update(next: EquipmentState) {
        if (next == state) return
        state = next
        listeners.toList().forEach { it.onEquipmentChanged(next) }
    }
}
