package app.ebikecompanion.ui

import app.ebikecompanion.ble.BridgeLink

enum class MarkState { OK, WARN, OFF, BAD, UNKNOWN }
enum class Tone { NORMAL, BAD, WAIT }

data class BoardLine(val id: String, val label: String, val state: MarkState, val value: String, val sub: String)
data class Count(val tone: Tone, val title: String, val sub: String)

/** What the Status and Ride screens say, derived from the bridge's own reports. Pure, so it is unit-tested. */
object Board {
    const val STALE_MS = 5_000L
    const val FTP_W = 150 // placeholder until the rider sets an FTP in Settings

    fun linkUp(ui: BridgeLink.Ui, now: Long) =
        ui.phase == BridgeLink.Phase.CONNECTED && ui.status != null && now - ui.statusAtMs <= STALE_MS

    /** Seconds since anything arrived from the bridge, or null if nothing ever did. */
    fun silentFor(ui: BridgeLink.Ui, now: Long): Long? {
        val last = maxOf(ui.statusAtMs, ui.liveAtMs)
        return if (last == 0L) null else (now - last) / 1000
    }

    private fun unknown(id: String, label: String) = BoardLine(id, label, MarkState.UNKNOWN, "No data", "Bridge not connected")

    fun rows(ui: BridgeLink.Ui, now: Long): List<BoardLine> {
        val s = ui.status
        if (!linkUp(ui, now) || s == null) {
            val silent = silentFor(ui, now)
            val link = BoardLine(
                "link", "ESP32 bridge", MarkState.BAD,
                if (silent == null) "Not connected" else "Not found",
                if (ui.message.isNotEmpty()) ui.message else if (silent == null) "Tap Connect" else "Unreachable for $silent s",
            )
            return listOf(link) + listOf("bike" to "Bike", "sd" to "SD card", "clock" to "Clock", "ha" to "Home Assistant",
                "backlog" to "Backlog", "drop" to "Dropped", "up" to "ESP32 uptime").map { unknown(it.first, it.second) }
        }
        val dropped = s.dropped > 0 || s.writeFailures > 0
        return listOf(
            BoardLine("link", "ESP32 bridge", MarkState.OK, "Connected", "Phone link encrypted"),
            if (s.bikeConnected) BoardLine("bike", "Bike", MarkState.OK, "Connected", if (s.recording) "Recording rides" else "Connected")
            else BoardLine("bike", "Bike", MarkState.OFF, "Off", "Waiting for the bike"),
            if (s.sdOk) BoardLine("sd", "SD card", MarkState.OK, "OK", "${bytes(s.usedBytes)} on the card")
            else BoardLine("sd", "SD card", MarkState.BAD, "Missing", "Rides are not being saved"),
            if (s.clockValid) BoardLine("clock", "Clock", MarkState.OK, "Set", "Records are dated")
            else BoardLine("clock", "Clock", MarkState.BAD, "Not set", "Records are undated"),
            if (s.mqttUp) BoardLine("ha", "Home Assistant", MarkState.OK, "Online", "Uploading as you ride")
            else BoardLine("ha", "Home Assistant", MarkState.OFF, "Offline", "Saving to the card"),
            if (s.unacked == 0L) BoardLine("backlog", "Backlog", MarkState.OK, "0 waiting", "All uploaded")
            else BoardLine("backlog", "Backlog", MarkState.WARN, "${s.unacked} waiting", "Uploads when back online"),
            if (!dropped) BoardLine("drop", "Dropped", MarkState.OK, "0 dropped", "0 write failures")
            else BoardLine("drop", "Dropped", MarkState.BAD, "${s.dropped} dropped", "${s.writeFailures} write failures"),
            BoardLine("up", "ESP32 uptime", MarkState.OK, uptime(s.uptimeMs), "Boot %08X".format(s.bootId)),
        )
    }

    private fun rank(s: MarkState) = when (s) { MarkState.BAD -> 0; MarkState.WARN -> 1; MarkState.OFF -> 2; MarkState.UNKNOWN -> 3; MarkState.OK -> 4 }

    /** Problems first; ties keep their natural order. */
    fun sorted(rows: List<BoardLine>) = rows.sortedBy { rank(it.state) }

    fun count(ui: BridgeLink.Ui, now: Long): Count {
        val rows = sorted(rows(ui, now))
        val bad = rows.filter { it.state == MarkState.BAD }
        if (bad.isNotEmpty()) {
            val first = bad.first()
            return Count(Tone.BAD, if (bad.size == 1) "1 problem" else "${bad.size} problems", "${first.label}: ${first.value}")
        }
        val s = ui.status!!
        if (!s.bikeConnected) return Count(Tone.WAIT, "Switch bike on", "Bridge and card are ready")
        val moving = (ui.live?.speedKmh ?: 0f) > 0.5f
        return Count(Tone.NORMAL, "All clear", if (moving) "Riding" else "Bike connected")
    }

    /** The red banner on the Ride screen; never dismissible. Empty when all is well. */
    fun banner(ui: BridgeLink.Ui, now: Long): String {
        if (!linkUp(ui, now)) {
            val silent = silentFor(ui, now)
            return if (silent == null) "Bridge not connected." else "Bridge lost $silent s. Last values shown."
        }
        if (ui.status?.sdOk == false) return "SD card missing. Rides are not being saved."
        return ""
    }

    fun footer(ui: BridgeLink.Ui, now: Long): Pair<String, String> {
        val s = ui.status
        if (!linkUp(ui, now) || s == null) return "Card status unknown" to "Bridge not connected"
        val main = when {
            !s.sdOk -> "Card missing: not saving"
            !s.mqttUp -> "Offline: saving to card"
            s.unacked > 0 -> "Uploading saved data"
            else -> "Logging to card"
        }
        return main to if (s.unacked > 0) "${s.unacked} waiting to upload" else "Nothing waiting"
    }

    fun zoneIndex(watts: Float, ftp: Int = FTP_W): Int {
        val f = watts / ftp
        return when { f < .55f -> 0; f < .75f -> 1; f < .9f -> 2; f < 1.05f -> 3; else -> 4 }
    }

    val zoneNames = listOf("Recovery", "Endurance", "Tempo", "Threshold", "Surge")

    private fun uptime(ms: Long): String {
        val min = ms / 60_000
        return if (min < 1) "< 1 min" else if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    private fun bytes(b: Long) = if (b < 1024 * 1024) "${b / 1024} KB" else "%.1f MB".format(b / 1048576.0)
}
