package io.github.helliott20.dashlink.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helliott20.dashlink.api.CamFile
import io.github.helliott20.dashlink.session.Phase
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.RecDot
import io.github.helliott20.dashlink.ui.components.SessionSnackbars
import io.github.helliott20.dashlink.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(back: () -> Unit, openRemote: (CamFile) -> Unit, openDownloads: () -> Unit) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    SessionSnackbars(snack)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val immersive = landscape && tab == 0

    Scaffold(
        topBar = {
            if (!immersive) TopAppBar(
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.modelName?.let { "VIOFO $it" } ?: "Camera", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            RecDot(state.recording)
                        }
                        Text(
                            listOfNotNull(
                                state.freeBytes?.let { "${Format.bytes(it)} free" } ?: if (!state.cardPresent) "No SD card" else null,
                                state.firmware,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { c.session.disconnect(); back() }) { Icon(Icons.Outlined.LinkOff, "Disconnect") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!immersive) NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(if (tab == 0) Icons.Rounded.Videocam else Icons.Outlined.Videocam, null) }, label = { Text("Live") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(if (tab == 1) Icons.Rounded.Folder else Icons.Outlined.Folder, null) }, label = { Text("Files") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(if (tab == 2) Icons.Rounded.Tune else Icons.Outlined.Tune, null) }, label = { Text("Settings") })
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(if (immersive) androidx.compose.foundation.layout.PaddingValues() else pad)) {
            when (tab) {
                0 -> LiveTab(fullscreen = immersive)
                1 -> FilesTab(openRemote = openRemote, openDownloads = openDownloads)
                else -> CameraSettingsTab()
            }
            ConnectionBanner(state.phase, state.message, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun ConnectionBanner(phase: Phase, message: String?, modifier: Modifier) {
    AnimatedVisibility(phase == Phase.Reconnecting || phase == Phase.Failed || phase == Phase.Handshake, modifier) {
        Surface(
            color = if (phase == Phase.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (phase != Phase.Failed) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
                Text(
                    when (phase) {
                        Phase.Failed -> "Disconnected: ${message ?: "connection lost"}"
                        Phase.Handshake -> "Connecting…"
                        else -> "Reconnecting to camera…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
