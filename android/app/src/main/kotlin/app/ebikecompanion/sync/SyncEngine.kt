package app.ebikecompanion.sync

import android.content.Context
import android.os.SystemClock
import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.ble.Commands
import app.ebikecompanion.ble.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Moves ride records from the ESP32's card to Home Assistant through the phone, with nothing lost and nothing doubled.
 *
 * ESP32 --(BLE log stream)--> [RecordStore] --(MQTT, QoS 1)--> broker --> Home Assistant
 *                                  ^                                          |
 *                                  +------ ack {boot,seq} <-------------------+
 *
 * The rules that make it safe:
 * 1. The phone requests records after the last one it stored ([RecordStore.cursor]) and stores them durably first.
 * 2. Records are published strictly in order, from the head of the queue, never skipping one. Home Assistant drops any
 *    record at or below its high-water mark for a boot, so a skipped record could never be stored later.
 * 3. A record leaves the phone's queue, and is released on the ESP32 (ACK_UP_TO), only when Home Assistant has
 *    acknowledged it (the acknowledgement is signed when a secret is configured).
 * 4. Everything unacknowledged is simply published again after a timeout or a reconnect; Home Assistant ignores repeats.
 */
class SyncEngine(
    private val ctx: Context,
    private val link: BridgeLink,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
) : BridgeLink.Listener, MqttRelay.Listener {

    data class Ui(
        val configured: Boolean = false,
        val mqttUp: Boolean = false,
        val queued: Int = 0,
        val uploaded: Long = 0,
        val receiving: Boolean = false,
        val error: String = "",
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val mutex = Mutex()
    private lateinit var store: RecordStore
    private var relay: MqttRelay? = null
    private var linkReady = false
    private var receiving = false
    private var ignoring = false // after a damaged packet: skip the rest of that stream, then ask again
    private var sentCount = 0 // records at the head of the queue already published since the last (re)connect
    private var lastAckAt = 0L
    private var lastSyncRequest = 0L
    private var uploaded = 0L
    private var mqttUp = false
    private var error = ""

    fun start() {
        store = RecordStore(File(ctx.filesDir, "ride")).also { it.load() }
        _settings.value = settingsStore.load()
        link.listener = this
        scope.launch { mutex.withLock { startRelay() }; tickLoop() }
    }

    fun applySettings(s: Settings) {
        scope.launch {
            mutex.withLock {
                settingsStore.save(s)
                _settings.value = s
                relay?.stop(); relay = null
                mqttUp = false; error = ""; sentCount = 0
                startRelay()
                publishUi()
            }
        }
    }

    private fun startRelay() {
        val s = _settings.value
        if (!s.configured) { publishUi(); return }
        relay = MqttRelay(s, settingsStore.clientId(), this).also { it.start() }
        publishUi()
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(1000)
            mutex.withLock { tick() }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (linkReady && !receiving && now - lastSyncRequest > SYNC_INTERVAL_MS) requestSync(now)
        if (sentCount > 0 && now - lastAckAt > ACK_TIMEOUT_MS) sentCount = 0 // nothing acknowledged: publish them again
        pump()
        publishUi()
    }

    // ---- ESP32 side ---------------------------------------------------------------

    override fun onReady() {
        scope.launch {
            mutex.withLock {
                linkReady = true; receiving = false; ignoring = false
                store.pendingAck?.let { link.sendCommand(Commands.ackUpTo(it.boot, it.seq)); store.setPendingAck(null) }
                requestSync(SystemClock.elapsedRealtime())
            }
        }
    }

    override fun onDisconnected() {
        scope.launch { mutex.withLock { linkReady = false; receiving = false; ignoring = false } }
    }

    private fun requestSync(now: Long) {
        lastSyncRequest = now
        val c = store.cursor
        link.sendCommand(Commands.syncFrom(c.boot, c.seq))
    }

    override fun onLogPacket(bytes: ByteArray) {
        scope.launch {
            mutex.withLock {
                val raws = Protocol.splitLogPacket(bytes)
                when {
                    raws == null -> { ignoring = true; receiving = true } // damaged: do not store what follows, a gap would be silent
                    raws.isEmpty() -> { // end marker: caught up
                        receiving = false
                        if (ignoring) { ignoring = false; lastSyncRequest = 0 } // ask again from the cursor
                    }
                    ignoring -> {}
                    else -> { receiving = true; store.append(raws); pump() }
                }
                publishUi()
            }
        }
    }

    // ---- Home Assistant side ---------------------------------------------------------

    override fun onState(up: Boolean, error: String) {
        scope.launch {
            mutex.withLock {
                mqttUp = up; this@SyncEngine.error = error
                if (up) { sentCount = 0; pump() } // the old session may have lost what was in flight
                publishUi()
            }
        }
    }

    override fun onAck(ack: RecordJson.Ack) {
        scope.launch {
            mutex.withLock {
                if (!RecordJson.ackTrusted(ack, _settings.value.ackSecret)) return@withLock
                val dropped = store.ackThrough(ack.id)
                if (dropped >= 0) {
                    sentCount = maxOf(0, sentCount - dropped)
                    lastAckAt = SystemClock.elapsedRealtime()
                    uploaded += dropped
                    forwardAck(ack.id)
                }
                pump()
                publishUi()
            }
        }
    }

    /** Release the records on the ESP32 now, or remember to do it at the next connection. */
    private fun forwardAck(id: RecordId) {
        if (linkReady) { link.sendCommand(Commands.ackUpTo(id.boot, id.seq)); store.setPendingAck(null) } else store.setPendingAck(id)
    }

    /** Publish from the head of the queue, in order, up to the window. */
    private fun pump() {
        val r = relay ?: return
        if (!r.up) return
        val n = minOf(store.size, WINDOW)
        if (sentCount == 0 && n > 0) lastAckAt = SystemClock.elapsedRealtime()
        while (sentCount < n) {
            val rec = Protocol.parseRecord(store.get(sentCount)) ?: break
            r.publish(RecordJson.toJson(rec))
            sentCount++
        }
    }

    private fun publishUi() {
        _ui.value = Ui(_settings.value.configured, mqttUp && relay != null, if (::store.isInitialized) store.size else 0, uploaded, receiving, error)
    }

    companion object {
        const val WINDOW = 200
        const val ACK_TIMEOUT_MS = 15_000L
        const val SYNC_INTERVAL_MS = 20_000L
    }
}
