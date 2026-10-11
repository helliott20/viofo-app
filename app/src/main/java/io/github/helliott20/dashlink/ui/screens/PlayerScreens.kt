package io.github.helliott20.dashlink.ui.screens

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helliott20.dashlink.api.CamFile
import io.github.helliott20.dashlink.gps.GpsParser
import io.github.helliott20.dashlink.gps.GpsPoint
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.ClipPlayer
import io.github.helliott20.dashlink.ui.components.ConfirmDialog
import io.github.helliott20.dashlink.ui.components.RouteMap
import io.github.helliott20.dashlink.ui.components.SessionSnackbars
import io.github.helliott20.dashlink.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.util.Locale

/** Streams a clip straight from the camera's SD card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotePlayerScreen(fpath: String, back: () -> Unit) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf(fpath) }
    val file = state.files.firstOrNull { it.fpath == current }
    val siblings = remember(file, state.files) { file?.let { f -> state.files.filter { it.groupKey == f.groupKey && it.category == f.category } }.orEmpty() }
    var confirmDelete by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    SessionSnackbars(snack)

    if (file == null) {
        LaunchedEffect(Unit) { back() }
        return
    }
    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (file.isVideo) ClipPlayer(Uri.parse(c.client.fileUrl(file)), Modifier.fillMaxSize())
            else coil.compose.AsyncImage(c.client.fileUrl(file), null, Modifier.fillMaxSize())
        }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text(file.time?.let { Format.time(it) } ?: file.name) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                // Keyed so switching lens builds a fresh player.
                androidx.compose.runtime.key(file.fpath) {
                    if (file.isVideo) ClipPlayer(Uri.parse(c.client.fileUrl(file)), Modifier.fillMaxSize())
                    else coil.compose.AsyncImage(c.client.fileUrl(file), null, Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(16.dp)) {
                if (siblings.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        siblings.forEach { s -> FilterChip(s.fpath == file.fpath, { current = s.fpath }, { Text(s.lens.label) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(file.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(file.time?.let { Format.day(it) }, Format.bytes(file.size), file.category.label, "Locked".takeIf { file.locked }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.session.download(listOf(file)) }) { Icon(Icons.Outlined.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text("Download") }
                    if (siblings.size > 1) OutlinedButton(onClick = { c.session.download(siblings) }) { Text("All cameras") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, null) }
                }
                Spacer(Modifier.height(12.dp))
                Text("Tip: download a clip to see its GPS route and speed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this file?", "It will be removed from the camera's SD card.", "Delete",
        onConfirm = { c.session.delete(listOf(file)) { back() } }, onDismiss = { confirmDelete = false }, destructive = true)
}

/** Plays a downloaded clip, with its GPS track on a map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlayerScreen(uri: Uri, back: () -> Unit) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val track by produceState<List<GpsPoint>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { GpsParser.parse(it) }
                }
            }.getOrNull().orEmpty()
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    val point = track?.let { t -> if (t.isEmpty()) null else t[(position / 1000).toInt().coerceIn(0, t.lastIndex)] }
    val name = remember(uri) { uri.lastPathSegment.orEmpty() }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ClipPlayer(uri, Modifier.fillMaxSize(), onPosition = { position = it })
            point?.let { SpeedBadge(it, Modifier.align(Alignment.TopEnd).padding(16.dp)) }
        }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Clip") },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType(context.contentResolver.getType(uri) ?: "video/*")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Share clip"))
                    }) { Icon(Icons.Outlined.Share, "Share") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                ClipPlayer(uri, Modifier.fillMaxSize(), onPosition = { position = it })
                point?.let { SpeedBadge(it, Modifier.align(Alignment.TopEnd).padding(12.dp)) }
            }
            when {
                track == null -> Text("Reading GPS…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                track!!.isEmpty() -> Text("No GPS data in this clip (GPS off, no fix yet, or a photo).", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Stat("Speed", point?.let { String.format(Locale.US, "%.0f km/h", it.speedKmh) } ?: "—")
                        Stat("Max", String.format(Locale.US, "%.0f km/h", track!!.maxOf { it.speedKmh }))
                        Stat("Position", point?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) } ?: "—")
                    }
                    RouteMap(track!!, point, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).padding(bottom = 16.dp).clip(RoundedCornerShape(16.dp)))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeedBadge(p: GpsPoint, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Speed, null)
            Spacer(Modifier.width(6.dp))
            Text(String.format(Locale.US, "%.0f km/h", p.speedKmh), fontWeight = FontWeight.Bold)
        }
    }
}
