package com.atakmap.android.plowtak.cot

import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.model.VehicleCapability
import com.atakmap.android.plowtak.model.WidthPreset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact free-text remarks for outbound self PLI so non-PlowTAK clients
 * can read truck setup, live equipment, shift, and storm from the marker.
 */
object PliRemarks {

    fun format(
        statusLabel: String,
        onShift: Boolean,
        stormName: String?,
        capability: VehicleCapability,
        equipment: EquipmentState
    ): String {
        val status = statusLabel.ifBlank { "?" }
        val shift = if (onShift) "Shift on" else "Shift off"
        val storm = stormName?.takeIf { it.isNotBlank() } ?: "No storm"
        val line1 = "$status | $shift | Storm $storm"
        val line2 = "Setup: ${setupLine(capability)}"
        val line3 = "Now: ${liveLine(capability, equipment)}"
        return "$line1\n$line2\n$line3"
    }

    private fun setupLine(cap: VehicleCapability): String {
        val parts = mutableListOf<String>()
        parts += if (cap.plowWidthM > 0.0) {
            "plow ${feetLabel(cap.plowWidthM)}"
        } else {
            "plow not fitted"
        }
        parts += if (cap.wingLeftWidthM > 0.0) {
            "L wing ${feetLabel(cap.wingLeftWidthM)}"
        } else {
            "L wing not fitted"
        }
        parts += if (cap.wingRightWidthM > 0.0) {
            "R wing ${feetLabel(cap.wingRightWidthM)}"
        } else {
            "R wing not fitted"
        }
        parts += if (cap.towWidthM > 0.0) {
            "tow ${feetLabel(cap.towWidthM)}"
        } else {
            "tow not fitted"
        }
        parts += if (cap.hasSalt) "spreader yes" else "spreader no"
        return parts.joinToString(", ")
    }

    private fun liveLine(cap: VehicleCapability, eq: EquipmentState): String {
        val parts = mutableListOf<String>()
        if (cap.hasBlade) {
            parts += if (eq.bladeDown) "blade down" else "blade up"
        }
        if (cap.wingLeftWidthM > 0.0) {
            parts += if (eq.wingLeftExtended) "L wing out" else "L wing up"
        }
        if (cap.wingRightWidthM > 0.0) {
            parts += if (eq.wingRightExtended) "R wing out" else "R wing up"
        }
        if (cap.towWidthM > 0.0) {
            parts += if (eq.widthPreset == WidthPreset.TOW) "tow down" else "tow up"
        }
        if (cap.hasSalt) {
            parts += if (eq.saltOn) {
                "spreader on (${eq.material.label})"
            } else {
                "spreader off"
            }
        }
        return if (parts.isEmpty()) "idle" else parts.joinToString(", ")
    }

    /** Whole feet when close; one decimal otherwise (matches Setup). */
    fun feetLabel(meters: Double): String {
        if (meters <= 0.0) return "0ft"
        val ft = VehicleCapability.metersToFeet(meters)
        val text = if (abs(ft - ft.roundToInt()) < 0.05) {
            ft.roundToInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", ft)
        }
        return "${text}ft"
    }
}
