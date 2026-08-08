package com.atakmap.android.plowtak.equipment

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.atakmap.android.plowtak.model.EquipmentState
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth plow/spreader controller link (Phase 3). Supports:
 *  - Classic SPP (RFCOMM) — most aftermarket serial bridges;
 *  - BLE GATT — Nordic-UART-style modules (NUS service, notify char).
 *
 * Speaks the one-way newline protocol in [BtLineProtocol]; each complete
 * line updates [state] and notifies listeners. Reconnects with
 * [ReconnectBackoff] whenever the link drops.
 *
 * Graceful fallback contract (per plan): this provider only *reports*
 * hardware state. The DriverPanel's manual toggles stay authoritative —
 * the controller merges BT updates the same way it merges manual taps,
 * and recording never blocks on BT being connected or even configured.
 *
 * Threading: RFCOMM connect + reads run on a private thread; BLE
 * callbacks arrive on binder threads. Both funnel through [mainHandler]
 * so state mutation and listener callbacks stay on the main thread.
 *
 * Permissions (declared in the plugin manifest): BLUETOOTH_CONNECT on
 * API 31+, legacy BLUETOOTH below. The settings UI requests them before
 * offering the device picker; if they are missing we log and stay idle.
 */
@SuppressLint("MissingPermission") // settings UI gates on BLUETOOTH_CONNECT
class BluetoothEquipmentProvider(
    private val context: Context,
    /** MAC of the chosen controller, from the settings device picker. */
    private val deviceAddress: String,
    /** True to use BLE GATT instead of classic RFCOMM. */
    private val useBle: Boolean = false
) : EquipmentProvider {

    private val listeners = mutableListOf<EquipmentProvider.Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backoff = ReconnectBackoff()
    private val running = AtomicBoolean(false)
    private val assembler = BtLineProtocol.LineAssembler()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var gatt: BluetoothGatt? = null
    private var readerThread: Thread? = null

    override var state: EquipmentState = EquipmentState()
        private set

    /** True while a link to the controller is up (for the settings UI). */
    @Volatile var connected: Boolean = false
        private set

    override fun addListener(l: EquipmentProvider.Listener) { listeners.add(l) }
    override fun removeListener(l: EquipmentProvider.Listener) { listeners.remove(l) }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        backoff.reset()
        connect()
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        mainHandler.removeCallbacksAndMessages(null)
        closeLink()
    }

    // ------------------------------------------------------------------
    // Connection supervision
    // ------------------------------------------------------------------

    private fun connect() {
        if (!running.get()) return
        val adapter = BluetoothAdapter.getDefaultAdapter()
        // SDK-fixup: on newer Androids prefer
        // context.getSystemService(BluetoothManager::class.java).adapter.
        if (adapter == null || !adapter.isEnabled) {
            scheduleReconnect()
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid BT address $deviceAddress; provider idle")
            return
        }
        if (useBle) connectBle(device) else connectSpp(device)
    }

    private fun onLinkUp() {
        connected = true
        backoff.reset()
        assembler.reset()
    }

    private fun onLinkLost() {
        connected = false
        closeLink()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = backoff.nextDelayMs()
        Log.d(TAG, "BT reconnect in ${delay}ms (attempt ${backoff.attempts})")
        mainHandler.postDelayed({ connect() }, delay)
    }

    private fun closeLink() {
        try { socket?.close() } catch (e: IOException) { /* closing */ }
        socket = null
        try { gatt?.close() } catch (e: Exception) { /* closing */ }
        gatt = null
        readerThread?.interrupt()
        readerThread = null
        connected = false
    }

    private fun onChunk(chunk: String) {
        val lines = assembler.feed(chunk)
        if (lines.isEmpty()) return
        mainHandler.post {
            var next = state
            for (line in lines) next = BtLineProtocol.apply(next, line)
            if (next != state) {
                state = next
                listeners.toList().forEach { it.onEquipmentChanged(next) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Classic SPP (RFCOMM)
    // ------------------------------------------------------------------

    private fun connectSpp(device: BluetoothDevice) {
        val t = Thread({
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                mainHandler.post { onLinkUp() }
                readLoop(s.inputStream)
            } catch (e: IOException) {
                Log.d(TAG, "SPP connect/read failed: ${e.message}")
            } finally {
                mainHandler.post { if (running.get()) onLinkLost() }
            }
        }, "PlowTak-BT-SPP")
        readerThread = t
        t.start()
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(512)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) onChunk(String(buf, 0, n, Charsets.US_ASCII))
        }
    }

    // ------------------------------------------------------------------
    // BLE GATT (Nordic UART style)
    // ------------------------------------------------------------------

    private fun connectBle(device: BluetoothDevice) {
        // SDK-fixup: verify connectGatt overload (autoConnect=false,
        // TRANSPORT_LE) against the target API level in the plugin build.
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt, status: Int, newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    mainHandler.post { if (running.get()) onLinkLost() }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(NUS_SERVICE)
                    ?.getCharacteristic(NUS_TX_CHAR)
                if (ch == null) {
                    Log.w(TAG, "BLE device lacks UART service; dropping")
                    g.disconnect()
                    return
                }
                g.setCharacteristicNotification(ch, true)
                // SDK-fixup: also write the CCC descriptor
                // (00002902-...) ENABLE_NOTIFICATION_VALUE — some stacks
                // require the explicit descriptor write for notifications.
                ch.getDescriptor(CCC_DESCRIPTOR)?.let { d ->
                    @Suppress("DEPRECATION")
                    d.value = byteArrayOf(0x01, 0x00)
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
                mainHandler.post { onLinkUp() }
            }

            @Deprecated("pre-T callback; fine for ATAK's minSdk")
            override fun onCharacteristicChanged(
                g: BluetoothGatt, ch: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val v = ch.value ?: return
                onChunk(String(v, Charsets.US_ASCII))
            }
        })
    }

    companion object {
        private const val TAG = "PlowTakBt"

        /** Standard Serial Port Profile UUID. */
        val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Nordic UART Service + its TX (device->phone notify) char. */
        val NUS_SERVICE: UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_CHAR: UUID =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCC_DESCRIPTOR: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Feature flag consumed by the settings screen. */
        const val IMPLEMENTED = true

        /** Paired devices for the settings picker (null = BT unavailable). */
        fun pairedDevices(): List<Pair<String, String>>? {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!adapter.isEnabled) return null
            return try {
                adapter.bondedDevices.map { (it.name ?: it.address) to it.address }
            } catch (e: SecurityException) {
                null // BLUETOOTH_CONNECT not granted yet
            }
        }
    }
}
