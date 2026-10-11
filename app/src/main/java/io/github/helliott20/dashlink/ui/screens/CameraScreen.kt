package io.github.helliott20.dashlink.ui.screens

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.helliott20.dashlink.api.CamFile
import io.github.helliott20.dashlink.session.Phase
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.components.RecDot
import io.github.helliott20.dashlink.ui.components.SessionSnackbars
import io.github.helliott20.dashlink.ui.layoutInfo
import io.github.helliott20.dashlink.util.Format

private enum class CamTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Live("Live", Icons.Outlined.Videocam, Icons.Rounded.Videocam),
    Files("Files", Icons.Outlined.Folder, Icons.Rounded.Folder),
    Settings("Settings", Icons.Outlined.Tune, Icons.Rounded.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(back: () -> Unit, openRemote: (CamFile) -> Unit, openDownloads: () -> Unit, initialTab: Int = 0, initialFile: String? = null) {
    val c = LocalContainer.current
    val state by c.session.state.collectAsStateWithLifecycle()
    val layout = layoutInfo()
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab) }
    val tab = CamTab.entries[tabIndex]
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    SessionSnackbars(snack)

    // Phones go full screen when turned sideways; larger windows only when asked.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val immersive = tab == CamTab.Live && (fullscreen || (!layout.wide && landscape))
    // Folding or unfolding mid-full-screen: start the new screen in its normal layout.
    var lastWide by remember { mutableStateOf(layout.wide) }
    LaunchedEffect(layout.wide) {
        if (lastWide != layout.wide) fullscreen = false
        lastWide = layout.wide
    }
    SystemBarsHidden(immersive)

    NavigationSuiteScaffold(
        layoutType = when {
            immersive -> NavigationSuiteType.None
            layout.wide -> NavigationSuiteType.NavigationRail
            else -> NavigationSuiteType.NavigationBar
        },
        navigationSuiteItems = {
            CamTab.entries.forEach { t ->
                item(
                    selected = tab == t,
                    onClick = { tabIndex = t.ordinal },
                    icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                    label = { Text(t.label) },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (!immersive) TopAppBar(
                    navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(state.modelName?.let { "VIOFO $it" } ?: "Camera", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(10.dp))
                                RecDot(state.recording)
                            }
                            Text(
                                when {
                                    !state.cardPresent -> "No SD card"
                                    state.freeBytes != null -> "${Format.bytes(state.freeBytes!!)} free on SD card"
                                    else -> state.ssid.orEmpty()
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        IconButton(onClick = { c.session.disconnect(); back() }) { Icon(Icons.Outlined.LinkOff, "Disconnect") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snack) },
            // The bar/rail owns its own edge; the content takes the rest.
            contentWindowInsets = when {
                immersive -> WindowInsets(0)
                layout.wide -> WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom)
                else -> WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(if (immersive) PaddingValues() else pad)) {
                when (tab) {
                    CamTab.Live -> LiveTab(
                        fullscreen = immersive,
                        onFullscreen = { fullscreen = it },
                        openSettings = { tabIndex = CamTab.Settings.ordinal },
                        openDownloads = openDownloads,
                    )
                    CamTab.Files -> FilesTab(openRemote = openRemote, openDownloads = openDownloads, initialDetail = initialFile)
                    CamTab.Settings -> CameraSettingsTab()
                }
                ConnectionBanner(state.phase, state.message, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

/** Hides status and navigation bars while true (swipe from an edge to peek). */
@Composable
private fun SystemBarsHidden(hidden: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(hidden) {
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (hidden) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl.show(WindowInsetsCompat.Type.systemBars()) }
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
                        Phase.Failed -> "Disconnected: ${message ?: "connection lost"}. Go back to reconnect."
                        Phase.Handshake -> "Connecting…"
                        else -> "Reconnecting to the camera…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
