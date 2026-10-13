package app.ebikecompanion.sync

/**
 * Wording of the "EbikeMqtt" log lines, kept free of android.util.Log so it is unit tested.
 * Never put a password, the acknowledgement secret, a MAC or record contents in here: only the host, port, user name,
 * counts and exception messages.
 */
object MqttLog {
    const val TAG = "EbikeMqtt"

    /** Which broker address is in use: the home one, or the remote (away from home / VPN) one. */
    fun pathKind(host: String, homeIp: String) = if (homeIp.isNotBlank() && host.trim() == homeIp.trim()) "home" else "remote"

    fun relayStart(s: Settings, reason: String, network: NetPath): String =
        "relay start (${reason}): ${s.uri.substringAfter("://")} path=${pathKind(s.host, s.homeIp)} tls=${s.tls} " +
            "user=${s.user.ifBlank { "(none)" }} network=${network.describe()}"

    fun connectAttempt(uri: String) = "connect attempt: ${uri.substringAfter("://")}"

    fun failure(what: String, t: Throwable?): String {
        val cause = t?.cause?.let { c -> "; cause: ${c.javaClass.simpleName}: ${c.message}" } ?: ""
        return "$what: ${t?.javaClass?.simpleName ?: "unknown"}: ${t?.message}$cause"
    }

    fun published(count: Int, queued: Int) = "published $count record(s) in order; $queued in the queue"
    fun acked(dropped: Int) = "ack received: $dropped record(s) released"
    fun ackTimeout(resent: Int) = "no ack for ${SyncEngine.ACK_TIMEOUT_MS / 1000} s: will publish $resent record(s) again"
}

/** How an ack-topic subscribe ended. Only [OK] means Home Assistant's acknowledgements can reach this phone. */
enum class AckSub { PENDING, OK, DENIED, FAILED }

object SubscribeResult {
    /** MQTT 3.1.1 SUBACK return code for a refused subscription. */
    const val REFUSED = 0x80

    /** [granted] is the SUBACK list, null when unknown; [failure] is the exception when the request itself failed. */
    fun classify(granted: IntArray?, failure: Throwable?): AckSub = when {
        failure != null -> AckSub.FAILED
        granted != null && granted.any { it == REFUSED || it < 0 } -> AckSub.DENIED
        else -> AckSub.OK
    }

    const val DENIED_TEXT = "The broker would not let this phone listen for acknowledgements"
    const val FAILED_TEXT = "The phone could not ask the broker for acknowledgements"

    fun reason(r: AckSub, failure: Throwable? = null): String = when (r) {
        AckSub.DENIED -> DENIED_TEXT
        AckSub.FAILED -> FAILED_TEXT + (failure?.message?.let { ": $it" } ?: "")
        else -> ""
    }
}
