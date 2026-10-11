package io.github.helliott20.dashlink.ui.components

import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Low-latency live view. Buffers are kept tiny so what you see is what the camera sees
 * right now, and the stream self-heals: on any error we reconnect with backoff, flipping
 * to RTP-over-TCP after a UDP failure (some phones/firewalls drop the UDP stream).
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveStream(
    url: String,
    preferTcp: Boolean,
    modifier: Modifier = Modifier,
    onState: (playing: Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var attempt by remember(url) { mutableIntStateOf(0) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var buffering by remember(url) { mutableStateOf(true) }
    val useTcp = preferTcp || attempt % 2 == 1

    val player = remember(url, attempt) {
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 2_000, 100, 100)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context).setLoadControl(load).build().apply {
            val src = RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .setTimeoutMs(6_000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
            setMediaSource(src)
            playWhenReady = true
            volume = 0f
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) { error = null; onState(true) }
            }
            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName
                onState(false)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }

    // Reconnect loop.
    LaunchedEffect(error) {
        if (error != null) {
            delay((800L * (1 + attempt.coerceAtMost(6))))
            attempt++
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
        )
        if (buffering || error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (error != null) "Reconnecting stream${if (useTcp) " (TCP)" else ""}…" else "Connecting to live view…",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (attempt > 3) TextButton(onClick = { attempt++ }) { Text("Retry now") }
            }
        }
    }
}

/** Standard player with controls, for recorded clips (remote over HTTP or local). */
@OptIn(UnstableApi::class)
@Composable
fun ClipPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    onPosition: (positionMs: Long) -> Unit = {},
    seekTo: Long? = null,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(seekTo) { if (seekTo != null) player.seekTo(seekTo) }
    LaunchedEffect(player) {
        while (true) {
            onPosition(player.currentPosition)
            delay(250)
        }
    }
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        update = { it.player = player },
    )
}
