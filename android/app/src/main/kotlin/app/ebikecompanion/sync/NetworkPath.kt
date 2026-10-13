package app.ebikecompanion.sync

/**
 * The kinds of network the phone has right now (from NetworkCapabilities transports). Small on purpose; a fuller
 * classifier (home / Tailscale / mobile / other Wi-Fi) can grow from this.
 */
data class NetPath(val wifi: Boolean = false, val cellular: Boolean = false, val vpn: Boolean = false) {
    val none get() = !wifi && !cellular && !vpn
    fun describe(): String = listOfNotNull("wifi".takeIf { wifi }, "cellular".takeIf { cellular }, "vpn".takeIf { vpn })
        .joinToString("+").ifEmpty { "none" }
}

/** Whether a network event must restart the MQTT session. Pure, so it is unit tested. */
object RelayRestart {
    enum class Decision { KEEP, RESTART, LATER }

    const val MIN_INTERVAL_MS = 5_000L
    const val DEBOUNCE_MS = 1_500L

    /**
     * Restart when the address to use changed, or when the path changed while the relay is not connected (a connected
     * relay is left alone). LATER means "wanted, but too soon after the last restart": look again after the interval.
     * [sinceLastRestartMs] is the time since the relay was last (re)started.
     */
    fun decide(
        oldHost: String, newHost: String, oldPath: NetPath, newPath: NetPath,
        connected: Boolean, sinceLastRestartMs: Long, minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Decision {
        val hostChanged = newHost != oldHost
        val pathChanged = newPath != oldPath && !newPath.none // nothing to connect over: the client keeps retrying by itself
        if (!hostChanged && !(pathChanged && !connected)) return Decision.KEEP
        return if (sinceLastRestartMs < minIntervalMs) Decision.LATER else Decision.RESTART
    }
}
