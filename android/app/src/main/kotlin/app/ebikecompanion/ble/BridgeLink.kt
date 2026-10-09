package app.ebikecompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Connection to the ESP32 bridge: find or pair, connect, subscribe to live + status, set the clock.
 * Everything runs on the main thread except the Bluetooth callbacks, which only touch [ui] and a few fields.
 * Not yet a foreground service: the link lives as long as the app process (see docs/app/ANDROID_APP_DESIGN.md).
 */
@SuppressLint("MissingPermission")
class BridgeLink(private val ctx: Context) {
    enum class Phase { IDLE, SCANNING, BONDING, CONNECTING, CONNECTED }

    /** Events for the sync engine; called on Bluetooth threads, so implementations must hand off. */
    interface Listener {
        fun onReady()
        fun onLogPacket(bytes: ByteArray)
        fun onDisconnected()
    }

    @Volatile var listener: Listener? = null

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val status: BridgeStatus? = null,
        val statusAtMs: Long = 0,
        val live: Record? = null,
        val liveAtMs: Long = 0,
        val mtu: Int = 23,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private var want = false
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var notifyStarted = false
    private var failures = 0
    private val commands = ArrayDeque<ByteArray>()
    private var writing = false
    private var writeStartedAt = 0L

    // ---- public actions ------------------------------------------------------

    /** Connect to the bonded bridge, or find and pair a new one (press "eBike Pair phone" in Home Assistant first). */
    fun connect() {
        want = true
        failures = 0
        handler.removeCallbacksAndMessages(null)
        val bonded = adapter?.bondedDevices?.firstOrNull { it.name == Gatt.DEVICE_NAME }
        if (bonded != null) open(bonded) else scan()
    }

    fun pair() {
        want = true
        failures = 0
        handler.removeCallbacksAndMessages(null)
        scan()
    }

    fun disconnect() {
        want = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        command = null
        dropCommands()
        listener?.onDisconnected()
        set(Phase.IDLE, "")
    }

    fun setClock() = sendCommand(Commands.setTime(System.currentTimeMillis() / 1000))

    /** Commands are written one at a time; each waits for the previous write to complete. */
    fun sendCommand(bytes: ByteArray) {
        handler.post { commands.addLast(bytes); pumpCommands() }
    }

    private fun dropCommands() { commands.clear(); writing = false }

    @Suppress("DEPRECATION")
    private fun pumpCommands() {
        val c = command ?: return
        if (writing && SystemClock.elapsedRealtime() - writeStartedAt < 3_000) return
        val next = commands.removeFirstOrNull() ?: return
        writing = true
        writeStartedAt = SystemClock.elapsedRealtime()
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = next
        if (gatt?.writeCharacteristic(c) != true) writing = false
    }

    // ---- scanning and pairing -------------------------------------------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            handler.removeCallbacksAndMessages(null)
            pairDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) = fail("Scan failed (code $errorCode)")
    }
    private var scanning = false

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return fail("Bluetooth is off")
        set(Phase.SCANNING, "Looking for the bridge")
        val filter = ScanFilter.Builder().setDeviceName(Gatt.DEVICE_NAME).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        scanning = true
        handler.postDelayed({
            stopScan()
            fail("Bridge not found. Press \"eBike Pair phone\" in Home Assistant, then try again.")
        }, 30_000)
    }

    private fun stopScan() {
        if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCb)
        scanning = false
    }

    private fun pairDevice(dev: BluetoothDevice) {
        if (dev.bondState == BluetoothDevice.BOND_BONDED) return open(dev)
        set(Phase.BONDING, "Pairing: accept the request on the phone")
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != dev.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> { ctx.unregisterReceiver(this); open(dev) }
                    BluetoothDevice.BOND_NONE -> { ctx.unregisterReceiver(this); fail("Pairing failed or was declined") }
                }
            }
        }
        ContextCompat.registerReceiver(ctx, rcv, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        if (!dev.createBond()) { ctx.unregisterReceiver(rcv); fail("Could not start pairing") }
    }

    // ---- GATT ---------------------------------------------------------------------

    private fun open(dev: BluetoothDevice) {
        device = dev
        notifyStarted = false
        set(Phase.CONNECTING, "Connecting")
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun scheduleReconnect() {
        val dev = device ?: return
        handler.postDelayed({ if (want && gatt == null) open(dev) }, 3_000)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                failures = 0
                handler.postDelayed({ if (gatt === g) g.discoverServices() }, 600)
                return
            }
            g.close()
            if (gatt === g) gatt = null
            command = null
            dropCommands()
            listener?.onDisconnected()
            if (!want) return set(Phase.IDLE, "")
            failures++
            val hint = if (failures >= 3) "Cannot connect. If you pressed Clear Bonding on the ESP32, remove the bridge in Android's Bluetooth settings and pair again." else "Reconnecting"
            set(Phase.CONNECTING, hint)
            scheduleReconnect()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Gatt.SERVICE) ?: return fail("This is not the eBike bridge")
            command = svc.getCharacteristic(Gatt.COMMAND)
            g.requestMtu(Gatt.WANTED_MTU)
            handler.postDelayed({ startNotify(g) }, 1_500) // fallback if no MTU callback arrives
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            _ui.update { it.copy(mtu = mtu) }
            startNotify(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = nextNotify(g)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { writing = false; pumpCommands() }
        }

        @Deprecated("kept for API 31/32")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { handleNotification(c.uuid, it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handleNotification(c.uuid, value)
    }

    private fun startNotify(g: BluetoothGatt) {
        if (notifyStarted || gatt !== g) return
        notifyStarted = true
        val svc = g.getService(Gatt.SERVICE) ?: return
        listOf(Gatt.LIVE, Gatt.STATUS, Gatt.LOG).mapNotNull { svc.getCharacteristic(it) }.forEach { notifyQueue.addLast(it) }
        nextNotify(g)
    }

    @Suppress("DEPRECATION")
    private fun nextNotify(g: BluetoothGatt) {
        val c = notifyQueue.removeFirstOrNull()
        if (c == null) {
            if (_ui.value.phase != Phase.CONNECTED) {
                set(Phase.CONNECTED, "")
                setClock() // the phone owns the time: every connection sets it
                listener?.onReady()
            }
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Gatt.CCCD) ?: return nextNotify(g)
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(d)
    }

    private fun handleNotification(uuid: java.util.UUID, value: ByteArray) {
        val now = System.currentTimeMillis()
        when (uuid) {
            Gatt.STATUS -> Protocol.parseStatus(value)?.let { s -> _ui.update { it.copy(status = s, statusAtMs = now) } }
            Gatt.LIVE -> Protocol.parseRecord(value)?.let { r -> _ui.update { it.copy(live = r, liveAtMs = now) } }
            Gatt.LOG -> listener?.onLogPacket(value.copyOf())
        }
    }

    private fun set(phase: Phase, message: String) = _ui.update { it.copy(phase = phase, message = message) }

    private fun fail(message: String) {
        want = false
        stopScan()
        gatt?.close()
        gatt = null
        dropCommands()
        listener?.onDisconnected()
        set(Phase.IDLE, message)
    }
}
