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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.helliott20.dashlink.ui.layoutInfo
import io.github.helliott20.dashlink.ui.HingeSplit
import io.github.helliott20.dashlink.ui.TwoPane
import androidx.compose.runtime.movableContentOf
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import io.github.helliott20.dashlink.data.AppSettings
import io.github.helliott20.dashlink.session.CameraMode
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

/**
 * Streams a clip straight from the camera's SD card: the phone (folded) layout. Unfold while
 * watching and the clip moves into the Files pane, carrying on from the same moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotePlayerScreen(fpath: String, back: () -> Unit, toPane: () -> Unit = back) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val selected by c.ui.clip.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (c.ui.clip.value == null) c.ui.select(fpath) }
    val file = state.files.firstOrNull { it.fpath == (selected ?: fpath) }
    val snack = remember { SnackbarHostState() }
    val layout = layoutInfo()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    SessionSnackbars(snack)
    DisposableEffect(Unit) {
        c.session.acquireBrowsing()
        onDispose { c.session.releaseBrowsing() }
    }
    LaunchedEffect(state.connected, state.mode) {
        if (state.connected && state.mode != CameraMode.Playback) c.session.enterPlayback()
    }
    // Unfolded: the list-detail Files screen is the better home for this clip.
    LaunchedEffect(layout.wide) { if (layout.wide) toPane() }

    // Deleted, or the list was refreshed without it: nothing left to show.
    if (file == null) {
        LaunchedEffect(Unit) { back() }
        return
    }
    val siblings = state.files.filter { it.groupKey == file.groupKey && it.category == file.category }
    val video = remember { movableContentOf { m: Modifier, f: CamFile -> ClipVideo(f, m) } }
    if (landscape && !layout.wide) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { video(Modifier.fillMaxSize(), file) }
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
        ClipDetail(file, siblings, video = { m -> video(m, file) }, onDeleted = {}, modifier = Modifier.padding(pad))
    }
}

/** The clip's picture: video (resuming from the shared position) or a photo. */
@Composable
fun ClipVideo(file: CamFile, modifier: Modifier) {
    val c = LocalContainer.current
    Box(modifier.background(Color.Black)) {
        // Keyed so switching lens builds a fresh player; it starts where the last one was.
        androidx.compose.runtime.key(file.fpath) {
            if (file.isVideo) ClipPlayer(
                Uri.parse(c.client.fileUrl(file)), Modifier.fillMaxSize(),
                onPosition = { c.ui.report(it) },
                startPositionMs = c.ui.positionMs,
            )
            else coil.compose.AsyncImage(c.client.fileUrl(file), "Photo taken at ${file.time?.let { Format.time(it) } ?: file.name}", Modifier.fillMaxSize())
        }
    }
}

/** A camera clip: [video] on top, then lens switcher, details and actions. */
@Composable
fun ClipDetail(file: CamFile, siblings: List<CamFile>, video: @Composable (Modifier) -> Unit, onDeleted: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        ClipInfo(file, siblings, onDeleted)
    }
}

/** Lens switcher, what the clip is, and what you can do with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipInfo(file: CamFile, siblings: List<CamFile>, onDeleted: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    var confirmDelete by remember { mutableStateOf(false) }
    Column(modifier.padding(16.dp)) {
        if (siblings.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Front and rear were recorded together, so the new view picks up at the same moment.
                siblings.forEach { s -> FilterChip(s.fpath == file.fpath, { c.ui.select(s.fpath, keepPosition = true) }, { Text(s.lens.label) }) }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(file.time?.let { Format.day(it) + ", " + Format.time(it) } ?: file.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            listOfNotNull(file.category.label + " clip", Format.bytes(file.size), "protected from loop overwrite".takeIf { file.locked }).joinToString(", "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.session.download(listOf(file)) }) { Icon(Icons.Outlined.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text("Download") }
            if (siblings.size > 1) OutlinedButton(onClick = { c.session.download(siblings) }) { Text(if (siblings.size == 2) "Download both" else "Download all ${siblings.size}") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp))
            Text("Delete from SD card", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        Text("Download a clip to see its GPS route and speed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmDelete) ConfirmDialog("Delete this clip?", "It will be removed from the camera's SD card.", "Delete",
        onConfirm = { c.session.delete(listOf(file)); onDeleted() }, onDismiss = { confirmDelete = false }, destructive = true)
}

/** Plays a downloaded clip, with its GPS track on a map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlayerScreen(uri: Uri, back: () -> Unit) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var track by remember(uri) { mutableStateOf<List<GpsPoint>?>(null) }
    LaunchedEffect(uri) {
        track = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { GpsParser.parse(it) }
                }
            }.getOrNull().orEmpty()
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    val prefs by LocalContainer.current.prefs.settings.collectAsStateWithLifecycle(AppSettings())
    val speed: (Double) -> String = { kmh -> if (prefs.speedMph) String.format(Locale.US, "%.0f mph", kmh / 1.609344) else String.format(Locale.US, "%.0f km/h", kmh) }
    val point = track?.let { t -> if (t.isEmpty()) null else t[(position / 1000).toInt().coerceIn(0, t.lastIndex)] }
    val layout = layoutInfo()

    // One player for the screen's lifetime: folding, unfolding or rotating moves it, never restarts it.
    val video = remember(uri) {
        movableContentOf { m: Modifier, badge: String? ->
            Box(m.background(Color.Black)) {
                ClipPlayer(uri, Modifier.fillMaxSize(), onPosition = { position = it })
                badge?.let { SpeedBadge(it, Modifier.align(Alignment.TopEnd).padding(12.dp)) }
            }
        }
    }
    val badge = point?.let { speed(it.speedKmh) }

    if (landscape && !layout.wide) {
        video(Modifier.fillMaxSize(), badge)
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
        val gps: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                when {
                    track == null -> Text("Reading GPS…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    track!!.isEmpty() -> Text("No GPS in this clip. The camera's GPS was off or hadn't got a fix yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Stat("Speed", badge ?: "—")
                            Stat("Top speed", speed(track!!.maxOf { it.speedKmh }))
                            Stat("Position", point?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) } ?: "—")
                        }
                        RouteMap(track!!, point, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).padding(bottom = 16.dp).clip(RoundedCornerShape(16.dp)))
                    }
                }
            }
        }
        val body = Modifier.fillMaxSize().padding(pad)
        when {
            // Tabletop: watch on the upright half, map on the flat half.
            layout.tabletop -> HingeSplit(layout, top = { m -> video(m, badge) }, bottom = { m -> gps(m) }, modifier = body)
            // Unfolded or half-open like a book: video and map side by side.
            layout.wide -> TwoPane(
                layout,
                first = { m -> Box(m, contentAlignment = Alignment.Center) { video(Modifier.fillMaxWidth().aspectRatio(16f / 9f), badge) } },
                second = { m -> gps(m) },
                modifier = body, firstWeight = 1.3f,
            )
            else -> Column(body) {
                video(Modifier.fillMaxWidth().aspectRatio(16f / 9f), badge)
                gps(Modifier.weight(1f))
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
private fun SpeedBadge(text: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Speed, null)
            Spacer(Modifier.width(6.dp))
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}
