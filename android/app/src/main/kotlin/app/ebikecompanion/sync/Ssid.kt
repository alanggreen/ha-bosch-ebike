package app.ebikecompanion.sync

/** Pure logic around the Wi-Fi name, kept free of Android types so it is unit tested. */
object Ssid {
    /**
     * Android hides the name behind "<unknown ssid>" (or null, or blank) when the app lacks location permission,
     * location services are off, or no Wi-Fi is connected. All of those mean "unknown", never a name to match.
     */
    fun normalize(raw: String?): String? {
        val t = raw?.trim()?.trim('"')?.trim() ?: return null
        if (t.isEmpty() || t.equals("<unknown ssid>", true) || t.equals("<unknown>", true) || t == "0x") return null
        return t
    }

    enum class Access { OK, NO_PERMISSION, LOCATION_OFF }

    fun access(permissionGranted: Boolean, locationOn: Boolean) = when {
        !permissionGranted -> Access.NO_PERMISSION
        !locationOn -> Access.LOCATION_OFF
        else -> Access.OK
    }

    /** Plain-language line for Settings. Empty when there is nothing to say (no home networks saved). */
    fun note(access: Access, hasHomeSsids: Boolean, activeSsid: String?, onHome: Boolean): String = when {
        !hasHomeSsids -> ""
        access == Access.NO_PERMISSION ->
            "The Wi-Fi name cannot be read, so the remote address is always used. Allow location, only to read the Wi-Fi name."
        access == Access.LOCATION_OFF ->
            "Location is switched off, so the Wi-Fi name cannot be read and the remote address is used. Switch location on in Android settings."
        activeSsid == null -> "No Wi-Fi name available now (not on Wi-Fi, or Android hides it). Using the remote address."
        onHome -> "On home network \"$activeSsid\": using the home address."
        else -> "On \"$activeSsid\", not a saved home network: using the remote address."
    }

    /** One row in the "Nearby networks" list. */
    data class Nearby(val name: String, val level: Int, val connected: Boolean, val added: Boolean)

    /**
     * Turns raw scan results (name, signal in dBm) into the list shown in Settings: blank/hidden/unknown names dropped,
     * duplicates (several access points of one network, case-insensitive) collapsed to the strongest signal,
     * strongest first (ties by name), the connected network marked, saved home networks marked as added.
     */
    fun nearby(raw: List<Pair<String?, Int>>, connected: String?, saved: List<String>): List<Nearby> {
        val conn = normalize(connected)
        val savedKeys = saved.mapNotNull { normalize(it)?.lowercase() }.toSet()
        val best = LinkedHashMap<String, Pair<String, Int>>()
        for ((rawName, level) in raw) {
            val name = normalize(rawName) ?: continue
            val key = name.lowercase()
            val old = best[key]
            if (old == null || level > old.second) best[key] = name to level
        }
        return best.map { (key, v) -> Nearby(v.first, v.second, conn != null && conn.equals(v.first, true), key in savedKeys) }
            .sortedWith(compareByDescending<Nearby> { it.level }.thenBy { it.name.lowercase() })
    }

    enum class Signal(val label: String) { STRONG("Strong"), FAIR("Fair"), WEAK("Weak") }

    /** Words for a signal level in dBm: at -60 or above strong, down to -75 fair, below that weak. */
    fun signal(dbm: Int) = when {
        dbm >= -60 -> Signal.STRONG
        dbm >= -75 -> Signal.FAIR
        else -> Signal.WEAK
    }

    enum class ScanPhase { IDLE, SCANNING, DONE, THROTTLED }

    /** State of the "Scan" button in Settings. [startedAt] and [updatedAt] are epoch milliseconds. */
    data class ScanState(val phase: ScanPhase = ScanPhase.IDLE, val startedAt: Long = 0, val updatedAt: Long = 0, val count: Int = 0) {
        val busy get() = phase == ScanPhase.SCANNING
    }

    const val SCAN_TIMEOUT_MS = 10_000L

    /** The button was pressed. A refused start (Android throttles scans) never leaves the state at "scanning". */
    fun scanPressed(now: Long, startAccepted: Boolean, cachedCount: Int): ScanState =
        if (startAccepted) ScanState(ScanPhase.SCANNING, startedAt = now, count = cachedCount)
        else ScanState(ScanPhase.THROTTLED, startedAt = now, updatedAt = now, count = cachedCount)

    /** Fresh results arrived. Only a running scan finishes; results from other apps' scans leave the state alone. */
    fun scanResults(state: ScanState, now: Long, count: Int): ScanState =
        if (state.phase == ScanPhase.SCANNING) ScanState(ScanPhase.DONE, state.startedAt, now, count) else state

    /** True when the wait is over; the caller then finishes with [scanResults] using whatever Android has cached. */
    fun scanTimedOut(state: ScanState, now: Long) = state.phase == ScanPhase.SCANNING && now - state.startedAt >= SCAN_TIMEOUT_MS

    fun scanButtonLabel(state: ScanState) = when (state.phase) {
        ScanPhase.SCANNING -> "Scanning..."
        ScanPhase.IDLE -> "Scan"
        else -> "Scan again"
    }

    private fun clock(ms: Long, zone: java.time.ZoneId) =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(java.time.Instant.ofEpochMilli(ms))

    /** The live status line under the Nearby heading. Empty while idle. */
    fun scanStatus(state: ScanState, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String = when (state.phase) {
        ScanPhase.IDLE -> ""
        ScanPhase.SCANNING -> "Scanning..."
        ScanPhase.DONE -> "Updated ${clock(state.updatedAt, zone)}, " + when (state.count) {
            0 -> "no networks found (is Wi-Fi on?)"
            1 -> "1 network"
            else -> "${state.count} networks"
        }
        ScanPhase.THROTTLED ->
            if (state.count == 0) "Android did not start a new scan (it limits scans) and has no earlier results. Try again in a few minutes."
            else "Android did not start a new scan (it limits scans). Showing the last results from earlier."
    }

    /** Plain line under the nearby list. [startAccepted] is false when Android refused the scan (throttled). */
    fun scanNote(access: Access, count: Int, startAccepted: Boolean): String = when {
        access == Access.NO_PERMISSION -> "Allow location, only to read Wi-Fi names, to list nearby networks."
        access == Access.LOCATION_OFF -> "Location is switched off, so Android hides nearby networks. Switch location on in Android settings."
        !startAccepted && count == 0 -> "Android limits how often Wi-Fi is scanned and no earlier results are available. Try again in a few minutes."
        !startAccepted -> "Android limits how often Wi-Fi is scanned, so these may be a few minutes old."
        count == 0 -> "No networks found yet. Results can take a few seconds; if still empty, Wi-Fi may be off."
        else -> ""
    }
}
