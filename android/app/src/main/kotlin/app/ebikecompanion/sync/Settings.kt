package app.ebikecompanion.sync

import android.content.Context
import java.util.UUID

/** How the phone reaches Home Assistant's MQTT broker.
 *
 * There is a single internal HA server on the home Wi‑Fi, so the app chooses between a home IP and a remote VPN/Tailscale IP
 * based on the current SSID. The remote host stays as the default away-from-home address.
 */
data class Settings(
    val host: String = "",
    val port: Int = 1883,
    val tls: Boolean = false,
    val user: String = "",
    val password: String = "",
    val ackSecret: String = "",
    val topic: String = "ebike-bridge-mobile/ride_log",
    val homeIp: String = "",
    val homeSsids: List<String> = emptyList(),
    val rotateScreen: Boolean = false,
) {
    val configured get() = host.isNotBlank() || homeIp.isNotBlank()
    val ackTopic get() = "$topic/ack"
    val uri get() = (if (tls) "ssl" else "tcp") + "://${host.trim()}:$port"

    fun effectiveHost(activeSsid: String?): String {
        val ssid = Ssid.normalize(activeSsid) ?: return host.trim()
        return if (isHome(ssid) && homeIp.isNotBlank()) homeIp.trim() else host.trim()
    }

    fun isHome(activeSsid: String?): Boolean {
        val ssid = Ssid.normalize(activeSsid) ?: return false
        return homeSsids.any { Ssid.normalize(it).equals(ssid, ignoreCase = true) }
    }

    fun normalizedHomeSsids() = homeSsids.map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .take(5)
}

/** App-private SharedPreferences. The password is stored in the clear inside the app's sandbox. */
class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        host = p.getString("host", "") ?: "",
        port = p.getInt("port", 1883),
        tls = p.getBoolean("tls", false),
        user = p.getString("user", "") ?: "",
        password = p.getString("password", "") ?: "",
        ackSecret = p.getString("ackSecret", "") ?: "",
        topic = p.getString("topic", null) ?: Settings().topic,
        homeIp = p.getString("homeIp", "") ?: "",
        homeSsids = (p.getString("homeSsids", "") ?: "")
            .split(Regex("\\R+|,\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() },
        rotateScreen = p.getBoolean("rotateScreen", false),
    )

    fun save(s: Settings) = p.edit()
        .putString("host", s.host.trim())
        .putInt("port", s.port)
        .putBoolean("tls", s.tls)
        .putString("user", s.user)
        .putString("password", s.password)
        .putString("ackSecret", s.ackSecret)
        .putString("topic", s.topic.trim())
        .putString("homeIp", s.homeIp.trim())
        .putString("homeSsids", s.normalizedHomeSsids().joinToString("\n"))
        .putBoolean("rotateScreen", s.rotateScreen)
        .apply()

    /** A stable MQTT client id, so the broker sees one phone rather than a new client at every start. */
    fun clientId(): String = p.getString("clientId", null) ?: "ebike-phone-${UUID.randomUUID().toString().take(8)}".also { p.edit().putString("clientId", it).apply() }
}
