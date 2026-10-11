package io.github.helliott20.dashlink.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import io.github.helliott20.dashlink.api.CamFile
import io.github.helliott20.dashlink.api.Category
import io.github.helliott20.dashlink.api.Lens
import io.github.helliott20.dashlink.data.DownloadState
import io.github.helliott20.dashlink.session.CameraMode
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.ConfirmDialog
import io.github.helliott20.dashlink.ui.components.EmptyState
import io.github.helliott20.dashlink.ui.theme.Amber
import io.github.helliott20.dashlink.util.Format

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesTab(openRemote: (CamFile) -> Unit, openDownloads: () -> Unit) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val tasks by c.downloads.tasks.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var lens by rememberSaveable { mutableStateOf<Lens?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Needs playback mode while visible; the session resumes recording once we leave.
    DisposableEffect(Unit) {
        c.session.acquireBrowsing()
        onDispose { c.session.releaseBrowsing() }
    }
    // (Re)load whenever the camera isn't in playback mode, e.g. on entry or after a reconnect.
    LaunchedEffect(state.connected, state.mode) {
        if (state.connected && state.mode != CameraMode.Playback) c.session.loadFiles()
    }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    val downloaded = remember(tasks) { tasks.filter { it.state == DownloadState.Done }.map { it.name }.toSet() }
    val downloading = remember(tasks) { tasks.filter { it.isActive }.map { it.name }.toSet() }
    val lenses = remember(state.files) { state.files.map { it.lens }.distinct().filter { it != Lens.Unknown } }
    val visible = remember(state.files, category, lens) {
        state.files.filter { (category == null || it.category == category) && (lens == null || it.lens == lens) }
    }
    val byDay = remember(visible) { visible.groupBy { it.time?.toLocalDate() } }
    val selectedFiles = visible.filter { it.fpath in selected }

    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                    Column(Modifier.weight(1f)) {
                        Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium)
                        Text(Format.bytes(selectedFiles.sumOf { it.size }), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { selected = visible.map { it.fpath }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                    IconButton(onClick = { c.session.download(selectedFiles); selected = emptySet() }) { Icon(Icons.Outlined.CloudDownload, "Download") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete") }
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(category == null, { category = null }, { Text("All ${state.files.size}") }) }
                items(Category.entries) { cat ->
                    val n = state.files.count { it.category == cat }
                    if (n > 0) FilterChip(category == cat, { category = if (category == cat) null else cat }, { Text("${cat.label} $n") })
                }
                if (lenses.size > 1) items(lenses) { l ->
                    FilterChip(lens == l, { lens = if (lens == l) null else l }, { Text(l.label) })
                }
            }
            if (state.filesLoaded) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Recording is paused while browsing and resumes when you go back to Live.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        PullToRefreshBox(isRefreshing = state.filesLoading, onRefresh = { c.session.loadFiles() }, modifier = Modifier.fillMaxSize()) {
            when {
                state.filesError != null && state.files.isEmpty() -> EmptyState(Icons.Outlined.ErrorOutline, "Couldn't load files", state.filesError!!) {
                    Button(onClick = { c.session.loadFiles() }) { Text("Retry") }
                }
                state.filesLoaded && visible.isEmpty() -> EmptyState(Icons.Outlined.FolderOff, "Nothing here", if (state.files.isEmpty()) "The SD card has no recordings yet." else "No files match these filters.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    byDay.forEach { (day, files) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h$day") {
                            Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
                                Text(day?.let { Format.day(it.atStartOfDay()) } ?: "Unknown date", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text("${files.size} · ${Format.bytes(files.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(files, key = { it.fpath }) { f ->
                            val isSel = f.fpath in selected
                            FileCard(
                                f, c.client.thumbnailUrl(f), isSel, selectionMode = selected.isNotEmpty(),
                                downloaded = f.name in downloaded, downloading = f.name in downloading,
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) selected = if (isSel) selected - f.fpath else selected + f.fpath
                                        else openRemote(f)
                                    },
                                    onLongClick = { selected = selected + f.fpath },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) ConfirmDialog(
        title = "Delete ${selectedFiles.size} file(s)?",
        body = "They'll be removed from the camera's SD card. Locked event clips may be refused by the camera." +
            (selectedFiles.count { it.locked }.takeIf { it > 0 }?.let { "\n\n$it of these are event (protected) clips." } ?: ""),
        confirm = "Delete", destructive = true,
        onConfirm = { c.session.delete(selectedFiles) { }; selected = emptySet() },
        onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun FileCard(f: CamFile, thumb: String, selected: Boolean, selectionMode: Boolean, downloaded: Boolean, downloading: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            SubcomposeAsyncImage(
                model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null, tint = Color.Gray) } },
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (f.lens != Lens.Unknown) Badge(f.lens.label)
                if (f.category != Category.Normal) Badge(f.category.label, if (f.category == Category.Event) Amber else null)
            }
            if (f.locked) Icon(Icons.Rounded.Lock, "Locked", Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp), tint = Amber)
            Text(
                f.time?.let { Format.time(it) } ?: f.name, Modifier.align(Alignment.BottomStart).padding(8.dp),
                color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            )
            when {
                selectionMode -> Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp), tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                )
                downloaded -> Icon(Icons.Rounded.DownloadDone, "Downloaded", Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp), tint = Color.White)
                downloading -> Icon(Icons.Outlined.CloudDownload, "Downloading", Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp), tint = Amber)
            }
        }
        Text(
            "${Format.bytes(f.size)} · ${f.name.substringAfterLast('_').ifEmpty { f.name }}",
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@Composable
private fun Badge(text: String, color: Color? = null) {
    Text(
        text, Modifier.clip(RoundedCornerShape(4.dp)).background((color ?: Color.Black).copy(alpha = if (color != null) 0.9f else 0.6f)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = if (color != null) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall,
    )
}
