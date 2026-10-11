package io.github.helliott20.dashlink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helliott20.dashlink.api.Group
import io.github.helliott20.dashlink.api.Kind
import io.github.helliott20.dashlink.api.SettingView
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.ConfirmDialog
import io.github.helliott20.dashlink.ui.components.SectionHeader
import io.github.helliott20.dashlink.util.Format

@Composable
fun CameraSettingsTab() {
    val c = LocalContainer.current
    val s = c.session
    val state by s.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<SettingView?>(null) }
    var textEdit by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // value, isCar
    var wifiEdit by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.connected) { if (state.connected) s.refreshValues() }

    val views = state.settings
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), state = rememberLazyListState()) {
        if (state.model == null && state.connected) item {
            Surface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("This camera (${state.firmware ?: "unknown firmware"}) isn't in DashLink's model list, so only basic controls are available.", Modifier.padding(16.dp))
            }
        }
        Group.entries.forEach { g ->
            val inGroup = views.filter { it.def.group == g }
            val extras = g == Group.Stamps && state.model != null
            if (inGroup.isEmpty() && !extras) return@forEach
            item(key = "g$g") { SectionHeader(g.title) }
            itemsIndexed(inGroup, key = { _, v -> "s${v.def.key}" }) { _, v ->
                SettingRow(v, enabled = state.connected && !state.busy, onChoose = { choosing = v }, onSwitch = { s.applySwitch(v, it) })
            }
            if (extras) {
                item(key = "car") {
                    Row2(Icons.Outlined.TextFields, "Car number stamp", state.carNumber?.ifEmpty { "Off" } ?: "—") { textEdit = (state.carNumber.orEmpty()) to true }
                }
                item(key = "stamp") {
                    Row2(Icons.Outlined.TextFields, "Custom text stamp", state.customStamp?.ifEmpty { "Off" } ?: "—") { textEdit = (state.customStamp.orEmpty()) to false }
                }
            }
        }

        item { SectionHeader("Wi-Fi") }
        item { Row2(Icons.Outlined.Wifi, "Camera Wi-Fi", state.wifiSsid ?: state.ssid ?: "—") { wifiEdit = true } }

        item { SectionHeader("Maintenance") }
        item { Row2(Icons.Outlined.AccessTime, "Sync clock with phone", "Sets the camera's date and time") { s.syncClock() } }
        item { Row2(Icons.Outlined.SdCard, "Format SD card", state.freeBytes?.let { "${Format.bytes(it)} free" } ?: "Erase all recordings") { confirm = "format" } }
        item { Row2(Icons.Outlined.RestartAlt, "Restart camera", "Reboots the dashcam; it'll reconnect automatically") { confirm = "restart" } }
        item { Row2(Icons.Outlined.SettingsBackupRestore, "Reset settings", "Restore factory defaults") { confirm = "reset" } }

        item { SectionHeader("About this camera") }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Info, null) },
                headlineContent = { Text(state.modelName?.let { "VIOFO $it" } ?: "Unknown model") },
                supportingContent = { Text("Firmware ${state.firmware ?: "unknown"}\nCheck viofo.com for firmware updates.") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            )
        }
    }

    choosing?.let { v ->
        ChoiceDialog(v, onPick = { i -> choosing = null; if (i != v.selected) s.applySetting(v, i) }, onDismiss = { choosing = null })
    }
    textEdit?.let { (value, isCar) ->
        TextSettingDialog(
            title = if (isCar) "Car number stamp" else "Custom text stamp", initial = value,
            onSave = { textEdit = null; if (isCar) s.setCarNumber(it) else s.setCustomStamp(it) },
            onDismiss = { textEdit = null },
        )
    }
    if (wifiEdit) WifiDialog(state.wifiSsid ?: state.ssid.orEmpty(), onSave = { ssid, pw -> wifiEdit = false; s.setWifi(ssid, pw) }, onDismiss = { wifiEdit = false })
    when (confirm) {
        "format" -> ConfirmDialog("Format SD card?", "Every recording on the card, including locked event clips, will be permanently erased.", "Format",
            onConfirm = { s.formatCard() }, onDismiss = { confirm = null }, destructive = true, typeToConfirm = "FORMAT")
        "reset" -> ConfirmDialog("Reset all settings?", "The camera returns to factory settings and restarts. Your recordings are kept.", "Reset",
            onConfirm = { s.resetSettings() }, onDismiss = { confirm = null }, destructive = true)
        "restart" -> ConfirmDialog("Restart camera?", "The camera reboots and the connection drops for about 30 seconds.", "Restart",
            onConfirm = { s.restartCamera() }, onDismiss = { confirm = null })
    }
}

@Composable
private fun SettingRow(v: SettingView, enabled: Boolean, onChoose: () -> Unit, onSwitch: (Boolean) -> Unit) {
    val value = when (v.def.kind) {
        Kind.Switch -> null
        Kind.Choice -> v.selected?.let { v.options[it] } ?: "Unknown (${v.rawValue})"
    }
    ListItem(
        modifier = Modifier.clickable(enabled = enabled && (v.def.kind == Kind.Switch || v.editable)) {
            if (v.def.kind == Kind.Switch) onSwitch(!v.switchOn) else onChoose()
        },
        headlineContent = { Text(v.def.title) },
        supportingContent = {
            val lines = listOfNotNull(value, v.def.hint.takeIf { v.def.kind == Kind.Switch }, "Change this on the camera".takeIf { !v.editable })
            if (lines.isNotEmpty()) Text(lines.joinToString("\n"), color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = if (v.def.kind == Kind.Switch) { { Switch(v.switchOn, onSwitch, enabled = enabled) } } else null,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun Row2(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun ChoiceDialog(v: SettingView, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(v.def.title) },
        text = {
            Column {
                v.def.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)) }
                if (v.def.key == "resolution") {
                    Text("Changing resolution takes a few seconds while the camera restarts recording.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(v.options) { i, label ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(i == v.selected, { onPick(i) })
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                        if (i < v.options.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TextSettingDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Up to 11 characters, shown on recordings. Leave empty to turn off.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == ' ' || ch == '-' }.take(11) }, singleLine = true,
                    supportingText = { Text("${text.length}/11") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WifiDialog(currentSsid: String, onSave: (String?, String?) -> Unit, onDismiss: () -> Unit) {
    var ssid by remember { mutableStateOf(currentSsid) }
    var pw by remember { mutableStateOf("") }
    val pwOk = pw.isEmpty() || pw.length in 8..32
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera Wi-Fi") },
        text = {
            Column {
                Text("The camera restarts its Wi-Fi after a change. DashLink saves the new details and reconnects automatically.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(ssid, { ssid = it }, label = { Text("Network name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text("New password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = !pwOk, supportingText = { Text(if (pwOk) "Leave empty to keep the current password" else "8–32 characters") })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(ssid.trim().takeIf { it != currentSsid && it.isNotEmpty() }, pw.ifEmpty { null }) },
                enabled = pwOk && ssid.isNotBlank() && (ssid.trim() != currentSsid || pw.isNotEmpty()),
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
