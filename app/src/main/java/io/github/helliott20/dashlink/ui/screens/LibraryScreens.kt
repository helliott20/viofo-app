package io.github.helliott20.dashlink.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.helliott20.dashlink.data.AppSettings
import io.github.helliott20.dashlink.data.DownloadState
import io.github.helliott20.dashlink.data.LocalClip
import io.github.helliott20.dashlink.data.LocalMedia
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.EmptyState
import io.github.helliott20.dashlink.ui.components.SectionHeader
import io.github.helliott20.dashlink.util.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(back: () -> Unit, open: (Uri) -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var clips by remember { mutableStateOf<List<LocalClip>?>(null) }
    LaunchedEffect(reload) { clips = LocalMedia.list(context) }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            title = { Text("Saved clips") },
            actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Refresh") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
    }) { pad ->
        val list = clips
        when {
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(pad))
            list.isEmpty() -> EmptyState(Icons.Outlined.VideoLibrary, "No saved clips", "Clips you download from the camera appear here and in your phone's gallery (Movies/DashLink).", Modifier.padding(pad))
            else -> LazyVerticalGrid(
                GridCells.Adaptive(160.dp), Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.uri.toString() }) { clip ->
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { open(clip.uri) }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                            AsyncImage(clip.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (clip.isVideo) Icon(Icons.Rounded.PlayArrow, null, Modifier.align(Alignment.Center), tint = Color.White.copy(alpha = 0.85f))
                            if (clip.durationMs > 0) Text(
                                Format.duration(clip.durationMs / 1000), Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp),
                                color = Color.White, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(clip.name, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(back: () -> Unit, open: (Uri) -> Unit) {
    val d = LocalContainer.current.downloads
    val tasks by d.tasks.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            title = { Text("Downloads") },
            actions = {
                if (tasks.any { it.state == DownloadState.Failed || it.state == DownloadState.Paused }) TextButton(onClick = { d.retryAllFailed() }) { Text("Retry all") }
                if (tasks.any { it.state == DownloadState.Done }) TextButton(onClick = { d.clearFinished() }) { Text("Clear done") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
    }) { pad ->
        if (tasks.isEmpty()) {
            EmptyState(Icons.Outlined.CloudDownload, "No downloads", "Long-press clips in the camera's Files tab to select and download several at once.", Modifier.padding(pad))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(tasks, key = { it.id }) { t ->
                ListItem(
                    modifier = Modifier.clickable(enabled = t.savedUri != null) { t.savedUri?.let(open) },
                    headlineContent = { Text(t.name) },
                    supportingContent = {
                        Column {
                            Text(
                                when (t.state) {
                                    DownloadState.Queued -> "Waiting…"
                                    DownloadState.Running -> "${Format.bytes(t.bytes)} of ${Format.bytes(t.totalBytes)}" + if (t.bytesPerSecond > 0) " · ${Format.speed(t.bytesPerSecond)}" else ""
                                    DownloadState.Paused -> "Paused (camera disconnected) — resumes on reconnect"
                                    DownloadState.Done -> "Saved · tap to play"
                                    DownloadState.Failed -> "Failed: ${t.error}"
                                },
                                color = if (t.state == DownloadState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (t.state == DownloadState.Running || t.state == DownloadState.Paused) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator({ t.progress }, Modifier.fillMaxWidth())
                            }
                        }
                    },
                    trailingContent = {
                        Row {
                            if (t.state == DownloadState.Failed || t.state == DownloadState.Paused) IconButton(onClick = { d.retry(t.id) }) { Icon(Icons.Outlined.Refresh, "Retry") }
                            if (t.state != DownloadState.Done) IconButton(onClick = { d.cancel(t.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(back: () -> Unit) {
    val c = LocalContainer.current
    val s by c.prefs.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    fun set(f: (AppSettings) -> AppSettings) { scope.launch { c.prefs.update(f) } }
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            title = { Text("App settings") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { SectionHeader("Connection") }
            item { Toggle("Auto-connect on launch", "Reconnect to the last camera when the app opens", s.autoConnectLast) { v -> set { it.copy(autoConnectLast = v) } } }
            item { Toggle("Sync camera clock", "Set the camera's date and time from the phone on every connect", s.syncClockOnConnect) { v -> set { it.copy(syncClockOnConnect = v) } } }
            item { SectionHeader("Live view") }
            item { Toggle("Force RTSP over TCP", "More reliable on some phones; slightly more delay. DashLink switches automatically if UDP fails.", s.rtspOverTcp) { v -> set { it.copy(rtspOverTcp = v) } } }
            item { Toggle("Keep screen on", "While live view is open", s.keepScreenOnLive) { v -> set { it.copy(keepScreenOnLive = v) } } }
            item { Toggle("Show speed in mph", "Otherwise km/h, for GPS playback", s.speedMph) { v -> set { it.copy(speedMph = v) } } }
            item { SectionHeader("Recording") }
            item { Toggle("Resume recording after browsing", "The camera stops recording while you browse files; start it again when you return to Live or leave the app", s.resumeRecordingOnExit) { v -> set { it.copy(resumeRecordingOnExit = v) } } }
            item { SectionHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("DashLink 1.0") },
                    supportingContent = { Text("An unofficial companion app for VIOFO dashcams. Not affiliated with VIOFO.") },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!value) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(value, onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}
