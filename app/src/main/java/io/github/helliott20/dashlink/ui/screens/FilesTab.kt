package io.github.helliott20.dashlink.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.SmartDisplay
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.helliott20.dashlink.ui.layoutInfo
import io.github.helliott20.dashlink.ui.theme.Amber
import io.github.helliott20.dashlink.ui.theme.Signage
import io.github.helliott20.dashlink.util.Format
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesTab(openRemote: (CamFile) -> Unit, openDownloads: () -> Unit, initialDetail: String? = null) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val tasks by c.downloads.tasks.collectAsStateWithLifecycle()
    val wide = layoutInfo().wide
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var lens by rememberSaveable { mutableStateOf<Lens?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var detailPath by rememberSaveable { mutableStateOf(initialDetail) }
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
    BackHandler(selected.isEmpty() && wide && detailPath != null) { detailPath = null }

    val downloaded = remember(tasks) { tasks.filter { it.state == DownloadState.Done }.map { it.name }.toSet() }
    val downloading = remember(tasks) { tasks.filter { it.isActive }.map { it.name }.toSet() }
    val lenses = remember(state.files) { state.files.map { it.lens }.distinct().filter { it != Lens.Unknown } }
    val visible = remember(state.files, category, lens) {
        state.files.filter { (category == null || it.category == category) && (lens == null || it.lens == lens) }
    }
    val byDay = remember(visible) { visible.groupBy { it.time?.toLocalDate() } }
    val allByDay = remember(state.files) { state.files.groupBy { it.time?.toLocalDate() } }
    val selectedFiles = visible.filter { it.fpath in selected }
    val detail = state.files.firstOrNull { it.fpath == detailPath }

    val list: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            if (selected.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                        Column(Modifier.weight(1f)) {
                            Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium)
                            Text(Format.bytes(selectedFiles.sumOf { it.size }), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { selected = visible.map { it.fpath }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                        IconButton(onClick = { c.session.download(selectedFiles); selected = emptySet() }) { Icon(Icons.Outlined.CloudDownload, "Download selected") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete selected") }
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
                        Text("Recording is paused while you browse. It restarts when you leave.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            PullToRefreshBox(isRefreshing = state.filesLoading, onRefresh = { c.session.loadFiles() }, modifier = Modifier.fillMaxSize()) {
                when {
                    state.filesError != null && state.files.isEmpty() -> EmptyState(Icons.Outlined.ErrorOutline, "Couldn't read the SD card", state.filesError!!) {
                        Button(onClick = { c.session.loadFiles() }) { Text("Try again") }
                    }
                    state.filesLoaded && visible.isEmpty() -> EmptyState(
                        Icons.Outlined.FolderOff, if (state.files.isEmpty()) "No recordings yet" else "No clips match",
                        if (state.files.isEmpty()) "Clips appear here once the camera has recorded something." else "Clear a filter to see more clips.",
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        byDay.forEach { (day, files) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "h$day") {
                                DayHeader(day, files, allByDay[day].orEmpty())
                            }
                            items(files, key = { it.fpath }) { f ->
                                val isSel = f.fpath in selected
                                FileCard(
                                    f, c.client.thumbnailUrl(f), isSel || (wide && f.fpath == detailPath), selectionMode = selected.isNotEmpty(),
                                    downloaded = f.name in downloaded, downloading = f.name in downloading,
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            when {
                                                selected.isNotEmpty() -> selected = if (isSel) selected - f.fpath else selected + f.fpath
                                                wide -> detailPath = f.fpath
                                                else -> openRemote(f)
                                            }
                                        },
                                        onLongClick = { selected = selected + f.fpath },
                                        onLongClickLabel = "Select",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (wide) {
        // List-detail: the clip plays beside the grid instead of replacing it.
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(1f).fillMaxHeight())
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (detail != null) {
                    val siblings = state.files.filter { it.groupKey == detail.groupKey && it.category == detail.category }
                    ClipDetail(detail, siblings, onSelect = { detailPath = it.fpath }, onDeleted = { detailPath = null })
                } else {
                    EmptyState(Icons.Outlined.SmartDisplay, "Pick a clip", "It plays here, next to the list. Long-press clips to select several.")
                }
            }
        }
    } else {
        list(Modifier.fillMaxSize())
    }

    if (confirmDelete) ConfirmDialog(
        title = "Delete ${selectedFiles.size} file${if (selectedFiles.size == 1) "" else "s"}?",
        body = "They'll be removed from the camera's SD card." +
            (selectedFiles.count { it.locked }.takeIf { it > 0 }?.let { "\n\n$it of these are protected event clips; the camera may refuse to delete them." } ?: ""),
        confirm = "Delete", destructive = true,
        onConfirm = { c.session.delete(selectedFiles); selected = emptySet() },
        onDismiss = { confirmDelete = false },
    )
}

/** Date, totals and a 24-hour strip showing when the camera was recording that day. */
@Composable
private fun DayHeader(day: LocalDate?, files: List<CamFile>, allThatDay: List<CamFile>) {
    Column(Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(day?.let { Format.day(it.atStartOfDay()) } ?: "Unknown date", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${files.size} clips, ${Format.bytes(files.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (day != null) {
            Spacer(Modifier.height(8.dp))
            DayStrip(allThatDay)
        }
    }
}

/**
 * The day as a road: grey where the camera recorded loop footage, amber where something
 * triggered an event, blue for parking. Clips from all lenses are merged.
 */
@Composable
private fun DayStrip(files: List<CamFile>) {
    val videos = remember(files) { files.filter { it.isVideo && it.time != null }.sortedBy { it.time } }
    if (videos.isEmpty()) return
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val loop = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val parking = MaterialTheme.colorScheme.secondary
    val events = videos.count { it.category == Category.Event }
    val first = videos.first().time!!; val last = videos.last().time!!
    val description = "Recorded from ${Format.time(first).dropLast(3)} to ${Format.time(last).dropLast(3)}" +
        if (events > 0) ", $events event clip${if (events == 1) "" else "s"}" else ""
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val perSec = size.width / 86_400f
            fun x(f: CamFile) = f.time!!.toLocalTime().toSecondOfDay() * perSec
            // Loop and parking first, events on top so they are never hidden.
            val ordered = videos.sortedBy { if (it.category == Category.Event) 1 else 0 }
            ordered.forEachIndexed { i, f ->
                val next = videos.firstOrNull { it.time!! > f.time!! }
                val dur = next?.let { (it.time!!.toLocalTime().toSecondOfDay() - f.time!!.toLocalTime().toSecondOfDay()).coerceIn(30, 600) } ?: 60
                val w = (dur * perSec).coerceAtLeast(3.dp.toPx())
                val color = when (f.category) { Category.Event -> Amber; Category.Parking -> parking; else -> loop }
                drawRoundRect(color, topLeft = Offset(x(f), 0f), size = Size(w, size.height), cornerRadius = r)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            listOf("00", "06", "12", "18", "24").forEachIndexed { i, h ->
                Text(h, style = MaterialTheme.typography.labelSmall, fontFamily = Signage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i < 4) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FileCard(f: CamFile, thumb: String, selected: Boolean, selectionMode: Boolean, downloaded: Boolean, downloading: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val description = listOfNotNull(
        f.time?.let { Format.time(it) }, f.lens.label.takeIf { f.lens != Lens.Unknown }, f.category.label,
        Format.bytes(f.size), "protected".takeIf { f.locked }, "downloaded".takeIf { downloaded }, "selected".takeIf { selected && selectionMode },
    ).joinToString(", ")
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description },
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
            if (f.locked) Icon(Icons.Rounded.Lock, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp), tint = Amber)
            Text(
                f.time?.let { Format.time(it) } ?: f.name, Modifier.align(Alignment.BottomStart).padding(8.dp),
                color = Color.White, style = MaterialTheme.typography.titleMedium, fontFamily = Signage, fontWeight = FontWeight.SemiBold,
            )
            when {
                selectionMode -> Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp), tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                )
                downloaded -> Icon(Icons.Rounded.DownloadDone, null, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp), tint = Color.White)
                downloading -> Icon(Icons.Outlined.CloudDownload, null, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp), tint = Amber)
            }
        }
        Text(
            Format.bytes(f.size), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
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
