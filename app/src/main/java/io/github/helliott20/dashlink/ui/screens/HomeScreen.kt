package io.github.helliott20.dashlink.ui.screens

import android.content.Intent
import android.provider.Settings as SysSettings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.data.SavedDevice
import io.github.helliott20.dashlink.session.Phase
import io.github.helliott20.dashlink.session.SessionState
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.SectionHeader
import io.github.helliott20.dashlink.ui.components.SessionSnackbars
import io.github.helliott20.dashlink.ui.theme.OkGreen
import io.github.helliott20.dashlink.util.Format
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(openCamera: () -> Unit, openLibrary: () -> Unit, openDownloads: () -> Unit, openSettings: () -> Unit) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val devices by c.prefs.devices.collectAsStateWithLifecycle(emptyList())
    val tasks by c.downloads.tasks.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedDevice?>(null) }
    val scope = rememberCoroutineScope()
    SessionSnackbars(snack)

    // Jump straight into the camera once the handshake completes.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.Ready && !autoOpened) { autoOpened = true; openCamera() }
        if (state.phase != Phase.Ready) autoOpened = false
    }
    // Auto-connect to the most recent camera on launch.
    LaunchedEffect(Unit) {
        val s = c.prefs.settings.first()
        val last = c.prefs.devices.first().firstOrNull()
        if (s.autoConnectLast && last != null && c.session.state.value.phase == Phase.Disconnected) c.session.connect(last)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DashLink", fontWeight = FontWeight.Bold) },
                actions = {
                    val active = tasks.count { it.isActive }
                    IconButton(onClick = openDownloads) {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Icon(Icons.Outlined.Download, "Downloads") }
                    }
                    IconButton(onClick = openSettings) { Icon(Icons.Outlined.Settings, "App settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                ConnectionCard(
                    state = state,
                    onOpen = openCamera,
                    onCancel = { c.session.disconnect() },
                    onRetry = { devices.firstOrNull { it.ssid == state.ssid }?.let { c.session.connect(it) } ?: c.session.connectCurrentWifi() },
                    onAdd = { showAdd = true },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickTile(Icons.Outlined.VideoLibrary, "Saved clips", "On this phone", Modifier.weight(1f), openLibrary)
                    QuickTile(Icons.Outlined.Download, "Downloads", tasks.count { it.isActive }.let { if (it > 0) "$it in progress" else "Queue & history" }, Modifier.weight(1f), openDownloads)
                }
            }
            item { SectionHeader("My cameras") }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "No cameras yet. Switch on your dashcam's Wi-Fi (usually a long-press of the Wi-Fi button), then tap Add camera.",
                        Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(devices, key = { it.ssid }) { d ->
                DeviceRow(
                    d, active = state.ssid == d.ssid && state.phase != Phase.Disconnected && state.phase != Phase.Failed,
                    onClick = { if (state.ssid == d.ssid && state.phase == Phase.Ready) openCamera() else c.session.connect(d) },
                    onForget = { scope.launch { c.prefs.forgetDevice(d.ssid) } },
                    onEdit = { editing = d },
                )
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add camera") }
                    OutlinedButton(onClick = { c.session.connectCurrentWifi() }) { Text("Use current Wi-Fi") }
                }
            }
        }
    }

    if (showAdd) AddCameraDialog(
        onDismiss = { showAdd = false },
        onAdd = { ssid, pw -> showAdd = false; c.session.connect(SavedDevice(ssid, pw, null, System.currentTimeMillis())) },
    )
    editing?.let { d ->
        AddCameraDialog(
            initialSsid = d.ssid, initialPassword = d.password, title = "Edit camera",
            onDismiss = { editing = null },
            onAdd = { ssid, pw ->
                editing = null
                scope.launch {
                    if (ssid != d.ssid) c.prefs.forgetDevice(d.ssid)
                    c.prefs.saveDevice(d.copy(ssid = ssid, password = pw))
                }
            },
        )
    }
}

@Composable
private fun ConnectionCard(state: SessionState, onOpen: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        AnimatedContent(state.phase, label = "conn") { phase ->
            Column(Modifier.padding(20.dp)) {
                when (phase) {
                    Phase.Disconnected -> {
                        HeroIcon(Icons.Outlined.Videocam)
                        Text("Not connected", style = MaterialTheme.typography.titleLarge)
                        Text("Pick one of your cameras below, or add a new one. DashLink joins the camera's Wi-Fi for you and keeps your mobile data working.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onAdd) { Text("Add camera") }
                    }
                    Phase.JoiningWifi, Phase.Handshake, Phase.Reconnecting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (phase) {
                                        Phase.JoiningWifi -> "Joining ${state.ssid ?: "camera Wi-Fi"}…"
                                        Phase.Handshake -> "Talking to the camera…"
                                        else -> "Reconnecting…"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    when (phase) {
                                        Phase.JoiningWifi -> "Approve the connection if Android asks."
                                        Phase.Handshake -> "Reading model, settings and SD card."
                                        else -> state.message ?: "The camera dropped off briefly."
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    Phase.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = OkGreen, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(state.modelName?.let { "VIOFO $it" } ?: (state.ssid ?: "Camera"), style = MaterialTheme.typography.titleLarge)
                                Text(
                                    listOfNotNull("Connected", state.freeBytes?.let { "${Format.bytes(it)} free" }, if (state.recording == true) "Recording" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpen) { Text("Open camera") }
                            OutlinedButton(onClick = onCancel) { Text("Disconnect") }
                        }
                    }
                    Phase.Failed -> {
                        HeroIcon(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
                        Text("Couldn't connect", style = MaterialTheme.typography.titleLarge)
                        Text(state.message ?: "Unknown error", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "• Is the camera on with Wi-Fi enabled?\n• Default password is ${NovatekClient.DEFAULT_PASSWORD}\n• If the phone joined the camera manually, use \"Use current Wi-Fi\".",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRetry) { Text("Try again") }
                            OutlinedButton(onClick = { context.startActivity(Intent(SysSettings.ACTION_WIFI_SETTINGS)) }) { Text("Wi-Fi settings") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(52.dp).clip(CircleShape).padding(0.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = tint.copy(alpha = 0.15f), modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp)) }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun QuickTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceRow(d: SavedDevice, active: Boolean, onClick: () -> Unit, onForget: () -> Unit, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Outlined.Wifi else Icons.Outlined.WifiFind, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(d.model?.let { "VIOFO $it" } ?: d.ssid, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(d.ssid.takeIf { d.model != null }, d.lastConnected.takeIf { it > 0 }?.let { "last used " + DateUtils.getRelativeTimeSpanString(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit Wi-Fi details") }, leadingIcon = { Icon(Icons.Outlined.Key, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Forget") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onForget() })
                }
            }
        }
    }
}

@Composable
fun AddCameraDialog(
    onDismiss: () -> Unit,
    onAdd: (ssid: String, password: String) -> Unit,
    initialSsid: String = "",
    initialPassword: String = NovatekClient.DEFAULT_PASSWORD,
    title: String = "Add camera",
) {
    val c = LocalContainer.current
    var ssid by rememberSaveable { mutableStateOf(initialSsid.ifEmpty { c.link.currentSsid()?.takeIf { it.contains("VIOFO", true) }.orEmpty() }) }
    var pw by rememberSaveable { mutableStateOf(initialPassword) }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Enter the camera's Wi-Fi name exactly as it appears on the camera screen (e.g. VIOFO-A139-xxxxxx).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(ssid, { ssid = it.trim() }, label = { Text("Wi-Fi name (SSID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password") } },
                    supportingText = { if (pw.isNotEmpty() && pw.length < 8) Text("WPA2 passwords are at least 8 characters") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(ssid, pw) }, enabled = ssid.isNotBlank() && (pw.isEmpty() || pw.length >= 8)) { Text(if (title == "Add camera") "Connect" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
