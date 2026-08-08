package com.atakmap.android.plowtak.equipment

import com.atakmap.android.plowtak.model.EquipmentState
import com.atakmap.android.plowtak.model.Material

/**
 * Line protocol for aftermarket plow/spreader controllers over Bluetooth
 * (classic SPP or BLE UART bridges). Newline-delimited ASCII, one report
 * per line, `KEY:VALUE`:
 *
 *   BLADE:UP        blade raised
 *   BLADE:DOWN      blade lowered (plowing)
 *   SPREADER:ON     spreader engaged
 *   SPREADER:OFF    spreader off
 *   RATE:<lbs/mi>   spreader application rate, decimal (e.g. RATE:250)
 *   TEMP:<F>        road surface temperature, decimal deg F (e.g. TEMP:28.4)
 *   MAT:<name>      material: SALT | SAND | BRINE | PREWET
 *
 * Keys and values are case-insensitive; `\r\n` and `\n` line endings both
 * accepted; unknown keys and malformed values are ignored (controllers in
 * the field emit all sorts of extra chatter). The protocol is one-way —
 * we never write to the controller.
 *
 * Framework-free: [BluetoothEquipmentProvider] feeds raw stream chunks to
 * [LineAssembler] and applies each complete line to its [EquipmentState]
 * via [apply]. Fully unit-tested in coretests.
 */
object BtLineProtocol {

    /** One parsed controller report. */
    sealed class Command {
        data class Blade(val down: Boolean) : Command()
        data class Spreader(val on: Boolean) : Command()
        data class Rate(val lbsPerMi: Double) : Command()
        data class Temp(val degF: Double) : Command()
        data class Mat(val material: Material) : Command()
    }

    /** Parses a single line; null for unknown/malformed lines. */
    fun parse(line: String): Command? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val sep = trimmed.indexOf(':')
        if (sep <= 0 || sep == trimmed.length - 1) return null
        val key = trimmed.substring(0, sep).trim().uppercase()
        val value = trimmed.substring(sep + 1).trim()
        return when (key) {
            "BLADE" -> when (value.uppercase()) {
                "DOWN" -> Command.Blade(down = true)
                "UP" -> Command.Blade(down = false)
                else -> null
            }
            "SPREADER" -> when (value.uppercase()) {
                "ON" -> Command.Spreader(on = true)
                "OFF" -> Command.Spreader(on = false)
                else -> null
            }
            "RATE" -> value.toDoubleOrNull()
                ?.takeIf { it >= 0.0 && it.isFinite() }
                ?.let { Command.Rate(it) }
            "TEMP" -> value.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > -100.0 && it < 200.0 }
                ?.let { Command.Temp(it) }
            "MAT" -> Material.fromWireName(value)?.let { Command.Mat(it) }
            else -> null
        }
    }

    /** Applies one line to the state; unchanged state for junk lines. */
    fun apply(state: EquipmentState, line: String): EquipmentState =
        when (val cmd = parse(line)) {
            is Command.Blade -> state.copy(bladeDown = cmd.down)
            is Command.Spreader -> state.copy(saltOn = cmd.on)
            is Command.Rate -> state.copy(rateLbsPerMi = cmd.lbsPerMi)
            is Command.Temp -> state.copy(roadTempF = cmd.degF)
            is Command.Mat -> state.copy(material = cmd.material)
            null -> state
        }

    /**
     * Reassembles complete lines from arbitrary stream chunks (RFCOMM
     * reads and BLE notifications split lines wherever they like).
     * Not thread-safe; the provider owns one per connection.
     */
    class LineAssembler(private val maxLineLength: Int = 256) {
        private val buf = StringBuilder()
        private var discarding = false

        /** Feed a chunk; returns the complete lines it finished. */
        fun feed(chunk: String): List<String> {
            val out = mutableListOf<String>()
            for (c in chunk) {
                if (c == '\n') {
                    if (!discarding && buf.isNotBlank()) out.add(buf.toString())
                    buf.setLength(0)
                    discarding = false
                } else if (c != '\r' && !discarding) {
                    buf.append(c)
                    // Runaway garbage guard: an oversized line is dropped in
                    // full — keep discarding until its newline finally shows.
                    if (buf.length > maxLineLength) {
                        buf.setLength(0)
                        discarding = true
                    }
                }
            }
            return out
        }

        fun reset() {
            buf.setLength(0)
            discarding = false
        }
    }
}
