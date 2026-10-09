package app.ebikecompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ebikecompanion.sync.Settings
import app.ebikecompanion.sync.SyncEngine

@Composable
fun SettingsScreen(current: Settings, up: SyncEngine.Ui, onSave: (Settings) -> Unit, batteryOk: Boolean, onBattery: () -> Unit) {
    var host by remember(current) { mutableStateOf(current.host) }
    var port by remember(current) { mutableStateOf(current.port.toString()) }
    var tls by remember(current) { mutableStateOf(current.tls) }
    var user by remember(current) { mutableStateOf(current.user) }
    var password by remember(current) { mutableStateOf(current.password) }
    var secret by remember(current) { mutableStateOf(current.ackSecret) }
    var topic by remember(current) { mutableStateOf(current.topic) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text("Home Assistant MQTT broker", style = MaterialTheme.typography.labelLarge)
        Text(
            "The address the phone can reach: the home address when you are home, the Tailscale address when you are away.",
            style = MaterialTheme.typography.bodyMedium, color = soft,
        )
        Field("Broker address", host, { host = it; saved = false }, KeyboardType.Uri)
        Field("Port", port, { port = it.filter(Char::isDigit); saved = false }, KeyboardType.Number)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Use TLS (port 8883)", style = MaterialTheme.typography.bodyLarge)
            Switch(tls, { tls = it; saved = false })
        }
        Field("User name", user, { user = it; saved = false }, KeyboardType.Ascii)
        Field("Password", password, { password = it; saved = false }, KeyboardType.Password, secret = true)
        Field("Acknowledgement secret (optional)", secret, { secret = it; saved = false }, KeyboardType.Password, secret = true)
        Field("Topic", topic, { topic = it; saved = false }, KeyboardType.Uri)
        Button(
            onClick = {
                onSave(Settings(host.trim(), port.toIntOrNull() ?: 1883, tls, user, password, secret, topic.trim().ifEmpty { Settings().topic }))
                saved = true
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RectangleShape,
            enabled = host.isNotBlank(),
        ) { Text("Save and connect") }
        Text("Running in the background", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (batteryOk) "Background use is allowed: the link and the upload keep running with the screen off."
            else "Android may pause the link in a pocket. Allow background use so it keeps running during a ride.",
            style = MaterialTheme.typography.bodyMedium, color = soft,
        )
        if (!batteryOk) Button(onClick = onBattery, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RectangleShape) { Text("Allow background use") }
        Text(
            when {
                !current.configured -> "Not set up yet."
                saved && !up.mqttUp -> "Saved. Connecting to ${current.host}..."
                up.mqttUp -> "Connected to the broker. ${up.queued} records waiting."
                else -> "Not connected${if (up.error.isBlank()) "" else ": ${up.error.take(60)}"}"
            },
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}
