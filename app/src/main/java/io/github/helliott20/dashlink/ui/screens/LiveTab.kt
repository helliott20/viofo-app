package io.github.helliott20.dashlink.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helliott20.dashlink.api.Settings
import io.github.helliott20.dashlink.data.AppSettings
import io.github.helliott20.dashlink.session.CameraMode
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.LiveStream
import io.github.helliott20.dashlink.ui.components.RecDot
import io.github.helliott20.dashlink.ui.layoutInfo
import io.github.helliott20.dashlink.ui.theme.RecordRed
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity

/** Stand-in for the camera feed in screenshot tests and previews. */
val LocalLivePreview = androidx.compose.runtime.staticCompositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }

@Composable
fun LiveTab(
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit = {},
    openSettings: () -> Unit = {},
    openDownloads: () -> Unit = {},
) {
    val layout = layoutInfo()
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val prefs by c.prefs.settings.collectAsStateWithLifecycle(AppSettings())
    val tasks by c.downloads.tasks.collectAsStateWithLifecycle()
    val downloading = tasks.any { it.isActive }
    val activity = LocalContext.current as? Activity

    // Ask for live mode whenever we're shown and the camera isn't in it (also re-tried once downloads finish).
    LaunchedEffect(state.connected, state.mode, downloading) {
        if (state.connected && state.mode != CameraMode.Live) c.session.enterLive()
    }
    DisposableEffect(prefs.keepScreenOnLive) {
        if (prefs.keepScreenOnLive) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Hand orientation back to the system when leaving live view.
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    // Phones rotate into full screen; unfolded and tablet windows just drop the chrome.
    val setFullscreen: (Boolean) -> Unit = { on ->
        if (!layout.wide) activity?.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        onFullscreen(on)
    }
    BackHandler(fullscreen) { setFullscreen(false) }

    // Stop streaming while the app is in the background.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val ready = state.connected && state.mode == CameraMode.Live && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val video: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black)) {
            val preview = LocalLivePreview.current
            if (preview != null) {
                preview(Modifier.fillMaxSize())
            } else if (ready) {
                // Re-keyed on liveToken so the stream re-opens when the camera set changes.
                key(state.liveToken, state.liveUrl) { LiveStream(state.liveUrl, prefs.rtspOverTcp, Modifier.fillMaxSize()) }
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (downloading && state.mode == CameraMode.Playback) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.height(8.dp))
                        Text("Live view resumes when downloads finish", color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                        Text("View downloads", Modifier.clickable(onClick = openDownloads).padding(8.dp), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(if (state.connected) "Starting live view…" else "Waiting for camera…", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
            if (fullscreen) {
                RecDot(state.recording, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp))
                Row(Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                    Controls(vertical = true, fullscreen = true, onFullscreen = setFullscreen)
                }
            }
        }
    }

    when {
        fullscreen -> video(Modifier.fillMaxSize())
        layout.tabletop && layout.hingeTopPx != null -> TabletopLive(layout.hingeTopPx, layout.hingeBottomPx ?: layout.hingeTopPx, video) {
            Controls(vertical = false, fullscreen = false, onFullscreen = setFullscreen)
            Spacer(Modifier.height(16.dp))
            AtAGlance(openSettings)
        }
        layout.wide -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Controls(vertical = false, fullscreen = false, onFullscreen = setFullscreen) }
                Box(Modifier.weight(1f)) { AtAGlance(openSettings, horizontalPadding = 0.dp) }
            }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Spacer(Modifier.height(20.dp))
            Controls(vertical = false, fullscreen = false, onFullscreen = setFullscreen)
            Spacer(Modifier.height(20.dp))
            AtAGlance(openSettings)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Half-folded on a dashboard or desk: the picture fills the upright half above the hinge,
 * controls sit on the flat half below it, where thumbs can reach them.
 */
@Composable
private fun TabletopLive(hingeTopPx: Int, hingeBottomPx: Int, video: @Composable (Modifier) -> Unit, below: @Composable () -> Unit) {
    val density = LocalDensity.current
    var topInWindow by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().onGloballyPositioned { topInWindow = it.positionInWindow().y.toInt() }) {
        val upper = with(density) { (hingeTopPx - topInWindow).coerceAtLeast(0).toDp() }
        val gap = with(density) { (hingeBottomPx - hingeTopPx).coerceAtLeast(0).toDp() }
        video(Modifier.fillMaxWidth().height(upper))
        Spacer(Modifier.height(gap))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp)) { below() }
    }
}

/** A few key settings, readable at a glance; tap through to change them. */
@Composable
private fun AtAGlance(openSettings: () -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp) {
    val state by LocalContainer.current.session.state.collectAsStateWithLifecycle()
    val views = state.settings.associateBy { it.def.key }
    val rows = listOf("resolution" to "Resolution", "parking" to "Parking mode", "gsensor" to "G-sensor", "cyclic" to "Loop length")
        .mapNotNull { (k, label) -> views[k]?.let { v -> label to (v.selected?.let { i -> v.options[i] } ?: "—") } }
    if (rows.isEmpty()) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).clip(RoundedCornerShape(20.dp)).clickable(onClick = openSettings),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Camera setup", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Controls(vertical: Boolean, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    var pipMenu by remember { mutableStateOf(false) }
    val recording = state.recording == true
    val recColor by animateColorAsState(if (recording) RecordRed else MaterialTheme.colorScheme.surfaceContainerHighest, label = "rec")
    val enabled = state.connected && !state.busy

    @Composable
    fun Small(icon: ImageVector, label: String, onClick: () -> Unit, needsCamera: Boolean = true) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(onClick = onClick, enabled = !needsCamera || enabled, modifier = Modifier.size(if (vertical) 48.dp else 52.dp)) { Icon(icon, label) }
            if (!vertical) {
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    val items: List<@Composable () -> Unit> = listOf(
        { Small(if (state.micOn == false) Icons.Outlined.MicOff else Icons.Outlined.Mic, if (state.micOn == false) "Mic off" else "Mic on", { c.session.toggleMic() }) },
        {
            Box {
                Small(Icons.Outlined.Cameraswitch, "Camera", { if (state.model?.pipCycles == true) c.session.switchCamera(null) else pipMenu = true })
                DropdownMenu(pipMenu, { pipMenu = false }) {
                    Settings.pipOptions(state.model).forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { pipMenu = false; c.session.switchCamera(i) })
                    }
                }
            }
        },
        {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = { c.session.toggleRecording() }, enabled = enabled,
                    modifier = Modifier.size(if (vertical) 64.dp else 72.dp).clip(CircleShape),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = recColor, contentColor = if (recording) Color.White else RecordRed),
                ) { Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, if (recording) "Stop recording" else "Start recording", Modifier.size(34.dp)) }
                if (!vertical) {
                    Spacer(Modifier.height(4.dp))
                    Text(if (recording) "Stop" else "Record", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        { Small(Icons.Outlined.CameraAlt, "Snapshot", { c.session.takeSnapshot() }) },
        {
            Small(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, if (fullscreen) "Exit full screen" else "Full screen", {
                onFullscreen(!fullscreen)
            }, needsCamera = false)
        },
    )
    if (vertical) {
        Column(
            Modifier.clip(RoundedCornerShape(36.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) { items.forEach { it() } }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { items.forEach { it() } }
    }
}
