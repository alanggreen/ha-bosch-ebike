package app.ebikecompanion.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.ble.Commands
import app.ebikecompanion.ble.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        val ssid: String? = null, // the Wi-Fi name as far as Android lets the app read it
        val onHome: Boolean = false, // the relay is using the home address
        val acks: AckSub = AckSub.PENDING, // can Home Assistant's acknowledgements reach this phone? Only OK means yes.
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
    private var acks = AckSub.PENDING
    private var relayPath = NetPath()
    private var lastRestartAt = 0L
    private var recheckJob: Job? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null

    private fun log(msg: String) { Log.i(MqttLog.TAG, msg) }

    fun start() {
        store = RecordStore(File(ctx.filesDir, "ride")).also { it.load() }
        _settings.value = settingsStore.load()
        link.listener = this
        watchWifi()
        watchDefaultNetwork()
        scope.launch { mutex.withLock { startRelay("app start") }; tickLoop() }
    }

    /** Unregisters the network callbacks and closes the MQTT session (the records stay in the durable queue). */
    fun stop() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(wifiCallback) }
        defaultCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        defaultCallback = null
        recheckJob?.cancel()
        relay?.stop(); relay = null
    }

    fun applySettings(s: Settings) {
        scope.launch {
            mutex.withLock {
                settingsStore.save(s)
                _settings.value = s
                relay?.stop(); relay = null
                mqttUp = false; error = ""; sentCount = 0
                startRelay("settings applied")
                publishUi()
            }
        }
    }

    private fun startRelay(reason: String) {
        val s = _settings.value
        if (!s.configured) { publishUi(); return }
        val ssid = currentSsid()
        val resolved = s.copy(host = s.effectiveHost(ssid))
        relayHost = resolved.host
        relayPath = currentPath()
        lastRestartAt = SystemClock.elapsedRealtime()
        acks = AckSub.PENDING
        log(MqttLog.relayStart(resolved, reason, relayPath))
        relay = MqttRelay(resolved, settingsStore.clientId(), this).also { it.start() }
        publishUi()
    }

    /**
     * The Wi-Fi name, or null when unknown. Android returns "<unknown ssid>" without location permission or with location
     * services off; that is treated as unknown (remote host), never matched against the saved names.
     * The name is cached per network: a background read may be redacted even though an earlier read in the foreground
     * was not, and the same network keeps the same name.
     */
    private fun currentSsid(): String? {
        wifiNetwork?.let { n -> wifiNames[n]?.let { return it } }
        return Ssid.normalize(runCatching { ctx.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull())
    }

    private val wifiNames = mutableMapOf<Network, String>()
    private var wifiNetwork: Network? = null
    private var relayHost = ""

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val name = Ssid.normalize((caps.transportInfo as? WifiInfo)?.ssid)
            networkChanged(network, name, lost = false)
        }

        override fun onLost(network: Network) = networkChanged(network, null, lost = true)
    }

    /** The kinds of network the phone has now, from the transports of every network that offers the internet. */
    private fun currentPath(): NetPath = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return NetPath()
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        NetPath(
            wifi = caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
            cellular = caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            vpn = caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
        )
    }.getOrDefault(NetPath())

    private fun networkChanged(network: Network, name: String?, lost: Boolean) {
        scope.launch {
            mutex.withLock {
                if (lost) { wifiNames.remove(network); if (wifiNetwork == network) wifiNetwork = null }
                else { wifiNetwork = network; if (name != null) wifiNames[network] = name }
                reresolveHost("Wi-Fi changed")
            }
        }
    }

    /** Any default-network change (VPN up or down, Wi-Fi to mobile data...): look again after a short pause, events come in bursts. */
    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRecheck(RelayRestart.DEBOUNCE_MS)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleRecheck(RelayRestart.DEBOUNCE_MS)
        override fun onLost(network: Network) = scheduleRecheck(RelayRestart.DEBOUNCE_MS)
    }

    private fun scheduleRecheck(delayMs: Long) {
        recheckJob?.cancel()
        recheckJob = scope.launch {
            delay(delayMs)
            mutex.withLock { reresolveHost("network change") }
        }
    }

    private fun watchDefaultNetwork() {
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(defaultNetworkCallback)
            defaultCallback = defaultNetworkCallback
        }
    }

    /** Called after the location permission is granted or the Wi-Fi changes: restart the relay only if the host would differ. */
    fun recheckNetwork() { scope.launch { mutex.withLock { reresolveHost("network re-check") } } }

    private fun reresolveHost(reason: String) {
        val s = _settings.value
        if (!s.configured || relay == null) { publishUi(); return }
        val newHost = s.effectiveHost(currentSsid())
        val newPath = currentPath()
        val since = SystemClock.elapsedRealtime() - lastRestartAt
        when (RelayRestart.decide(relayHost, newHost, relayPath, newPath, mqttUp, since)) {
            RelayRestart.Decision.KEEP -> publishUi()
            RelayRestart.Decision.LATER -> { scheduleRecheck(RelayRestart.MIN_INTERVAL_MS - since + 200); publishUi() }
            RelayRestart.Decision.RESTART -> {
                // Same as a settings change: a new session; everything unacknowledged is published again, repeats are ignored by HA.
                // The last error stays until the new attempt gives a result.
                val why = if (newHost != relayHost) "$reason: address changed" else "$reason: ${relayPath.describe()} -> ${newPath.describe()} while not connected"
                relay?.stop(); relay = null
                mqttUp = false; sentCount = 0
                startRelay(why)
            }
        }
    }

    private fun watchWifi() {
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        }
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
        if (sentCount > 0 && now - lastAckAt > ACK_TIMEOUT_MS) { // nothing acknowledged: publish them again
            log(MqttLog.ackTimeout(sentCount)); sentCount = 0
        }
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

    override fun onState(up: Boolean, error: String, acks: AckSub) {
        scope.launch {
            mutex.withLock {
                mqttUp = up; this@SyncEngine.error = error; this@SyncEngine.acks = acks
                // A new session (acks still PENDING): the old one may have lost what was in flight. The subscribe result
                // that follows must not reset it again.
                if (up && acks == AckSub.PENDING) sentCount = 0
                if (up) pump()
                if (!up) scheduleRecheck(RelayRestart.DEBOUNCE_MS) // the path may have changed under a dropped session
                publishUi()
            }
        }
    }

    override fun onAck(ack: RecordJson.Ack) {
        scope.launch {
            mutex.withLock {
                if (!RecordJson.ackTrusted(ack, _settings.value.ackSecret)) return@withLock
                val dropped = store.ackThrough(ack.id)
                log(MqttLog.acked(maxOf(dropped, 0)))
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
        var published = 0
        while (sentCount < n) {
            val rec = Protocol.parseRecord(store.get(sentCount)) ?: break
            r.publish(RecordJson.toJson(rec))
            sentCount++; published++
        }
        if (published > 0) log(MqttLog.published(published, store.size))
    }

    private fun publishUi() {
        val s = _settings.value
        val ssid = wifiNetwork?.let { wifiNames[it] }
        _ui.value = Ui(
            s.configured, mqttUp && relay != null, if (::store.isInitialized) store.size else 0, uploaded, receiving, error,
            ssid, relay != null && s.homeIp.isNotBlank() && relayHost == s.homeIp.trim(), acks,
        )
    }

    companion object {
        const val WINDOW = 200
        const val ACK_TIMEOUT_MS = 15_000L
        const val SYNC_INTERVAL_MS = 20_000L
    }
}
