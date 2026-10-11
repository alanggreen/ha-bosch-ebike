package app.ebikecompanion.ui

import app.ebikecompanion.sync.Settings

/** What the rider has typed on the Settings screen, as plain values (no Compose types, so it is unit tested). */
data class SettingsDraft(
    val host: String,
    val homeIp: String,
    val port: String,
    val tls: Boolean,
    val user: String,
    val password: String,
    val secret: String,
    val topic: String,
    val rotateScreen: Boolean,
    val homeSsids: List<String>,
) {
    /** The port as a number: blank means the default, anything outside 1..65535 (or not a number) is null. */
    val portNumber: Int? get() = if (port.isBlank()) DEFAULT.port else port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    fun toSettings() = Settings(
        host = host.trim(),
        port = portNumber ?: DEFAULT.port,
        tls = tls,
        user = user,
        password = password,
        ackSecret = secret,
        topic = topic.trim().ifEmpty { DEFAULT.topic },
        homeIp = homeIp.trim(),
        homeSsids = homeSsids,
        rotateScreen = rotateScreen,
    )

    companion object {
        private val DEFAULT = Settings()
        fun from(s: Settings) = SettingsDraft(
            s.host, s.homeIp, s.port.toString(), s.tls, s.user, s.password, s.ackSecret, s.topic, s.rotateScreen, s.normalizedHomeSsids(),
        )
    }
}

/** Pure rules of the Settings screen: unsaved state, what blocks Save, the words of the status line. */
object SettingsForm {
    const val MAX_SSIDS = 5

    enum class Problem { NO_ADDRESS, BAD_PORT }

    /** Trims and applies the same defaults as saving, so "looks different" means "would save differently". */
    fun normalized(d: SettingsDraft): SettingsDraft = d.copy(
        host = d.host.trim(),
        homeIp = d.homeIp.trim(),
        port = d.port.trim().ifEmpty { Settings().port.toString() }.let { p -> p.toIntOrNull()?.toString() ?: p },
        topic = d.topic.trim().ifEmpty { Settings().topic },
        homeSsids = Settings(homeSsids = d.homeSsids).normalizedHomeSsids(),
    )

    /** True when the form differs from the saved settings. */
    fun isDirty(saved: Settings, draft: SettingsDraft) = normalized(draft) != normalized(SettingsDraft.from(saved))

    fun problem(d: SettingsDraft): Problem? = when {
        d.host.isBlank() && d.homeIp.isBlank() -> Problem.NO_ADDRESS
        d.portNumber == null -> Problem.BAD_PORT
        else -> null
    }

    /** Save is offered only for a changed, valid form. */
    fun canSave(saved: Settings, draft: SettingsDraft) = isDirty(saved, draft) && problem(draft) == null

    /** Why Save is off because of a mistake (not because nothing changed); null when the form is valid. */
    fun blockedReason(d: SettingsDraft): String? = when (problem(d)) {
        Problem.NO_ADDRESS -> "Enter an address at home or away from home to save."
        Problem.BAD_PORT -> "The port must be a number from 1 to 65535."
        null -> null
    }

    /** The Advanced group opens by itself when something in it is wrong. */
    fun advancedNeedsAttention(d: SettingsDraft) = problem(d) == Problem.BAD_PORT

    fun advancedSummary(d: SettingsDraft): String {
        val port = d.portNumber?.toString() ?: d.port.trim()
        return "${d.user.trim().ifEmpty { "No user name" }} on port $port"
    }

    /** Line under the port field. */
    fun portHint(d: SettingsDraft): String = when {
        d.port.isBlank() -> "Blank: port ${Settings().port} will be used."
        d.portNumber == null -> "Use a number from 1 to 65535."
        else -> "Usually 1883, or 8883 for a secure connection."
    }

    /** Supporting text of the TLS switch: the port the rider is about to use. */
    fun tlsHint(d: SettingsDraft): String =
        "Secure connections normally use port 8883. Port now: ${d.portNumber ?: d.port.trim()}."

    /** The address the app will really use on the network it is on now; blank when none is set for it. */
    fun hostUsed(d: SettingsDraft, activeSsid: String?): String = d.toSettings().effectiveHost(activeSsid)

    /** [text] is announced by TalkBack; [more] is detail that changes often and is not. */
    data class Line(val text: String, val more: String = "")

    fun shortError(raw: String, max: Int = 80): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= max) t else t.take(max).trimEnd() + "..."
    }

    /** [savedHost] is non-null only right after the rider saved: the address that was used (blank if none). */
    fun connectionLine(configured: Boolean, savedHost: String?, mqttUp: Boolean, queued: Int, error: String): Line = when {
        !configured -> Line("Not set up yet.", "Enter an address and press Save and connect.")
        mqttUp -> Line("Connected to the broker.", if (queued == 1) "1 record waiting." else "$queued records waiting.")
        savedHost != null && error.isBlank() ->
            if (savedHost.isBlank()) Line("Saved. No address is set for the network you are on now.", "Add the other address, or join a home network.")
            else Line("Saved. Connecting to $savedHost...")
        else -> Line(
            "Not connected to the broker.",
            (if (error.isBlank()) "" else "Reason: ${shortError(error)}. ") + "Check the address, and that Home Assistant is running.",
        )
    }

    // ---- the saved home networks in the draft ----

    /** The draft with [name] added, or null when it is blank, already there (any case) or the list is full. */
    fun addSsid(d: SettingsDraft, name: String): SettingsDraft? {
        val cleaned = name.trim()
        if (cleaned.isBlank() || d.homeSsids.size >= MAX_SSIDS || d.homeSsids.any { it.equals(cleaned, ignoreCase = true) }) return null
        return d.copy(homeSsids = d.homeSsids + cleaned)
    }

    /** The draft without [name] and the place it had, so Undo can put it back; null if it is not in the list. */
    fun removeSsid(d: SettingsDraft, name: String): Pair<SettingsDraft, Int>? {
        val i = d.homeSsids.indexOf(name)
        if (i < 0) return null
        return d.copy(homeSsids = d.homeSsids.filterIndexed { k, _ -> k != i }) to i
    }

    fun restoreSsid(d: SettingsDraft, name: String, index: Int): SettingsDraft {
        if (d.homeSsids.size >= MAX_SSIDS || d.homeSsids.any { it.equals(name, ignoreCase = true) }) return d
        val list = d.homeSsids.toMutableList().also { it.add(index.coerceIn(0, it.size), name) }
        return d.copy(homeSsids = list)
    }

    /**
     * The saved settings changed from [oldBase] to [newBase]. A form the rider has not edited follows them; a form with
     * unsaved edits is kept, and re-emitting an equal value changes nothing.
     */
    fun reconcile(oldBase: SettingsDraft, newBase: SettingsDraft, draft: SettingsDraft): SettingsDraft = when {
        newBase == oldBase -> draft
        normalized(draft) == normalized(oldBase) -> newBase
        else -> draft
    }
}
