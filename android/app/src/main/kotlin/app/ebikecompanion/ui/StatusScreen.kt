package app.ebikecompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.sync.SyncEngine

class StatusActions(val connect: () -> Unit, val pair: () -> Unit, val disconnect: () -> Unit, val setClock: () -> Unit)

@Composable
fun StatusScreen(ui: BridgeLink.Ui, now: Long, up: SyncEngine.Ui, actions: StatusActions) {
    val rows = Board.sorted(Board.rows(ui, now, up))
    val busy = ui.phase != BridgeLink.Phase.IDLE && !Board.linkUp(ui, now)
    Column(Modifier.fillMaxSize()) {
        Text("STATUS", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
        CountPlate(Board.count(ui, now, up))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp).padding(top = 4.dp)) {
            // No item keys on purpose: rows re-sort as links change, and a key would keep the old scroll position.
            items(rows) { BoardRow(it) }
            item { Box(Modifier.fillMaxWidth().topRule(ink)) }
        }
        Row(Modifier.fillMaxWidth().topRule(ink).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (Board.linkUp(ui, now)) {
                Button(actions.setClock, Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) { Text("Set clock") }
                OutlinedButton(actions.disconnect, Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) { Text("Disconnect") }
            } else if (busy) {
                Button({}, Modifier.weight(1f).heightIn(min = 48.dp), enabled = false, shape = RectangleShape) { Text("Connecting") }
                OutlinedButton(actions.disconnect, Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) { Text("Cancel") }
            } else {
                Button(actions.connect, Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) { Text("Connect") }
                OutlinedButton(actions.pair, Modifier.weight(1f).heightIn(min = 48.dp), shape = RectangleShape) { Text("Pair phone") }
            }
        }
    }
}

@Composable
private fun BoardRow(row: BoardLine) {
    val bad = row.state == MarkState.BAD
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(ink).padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${row.label}: ${row.value}. ${row.sub}" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).border(2.dp, ink).then(if (bad) Modifier.background(Palette.Alert) else Modifier), contentAlignment = Alignment.Center) {
            Mark(row.state, 24.dp, onAlert = bad)
        }
        Column {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = soft)
                Text(row.value.uppercase(), style = MaterialTheme.typography.headlineMedium)
            }
            Text(row.sub, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
