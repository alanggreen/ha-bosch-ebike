package app.ebikecompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ebikecompanion.ble.BridgeLink
import kotlin.math.roundToInt

@Composable
fun RideScreen(ui: BridgeLink.Ui, now: Long) {
    val up = Board.linkUp(ui, now)
    val live = ui.live
    val banner = Board.banner(ui, now)
    val (footMain, footSub) = Board.footer(ui, now)
    val bikeOn = ui.status?.bikeConnected == true
    val watts = live?.riderPowerW
    val zone = Board.zoneIndex(watts ?: 0f)

    Column(Modifier.fillMaxSize()) {
        if (banner.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(Palette.Alert).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Mark(MarkState.BAD, 24.dp, onAlert = true)
                Text(banner, color = Palette.Bone, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).alpha(if (up) 1f else .45f)) {
            Text(
                if (!up) "Bridge not connected" else if (bikeOn) "Bike linked" else "Bike off",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 10.dp).bottomRule(ink),
            )
            Text("RIDER POWER", style = MaterialTheme.typography.labelMedium, color = soft, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                FlipCells(watts?.let { it.roundToInt().toString() } ?: "", "###", big = true)
                Text("W", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            }
            ZoneBar(zone, Modifier.padding(top = 12.dp))
            Text(
                if (!up) "Last known value" else if (bikeOn && watts != null) "Zone ${zone + 1}, ${Board.zoneNames[zone]}" else "Not riding",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            BoardRow("SPEED", live?.speedKmh?.let { "%.1f".format(it) } ?: "", "##.#", "km/h")
            BoardRow("CADENCE", live?.cadenceRpm?.let { it.roundToInt().toString() } ?: "", "###", "rpm")
            BoardRow("BATTERY", live?.batteryPercent?.let { it.roundToInt().toString() } ?: "", "###", "%")
            BoardRow("DISTANCE", live?.odometerKm?.let { "%.1f".format(it) } ?: "", "###.#", "km", last = true)
        }
        Row(Modifier.fillMaxWidth().topRule(ink).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(footMain, style = MaterialTheme.typography.labelLarge)
            Text(footSub, style = MaterialTheme.typography.bodyMedium, color = soft)
        }
    }
}

@Composable
private fun BoardRow(label: String, value: String, pattern: String, unit: String, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(ink).then(if (last) Modifier.bottomRule(ink) else Modifier).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = soft)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlipCells(value, pattern, big = false)
            Box(Modifier.width(56.dp).padding(start = 6.dp)) { Text(unit, style = MaterialTheme.typography.titleLarge, color = soft) }
        }
    }
}
