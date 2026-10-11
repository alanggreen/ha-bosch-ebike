package app.ebikecompanion.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.sync.Settings
import app.ebikecompanion.sync.Ssid
import app.ebikecompanion.sync.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Draft form values survive rotation and process recreation (the password stays inside the app's saved instance state only). */
private val DraftSaver: Saver<SettingsDraft, Any> = listSaver(
    save = { d -> listOf(d.host, d.homeIp, d.port, d.tls, d.user, d.password, d.secret, d.topic, d.rotateScreen, ArrayList(d.homeSsids)) },
    restore = { l ->
        @Suppress("UNCHECKED_CAST")
        SettingsDraft(l[0] as String, l[1] as String, l[2] as String, l[3] as Boolean, l[4] as String, l[5] as String, l[6] as String,
            l[7] as String, l[8] as Boolean, (l[9] as List<String>).toList())
    },
)

private val ScanSaver: Saver<Ssid.ScanState, Any> = listSaver(
    save = { s -> listOf(s.phase.name, s.startedAt, s.updatedAt, s.count) },
    restore = { l -> Ssid.ScanState(Ssid.ScanPhase.valueOf(l[0] as String), l[1] as Long, l[2] as Long, l[3] as Int) },
)

@Composable
fun SettingsScreen(current: Settings, bridge: BridgeLink.Ui, now: Long, up: SyncEngine.Ui, onSave: (Settings) -> Unit, batteryOk: Boolean, onBattery: () -> Unit, wifiAccess: Ssid.Access, onAllowLocation: () -> Unit) {
    // The form lives in saveable state: rotation does not wipe edits. It follows the saved settings only while it has no unsaved edits.
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(SettingsDraft.from(current)) }
    var baseline by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(SettingsDraft.from(current)) }
    LaunchedEffect(current) {
        val next = SettingsDraft.from(current)
        draft = SettingsForm.reconcile(baseline, next, draft)
        baseline = next
    }
    var ssidDraft by rememberSaveable { mutableStateOf("") }
    var savedHost by rememberSaveable { mutableStateOf<String?>(null) } // set right after Save: the address that was used
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var showNearby by rememberSaveable { mutableStateOf(false) }
    var scan by rememberSaveable(stateSaver = ScanSaver) { mutableStateOf(Ssid.ScanState()) }
    var rawScan by remember { mutableStateOf<List<Pair<String?, Int>>>(emptyList()) }
    val scroll = rememberScrollState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val dirty = SettingsForm.isDirty(current, draft)
    val canSave = SettingsForm.canSave(current, draft)
    val blocked = SettingsForm.blockedReason(draft)
    val canAdd = draft.homeSsids.size < SettingsForm.MAX_SSIDS
    val advancedShown = advancedOpen || SettingsForm.advancedNeedsAttention(draft)

    LaunchedEffect(dirty) { if (dirty) savedHost = null }

    fun save() {
        val next = draft.toSettings()
        savedHost = SettingsForm.hostUsed(draft, up.ssid)
        onSave(next)
    }
    fun addNetwork(name: String): Boolean {
        val next = SettingsForm.addSsid(draft, name) ?: return false
        draft = next
        return true
    }

    /** Back with unsaved edits asks first; with none it is the system's Back as before. */
    BackHandler(enabled = dirty) { confirmLeave = true }

    /** Reads Android's cached list and ends a running scan with it (fresh results, or the 10 s wait is over). */
    fun finishScan() {
        val r = readScan(ctx)
        rawScan = r
        scan = Ssid.scanResults(scan, System.currentTimeMillis(), Ssid.nearby(r, null, emptyList()).size)
    }
    // Fresh results arrive a few seconds after startScan; read them when Android announces them.
    DisposableEffect(showNearby, wifiAccess) {
        if (!showNearby || wifiAccess != Ssid.Access.OK) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { finishScan() } }
        ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
    }
    // After rotation the list itself is not saved; read Android's cache again.
    LaunchedEffect(showNearby, wifiAccess) { if (showNearby && wifiAccess == Ssid.Access.OK && rawScan.isEmpty()) rawScan = readScan(ctx) }
    LaunchedEffect(scan.phase, scan.startedAt) {
        if (scan.busy) {
            delay(Ssid.SCAN_TIMEOUT_MS)
            if (Ssid.scanTimedOut(scan, System.currentTimeMillis())) finishScan()
        }
    }

    // ---- At home ----
    val atHome: @Composable ColumnScope.() -> Unit = {
        SectionHead("At home", Modifier.padding(top = 12.dp))
        Field(
            "Address at home", draft.homeIp, { draft = draft.copy(homeIp = it) }, KeyboardType.Uri, ImeAction.Next,
            support = "Home Assistant on your home Wi-Fi, for example 192.168.0.10", contentType = null,
        )

        val connectedKey = Ssid.normalize(up.ssid)?.lowercase()
        SectionHead(
            "Saved home networks (${draft.homeSsids.size} of ${SettingsForm.MAX_SSIDS})", rule = false,
            color = if (canAdd) ink else alertText,
        )
        if (draft.homeSsids.isEmpty()) {
            Text("None saved yet. Type a name below, or add one from Nearby networks.", style = MaterialTheme.typography.bodyMedium, color = soft)
        } else {
            Column(Modifier.fillMaxWidth().topRule(ink)) {
                draft.homeSsids.forEach { ssid ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).bottomRule(soft.copy(alpha = 0.4f)),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(ssid, style = MaterialTheme.typography.bodyLarge)
                            if (connectedKey != null && Ssid.normalize(ssid)?.lowercase() == connectedKey) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Mark(MarkState.OK, 14.dp)
                                    Text("Connected now", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val gone = SettingsForm.removeSsid(draft, ssid) ?: return@OutlinedButton
                                draft = gone.first
                                snackbar.currentSnackbarData?.dismiss()
                                scope.launch {
                                    val r = snackbar.showSnackbar("Removed $ssid", actionLabel = "Undo", duration = SnackbarDuration.Long)
                                    if (r == SnackbarResult.ActionPerformed) draft = SettingsForm.restoreSsid(draft, ssid, gone.second)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp).semantics { contentDescription = "Delete $ssid" },
                            shape = RectangleShape,
                        ) { Text("Delete") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Field(
                "Add a network name", ssidDraft, { ssidDraft = it }, KeyboardType.Text, ImeAction.Done, modifier = Modifier.weight(1f),
                support = "Add puts the name on the list above. Save and connect applies it.",
                onDone = { if (addNetwork(ssidDraft)) ssidDraft = "" },
            )
            Button(
                onClick = { if (addNetwork(ssidDraft)) ssidDraft = "" },
                enabled = ssidDraft.isNotBlank() && canAdd,
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp).semantics { contentDescription = "Add network name to the list" },
                shape = RectangleShape,
            ) { Text("Add") }
        }
        if (!canAdd) Text("The list holds ${SettingsForm.MAX_SSIDS} home networks; delete one to add another.", style = MaterialTheme.typography.bodyMedium, color = alertText)
        val note = Ssid.note(wifiAccess, draft.homeSsids.isNotEmpty(), up.ssid, up.onHome)
        if (note.isNotEmpty()) {
            val problem = wifiAccess != Ssid.Access.OK
            Text(
                note, style = MaterialTheme.typography.bodyMedium, color = if (problem) alertText else soft,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (wifiAccess == Ssid.Access.NO_PERMISSION) {
                Text(
                    "Android treats the Wi-Fi name as location data. The app uses this permission only to read the name of the network " +
                        "it is on, never your position.",
                    style = MaterialTheme.typography.bodyMedium, color = soft,
                )
                Button(onClick = onAllowLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RectangleShape) { Text("Allow reading the Wi-Fi name") }
            }
        }

        // Nearby networks: a separate section, divided from the saved list by a full-width rule.
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(ink))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHead("Nearby networks", Modifier.weight(1f), rule = false)
            OutlinedButton(
                onClick = {
                    showNearby = true
                    if (wifiAccess == Ssid.Access.OK && !scan.busy) {
                        val cached = readScan(ctx)
                        rawScan = cached
                        scan = Ssid.scanPressed(System.currentTimeMillis(), startScan(ctx), Ssid.nearby(cached, null, emptyList()).size)
                    }
                },
                enabled = !scan.busy,
                modifier = Modifier.heightIn(min = 48.dp), shape = RectangleShape,
            ) { Text(Ssid.scanButtonLabel(scan)) }
        }
        if (!showNearby) {
            Text("Press Scan to list the Wi-Fi networks around you.", style = MaterialTheme.typography.bodyMedium, color = soft)
        } else {
            if (scan.busy) {
                val progress = Modifier.fillMaxWidth().height(4.dp).semantics { stateDescription = "Scanning for networks" }
                if (LocalReduceMotion.current) Box(progress.background(ink))
                else LinearProgressIndicator(progress, color = ink, trackColor = soft.copy(alpha = 0.3f), strokeCap = StrokeCap.Butt)
            }
            if (wifiAccess != Ssid.Access.OK) {
                Text(
                    Ssid.scanNote(wifiAccess, 0, true), style = MaterialTheme.typography.bodyMedium, color = alertText,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (wifiAccess == Ssid.Access.NO_PERMISSION) {
                    Button(onClick = onAllowLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RectangleShape) { Text("Allow reading the Wi-Fi name") }
                }
            } else {
                Text(
                    Ssid.scanStatus(scan), style = MaterialTheme.typography.bodyMedium, color = soft,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                val nearby = Ssid.nearby(rawScan, up.ssid, draft.homeSsids)
                Column(Modifier.fillMaxWidth()) {
                    nearby.forEach { n ->
                        val word = Ssid.signal(n.level).label
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).bottomRule(soft.copy(alpha = 0.4f)),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                Modifier.weight(1f).padding(vertical = 6.dp).clearAndSetSemantics {
                                    contentDescription = "${n.name}, ${if (n.connected) "connected now, " else ""}$word signal, ${n.level} dBm" + if (n.added) ", saved" else ""
                                },
                            ) {
                                Text(n.name, style = MaterialTheme.typography.bodyLarge, color = if (n.added) soft else ink)
                                Text(if (n.connected) "Connected, $word" else word, style = MaterialTheme.typography.bodyMedium, color = soft)
                            }
                            if (n.added) Text("Saved", style = MaterialTheme.typography.bodyMedium, color = soft, modifier = Modifier.padding(horizontal = 12.dp))
                            else Button(
                                onClick = { addNetwork(n.name) },
                                enabled = canAdd,
                                modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp).semantics { contentDescription = "Add ${n.name}" },
                                shape = RectangleShape,
                            ) { Text("Add") }
                        }
                    }
                }
            }
        }
    }

    // ---- Away from home ----
    val away: @Composable ColumnScope.() -> Unit = {
        SectionHead("Away from home", Modifier.padding(top = 12.dp))
        Field(
            "Address away from home", draft.host, { draft = draft.copy(host = it) }, KeyboardType.Uri, ImeAction.Next,
            support = "A VPN or Tailscale address",
        )
    }

    // ---- Screen ----
    val screen: @Composable ColumnScope.() -> Unit = {
        SectionHead("Screen", Modifier.padding(top = 12.dp))
        ToggleRow("Allow landscape", "Lets the screen turn sideways. Off keeps it upright.", draft.rotateScreen) { draft = draft.copy(rotateScreen = it) }
    }

    // ---- Running in the background ----
    val background: @Composable ColumnScope.() -> Unit = {
        SectionHead("Running in the background", Modifier.padding(top = 12.dp))
        Text(
            if (batteryOk) "Background use is allowed: the link and the upload keep running with the screen off."
            else "Android may pause the link in a pocket. Allow background use so it keeps running during a ride.",
            style = MaterialTheme.typography.bodyMedium, color = if (batteryOk) soft else ink,
        )
        if (!batteryOk) Button(onClick = onBattery, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RectangleShape) { Text("Allow background use") }
    }

    // ---- Advanced: MQTT ----
    val advanced: @Composable ColumnScope.() -> Unit = {
        SectionHead("Advanced: MQTT", Modifier.padding(top = 12.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = if (advancedShown) "Hide advanced settings" else "Show advanced settings") { advancedOpen = !advancedOpen }
                .semantics { stateDescription = if (advancedShown) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(SettingsForm.advancedSummary(draft), style = MaterialTheme.typography.bodyLarge)
                Text("Only change these if your installer says so.", style = MaterialTheme.typography.bodyMedium, color = soft)
            }
            Text(if (advancedShown) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (advancedShown) {
            Field("User name", draft.user, { draft = draft.copy(user = it) }, KeyboardType.Text, ImeAction.Next, contentType = ContentType.Username)
            Field(
                "Password", draft.password, { draft = draft.copy(password = it) }, KeyboardType.Password, ImeAction.Next,
                secret = true, revealed = showPassword, onReveal = { showPassword = !showPassword }, contentType = ContentType.Password,
            )
            Field(
                "Acknowledgement secret (optional)", draft.secret, { draft = draft.copy(secret = it) }, KeyboardType.Password, ImeAction.Next,
                support = "Must match Home Assistant", secret = true, revealed = showSecret, onReveal = { showSecret = !showSecret },
            )
            Field(
                "Topic", draft.topic, { draft = draft.copy(topic = it) }, KeyboardType.Uri, ImeAction.Next,
                support = "Where ride records are published. Leave as it is unless told otherwise.",
            )
            Field(
                "Port", draft.port, { draft = draft.copy(port = it.filter(Char::isDigit)) }, KeyboardType.Number, ImeAction.Done,
                support = SettingsForm.portHint(draft), isError = SettingsForm.advancedNeedsAttention(draft),
            )
            ToggleRow("Secure connection (TLS)", SettingsForm.tlsHint(draft), draft.tls) { draft = draft.copy(tls = it) }
        }
    }

    // ---- Pinned bar: Save, unsaved state, connection result ----
    val saveBar: @Composable () -> Unit = {
        val line = SettingsForm.connectionLine(current.configured, savedHost, up.mqttUp, up.queued, up.error)
        val texts: @Composable ColumnScope.() -> Unit = {
            if (dirty && savedHost == null) Text("Not saved", style = MaterialTheme.typography.labelLarge, color = ink)
            Text(line.text, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (line.more.isNotEmpty()) Text(line.more, style = MaterialTheme.typography.bodyMedium, color = soft)
            if (blocked != null) Text(blocked, style = MaterialTheme.typography.bodyMedium, color = ink)
        }
        val button: @Composable (Modifier) -> Unit = { m ->
            Button(onClick = { save() }, enabled = canSave, modifier = m.heightIn(min = 48.dp), shape = RectangleShape) { Text("Save and connect") }
        }
        Column(Modifier.fillMaxWidth().background(ground).topRule(ink).padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), content = texts)
                    button(Modifier.width(260.dp))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = texts)
                button(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        FaultBanner(Board.banner(bridge, now))
        Text(
            "SETTINGS", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
        if (landscape) {
            Row(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { atHome(); away() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { screen(); background(); advanced(); Spacer(Modifier.height(12.dp)) }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                atHome(); away(); screen(); background(); advanced(); Spacer(Modifier.height(12.dp))
            }
        }
        SnackbarHost(snackbar)
        saveBar()
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Save or discard?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("You changed settings that are not saved yet.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canSave) Button(onClick = { save(); confirmLeave = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RectangleShape) { Text("Save and connect") }
                    else if (blocked != null) Text(blocked, style = MaterialTheme.typography.bodyMedium, color = ink)
                    OutlinedButton(onClick = { draft = SettingsDraft.from(current); confirmLeave = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RectangleShape) { Text("Discard changes") }
                    TextButton(onClick = { confirmLeave = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RectangleShape) { Text("Keep editing") }
                }
            },
            shape = RectangleShape,
        )
    }
}

/** Cached scan results as (name, dBm); empty if Android refuses. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readScan(ctx: Context): List<Pair<String?, Int>> =
    runCatching { ctx.getSystemService(WifiManager::class.java)?.scanResults.orEmpty().map { it.SSID to it.level } }.getOrDefault(emptyList())

/** Best effort: Android throttles foreground scans (about four per two minutes); false means it said no. */
@Suppress("DEPRECATION")
private fun startScan(ctx: Context): Boolean =
    runCatching { ctx.getSystemService(WifiManager::class.java)?.startScan() == true }.getOrDefault(false)

/** A whole row toggles: the label is tappable and TalkBack names the switch. */
@Composable
private fun ToggleRow(label: String, support: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (support != null) Text(support, style = MaterialTheme.typography.bodyMedium, color = soft)
        }
        Switch(checked, null)
    }
}

@Composable
private fun Field(
    label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, ime: ImeAction,
    modifier: Modifier = Modifier, support: String? = null, isError: Boolean = false,
    secret: Boolean = false, revealed: Boolean = false, onReveal: (() -> Unit)? = null,
    contentType: ContentType? = null, onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = support?.let { { Text(it, color = if (isError) alertText else soft) } },
        isError = isError,
        singleLine = true,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (contentType != null) Modifier.semantics { this.contentType = contentType } else Modifier),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = type, imeAction = ime),
        keyboardActions = if (onDone != null) androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }) else androidx.compose.foundation.text.KeyboardActions.Default,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret && onReveal != null) {
            {
                TextButton(
                    onClick = onReveal,
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                        .semantics { contentDescription = (if (revealed) "Hide " else "Show ") + label.substringBefore(" (").lowercase() },
                    shape = RectangleShape,
                ) { Text(if (revealed) "Hide" else "Show", fontWeight = FontWeight.SemiBold) }
            }
        } else null,
    )
}
