package com.atakmap.android.plowtak.equipment

/**
 * Exponential reconnect backoff for the Bluetooth equipment link:
 * 1s, 2s, 4s, ... capped (default 60s). Reset on a successful connect so
 * a brief dropout recovers fast, while a truck with the controller powered
 * off doesn't hammer the radio all shift. Framework-free and tested;
 * [BluetoothEquipmentProvider] does the actual scheduling.
 */
class ReconnectBackoff(
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L
) {
    private var attempt = 0

    /** Delay to wait before the next connection attempt, then advances. */
    fun nextDelayMs(): Long {
        val shift = attempt.coerceAtMost(20)
        attempt++
        val d = baseDelayMs shl shift
        return if (d in 1..maxDelayMs) d else maxDelayMs
    }

    /** Call after a successful connect so the next drop retries quickly. */
    fun reset() {
        attempt = 0
    }

    val attempts: Int get() = attempt
}
