package app.ebikecompanion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.sync.SyncEngine
import app.ebikecompanion.ui.SettingsScreen
import app.ebikecompanion.ui.Board
import app.ebikecompanion.ui.EbikeTheme
import app.ebikecompanion.ui.Palette
import app.ebikecompanion.ui.RideScreen
import app.ebikecompanion.ui.StatusActions
import app.ebikecompanion.ui.StatusScreen
import app.ebikecompanion.ui.ground
import app.ebikecompanion.ui.ink
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var afterPermission: (() -> Unit)? = null
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) afterPermission?.invoke()
        afterPermission = null
    }

    private fun withBluetooth(action: () -> Unit) {
        val need = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (need.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) action()
        else { afterPermission = action; askPermissions.launch(need.toTypedArray()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as EbikeApp
        setContent { EbikeTheme { App(app.link, app.engine, ::withBluetooth) } }
    }
}

@Composable
private fun App(link: BridgeLink, engine: SyncEngine, withBluetooth: (() -> Unit) -> Unit) {
    val ui by link.ui.collectAsStateWithLifecycle()
    val up by engine.ui.collectAsStateWithLifecycle()
    val settings by engine.settings.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val fault = Board.banner(ui, now).isNotEmpty()

    // Moving turns Status into the Ride face; the rider can go back with the navigation bar.
    val moving = Board.linkUp(ui, now) && (ui.live?.speedKmh ?: 0f) > 0.5f
    LaunchedEffect(moving) { if (moving) tab = 1 }

    // The screen stays on while riding (handlebar mount).
    val view = LocalView.current
    DisposableEffect(tab) { view.keepScreenOn = tab == 1; onDispose { view.keepScreenOn = false } }

    val actions = StatusActions(
        connect = { withBluetooth { link.connect() } },
        pair = { withBluetooth { link.pair() } },
        disconnect = link::disconnect,
        setClock = link::setClock,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                val colors = NavigationBarItemDefaults.colors(
                    indicatorColor = ink, selectedIconColor = ground, selectedTextColor = ink, unselectedIconColor = ink, unselectedTextColor = ink,
                )
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, colors = colors,
                    icon = { BadgedBox(badge = { if (fault && tab != 0) Badge(containerColor = Palette.Alert) }) { NavIcon(Icon.STATUS, selected = tab == 0) } },
                    label = { Text("Status") },
                )
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, colors = colors, icon = { NavIcon(Icon.RIDE, selected = tab == 1) }, label = { Text("Ride") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, colors = colors, icon = { NavIcon(Icon.SETTINGS, selected = tab == 2) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> StatusScreen(ui, now, up, actions)
                1 -> RideScreen(ui, now)
                else -> SettingsScreen(settings, up, engine::applySettings)
            }
        }
    }
}

private enum class Icon { STATUS, RIDE, SETTINGS }

/** Drawn icons in one 2 dp stroke: a checklist for Status, a gauge for Ride, sliders for Settings. */
@Composable
private fun NavIcon(icon: Icon, selected: Boolean) {
    val c = if (selected) ground else ink
    Canvas(Modifier.size(24.dp)) {
        val sw = 2.dp.toPx()
        fun line(a: Offset, b: Offset) = drawLine(c, a, b, sw)
        val u = size.width / 24f
        if (icon == Icon.STATUS) {
            drawRect(c, Offset(4 * u, 5 * u), Size(5 * u, 5 * u), style = Stroke(sw))
            drawRect(c, Offset(4 * u, 14 * u), Size(5 * u, 5 * u), style = Stroke(sw))
            line(Offset(13 * u, 7.5f * u), Offset(20 * u, 7.5f * u)); line(Offset(13 * u, 16.5f * u), Offset(20 * u, 16.5f * u))
        } else if (icon == Icon.RIDE) {
            drawArc(c, 180f, 180f, false, Offset(3 * u, 9 * u), Size(18 * u, 18 * u), style = Stroke(sw))
            line(Offset(12 * u, 18 * u), Offset(17 * u, 11 * u))
        } else { // three sliders
            listOf(6f to 8f, 12f to 15f, 18f to 6f).forEach { (y, x) ->
                line(Offset(4 * u, y * u), Offset(20 * u, y * u))
                drawRect(c, Offset((x - 2) * u, (y - 2.5f) * u), Size(4 * u, 5 * u))
            }
        }
    }
}
