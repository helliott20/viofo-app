package io.github.helliott20.dashlink.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helliott20.dashlink.api.Settings
import io.github.helliott20.dashlink.data.AppSettings
import io.github.helliott20.dashlink.session.CameraMode
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.LiveStream
import io.github.helliott20.dashlink.ui.components.Pill
import io.github.helliott20.dashlink.ui.components.RecDot
import io.github.helliott20.dashlink.ui.theme.RecordRed
import io.github.helliott20.dashlink.util.Format

@Composable
fun LiveTab(fullscreen: Boolean) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val prefs by c.prefs.settings.collectAsStateWithLifecycle(AppSettings())
    val activity = LocalContext.current as? Activity

    LaunchedEffect(state.connected) { if (state.connected) c.session.enterLive() }
    DisposableEffect(prefs.keepScreenOnLive) {
        if (prefs.keepScreenOnLive) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val ready = state.connected && state.mode == CameraMode.Live
    val video: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black)) {
            if (ready) {
                // Re-keyed on liveToken so the stream re-opens when the camera set changes.
                androidx.compose.runtime.key(state.liveToken, state.liveUrl) {
                    LiveStream(state.liveUrl, prefs.rtspOverTcp, Modifier.fillMaxSize())
                }
            } else {
                Text(if (state.connected) "Starting live view…" else "Waiting for camera…", Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.7f))
            }
            RecDot(state.recording, Modifier.align(Alignment.TopStart).padding(12.dp))
            if (fullscreen) {
                Row(Modifier.align(Alignment.CenterEnd).padding(16.dp)) { Controls(vertical = true) }
            }
        }
    }

    if (fullscreen) {
        video(Modifier.fillMaxSize())
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(20.dp))
        Controls(vertical = false)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Pill(if (state.recording == true) "Recording" else "Standby", icon = Icons.Outlined.Videocam)
            Pill(state.freeBytes?.let { Format.bytes(it) + " free" } ?: if (state.cardPresent) "SD card" else "No SD card", icon = Icons.Outlined.SdCard)
            state.micOn?.let { Pill(if (it) "Mic on" else "Mic off", icon = if (it) Icons.Outlined.Mic else Icons.Outlined.MicOff) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Tip: rotate your phone for full-screen live view.",
            Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Controls(vertical: Boolean) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var pipMenu by remember { mutableStateOf(false) }
    val recording = state.recording == true
    val recColor by animateColorAsState(if (recording) RecordRed else MaterialTheme.colorScheme.surfaceContainerHighest, label = "rec")
    val enabled = state.connected && !state.busy

    val items: List<@Composable () -> Unit> = listOf(
        {
            FilledTonalIconButton(onClick = { c.session.toggleMic() }, enabled = enabled, modifier = Modifier.size(56.dp)) {
                Icon(if (state.micOn == false) Icons.Outlined.MicOff else Icons.Outlined.Mic, "Microphone")
            }
        },
        {
            Box {
                FilledTonalIconButton(
                    onClick = { if (state.model?.pipCycles == true) c.session.switchCamera(null) else pipMenu = true },
                    enabled = enabled, modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Outlined.Cameraswitch, "Switch camera") }
                DropdownMenu(pipMenu, { pipMenu = false }) {
                    Settings.pipOptions(state.model).forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { pipMenu = false; c.session.switchCamera(i) })
                    }
                }
            }
        },
        {
            FilledIconButton(
                onClick = { c.session.toggleRecording() }, enabled = enabled,
                modifier = Modifier.size(76.dp).clip(CircleShape),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = recColor, contentColor = if (recording) Color.White else RecordRed),
            ) { Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, if (recording) "Stop recording" else "Start recording", Modifier.size(36.dp)) }
        },
        {
            FilledTonalIconButton(onClick = { c.session.takeSnapshot() }, enabled = enabled, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Outlined.CameraAlt, "Snapshot")
            }
        },
        {
            val landscape = activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            FilledTonalIconButton(onClick = {
                activity?.requestedOrientation = if (vertical || landscape) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }, modifier = Modifier.size(56.dp)) {
                Icon(if (vertical) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, "Full screen")
            }
        },
    )
    val shape = RoundedCornerShape(36.dp)
    if (vertical) {
        Column(
            Modifier.clip(shape).background(Color.Black.copy(alpha = 0.45f)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) { items.forEach { it() } }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { items.forEach { it() } }
    }
}
