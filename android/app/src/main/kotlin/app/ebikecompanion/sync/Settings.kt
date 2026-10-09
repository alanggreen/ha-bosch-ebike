package app.ebikecompanion.sync

import android.content.Context
import java.util.UUID

/** How the phone reaches Home Assistant's MQTT broker. Use the tailnet address when away from home. */
data class Settings(
    val host: String = "",
    val port: Int = 1883,
    val tls: Boolean = false,
    val user: String = "",
    val password: String = "",
    val ackSecret: String = "",
    val topic: String = "ebike-bridge-mobile/ride_log",
) {
    val configured get() = host.isNotBlank()
    val ackTopic get() = "$topic/ack"
    val uri get() = (if (tls) "ssl" else "tcp") + "://${host.trim()}:$port"
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
    )

    fun save(s: Settings) = p.edit()
        .putString("host", s.host.trim()).putInt("port", s.port).putBoolean("tls", s.tls)
        .putString("user", s.user).putString("password", s.password).putString("ackSecret", s.ackSecret)
        .putString("topic", s.topic.trim()).apply()

    /** A stable MQTT client id, so the broker sees one phone rather than a new client at every start. */
    fun clientId(): String = p.getString("clientId", null) ?: "ebike-phone-${UUID.randomUUID().toString().take(8)}".also { p.edit().putString("clientId", it).apply() }
}
