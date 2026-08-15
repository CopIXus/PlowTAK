package com.atakmap.android.plowtak.equipment

import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.model.Material
import com.atakmap.android.plowtak.model.WidthPreset

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

    /** Alias for [setSaltOn] — spreader / material applicator. */
    fun setSpreading(on: Boolean) = setSaltOn(on)

    fun setMaterial(material: Material) = update(state.copy(material = material))

    fun setWidthPreset(preset: WidthPreset) = update(state.copy(widthPreset = preset))

    /** Deploy / stow the tow plow. Deploying retracts wings. */
    fun setTowDeployed(deployed: Boolean) = update(
        if (deployed) {
            state.copy(
                widthPreset = WidthPreset.TOW,
                wingLeftExtended = false,
                wingRightExtended = false
            )
        } else {
            state.copy(widthPreset = WidthPreset.STANDARD)
        }
    )

    fun setWingLeft(extended: Boolean) {
        if (extended && !wingLeftAllowed()) return
        update(
            state.copy(
                wingLeftExtended = extended,
                widthPreset = when {
                    extended || state.wingRightExtended -> WidthPreset.WING
                    else -> WidthPreset.STANDARD
                }
            )
        )
    }

    fun setWingRight(extended: Boolean) {
        if (extended && !wingRightAllowed()) return
        update(
            state.copy(
                wingRightExtended = extended,
                widthPreset = when {
                    extended || state.wingLeftExtended -> WidthPreset.WING
                    else -> WidthPreset.STANDARD
                }
            )
        )
    }

    /** Optional gating from vehicle capability (0 width = not fitted). */
    var wingLeftAllowed: () -> Boolean = { true }
    var wingRightAllowed: () -> Boolean = { true }
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
