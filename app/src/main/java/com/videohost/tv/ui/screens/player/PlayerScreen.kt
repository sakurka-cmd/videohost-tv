package com.videohost.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.data.api.WatchProgressUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Available playback speeds: 0.5, 1.0, 1.25, 1.5, 1.75, 2.0
// (0.25 and 0.75 removed per user request, 1.75 added)
val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@Composable
fun PlayerScreen(
    repo: VideoHostRepository,
    target: PlaybackTarget,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var currentIndex by remember { mutableStateOf(target.allVideoIds.indexOf(target.videoId).coerceAtLeast(0)) }
    var currentVideoId by remember { mutableStateOf(target.videoId) }

    // Playback speed
    var speedIdx by remember { mutableStateOf(1) }  // default 1.0x
    var speedOverlayVisible by remember { mutableStateOf(false) }

    // Controller visibility (auto-hide after 5s of inactivity)
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }

    // Player state for UI
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // Read settings
    val autoplayNext = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        autoplayNext.value = repo.autoplayNextFlow.first()
        val savedSpeed = repo.getPlaybackSpeed(target.playlistId)
        val idx = PLAYBACK_SPEEDS.indexOfFirst { kotlin.math.abs(it - savedSpeed) < 0.01f }
        if (idx >= 0) speedIdx = idx
    }

    // Auto-hide controls
    LaunchedEffect(lastInteraction) {
        while (true) {
            delay(1000)
            if (System.currentTimeMillis() - lastInteraction > 5000 && isPlaying) {
                controlsVisible = false
                speedOverlayVisible = false
            }
        }
    }

    // Position updater
    LaunchedEffect(currentPlayer) {
        while (true) {
            delay(500)
            currentPlayer?.let { p ->
                positionMs = p.currentPosition
                durationMs = p.duration.takeIf { it > 0 } ?: 0L
                isPlaying = p.isPlaying
            }
        }
    }

    // Apply speed to player
    LaunchedEffect(speedIdx, currentPlayer) {
        currentPlayer?.let { p ->
            p.playbackParameters = p.playbackParameters.withSpeed(PLAYBACK_SPEEDS[speedIdx])
        }
    }

    fun buildPlayer(videoId: String): ExoPlayer {
        val baseUrl = kotlinx.coroutines.runBlocking { repo.getServerUrl() }
        val streamUrl = "$baseUrl/api/videos/$videoId/stream"
        val sessionCookie = kotlinx.coroutines.runBlocking { repo.getSessionCookie() }

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            okhttp3.OkHttpClient.Builder().build()
        ).apply {
            setDefaultRequestProperties(mapOf(
                "Cookie" to "vh_session=$sessionCookie",
                "User-Agent" to "UTube/1.3 (Android TV)",
            ))
        }
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            context, httpDataSourceFactory
        )

        val mediaItem = MediaItem.Builder().setUri(streamUrl).setMimeType("video/mp4").build()
        val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            dataSourceFactory
        ).createMediaSource(mediaItem)

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            )
            .build()
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        player.repeatMode = Player.REPEAT_MODE_OFF
        // Apply saved speed immediately
        player.playbackParameters = player.playbackParameters.withSpeed(PLAYBACK_SPEEDS[speedIdx])

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("UTube", "ExoPlayer error for $videoId", error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    scope.launch {
                        try { repo.getApi().deleteProgress(videoId) } catch (_: Exception) {}
                        if (autoplayNext.value) {
                            val nextIdx = currentIndex + 1
                            if (nextIdx < target.allVideoIds.size) {
                                currentIndex = nextIdx
                                currentVideoId = target.allVideoIds[nextIdx]
                                currentPlayer?.release()
                                currentPlayer = buildPlayer(target.allVideoIds[nextIdx])
                            } else { onClose() }
                        } else { player.playWhenReady = false }
                    }
                }
            }
        })

        // Resume from saved position
        scope.launch {
            try {
                val progress = repo.getApi().getProgress(videoId)
                if (progress.positionSec > 5f) player.seekTo((progress.positionSec * 1000).toLong())
            } catch (_: Exception) {}
        }

        // Periodic progress save
        scope.launch {
            while (true) {
                delay(5000)
                if (player.playbackState == Player.STATE_ENDED) break
                val pos = player.currentPosition / 1000f
                val dur = player.duration.takeIf { it > 0 }?.let { it / 1000f }
                try { repo.getApi().putProgress(videoId, WatchProgressUpdate(pos, dur)) } catch (_: Exception) {}
            }
        }

        return player
    }

    LaunchedEffect(Unit) { currentPlayer = buildPlayer(currentVideoId) }

    fun switchTo(idx: Int) {
        if (idx < 0 || idx >= target.allVideoIds.size) return
        currentIndex = idx
        currentVideoId = target.allVideoIds[idx]
        currentPlayer?.release()
        currentPlayer = buildPlayer(target.allVideoIds[idx])
    }

    fun togglePlayPause() {
        currentPlayer?.let { it.playWhenReady = !it.playWhenReady }
        lastInteraction = System.currentTimeMillis()
        controlsVisible = true
    }

    fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % PLAYBACK_SPEEDS.size
        scope.launch { repo.setPlaybackSpeed(target.playlistId, PLAYBACK_SPEEDS[speedIdx]) }
        speedOverlayVisible = true
        lastInteraction = System.currentTimeMillis()
    }

    fun seekBy(deltaSec: Int) {
        currentPlayer?.let { p ->
            val newPos = (p.currentPosition + deltaSec * 1000).coerceIn(0, p.duration.coerceAtLeast(0))
            p.seekTo(newPos)
        }
        lastInteraction = System.currentTimeMillis()
        controlsVisible = true
    }

    fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Click to toggle controls (mouse/touch)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                controlsVisible = !controlsVisible
                lastInteraction = System.currentTimeMillis()
            }
            // D-pad handler — intercepts BEFORE PlayerView
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                lastInteraction = System.currentTimeMillis()
                controlsVisible = true
                when (event.key) {
                    Key.DirectionLeft -> { seekBy(-10); true }
                    Key.DirectionRight -> { seekBy(10); true }
                    Key.DirectionCenter, Key.Enter -> { togglePlayPause(); true }
                    Key.DirectionUp -> { switchTo(currentIndex - 1); true }
                    Key.DirectionDown -> { switchTo(currentIndex + 1); true }
                    Key.Menu -> { cycleSpeed(); true }
                    Key.Back -> {
                        scope.launch {
                            try {
                                val api = repo.getApi()
                                val pos = currentPlayer?.currentPosition?.div(1000f) ?: 0f
                                val dur = currentPlayer?.duration?.takeIf { it > 0 }?.div(1000f)
                                api.putProgress(currentVideoId, WatchProgressUpdate(pos, dur))
                            } catch (_: Exception) {}
                            currentPlayer?.release()
                            onClose()
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        // PlayerView — NO controller (we handle everything in Compose)
        currentPlayer?.let { player ->
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Custom controls overlay (visible when controlsVisible)
        if (controlsVisible) {
            // Bottom bar: seek bar + time + speed
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${fmt(positionMs)}", color = Color.White, fontSize = 12.sp)
                    Text(
                        if (isPlaying) "⏸" else "▶",
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { togglePlayPause() }
                    )
                    Text(
                        "${PLAYBACK_SPEEDS[speedIdx]}x",
                        color = if (speedIdx > 1) Color(0xFFEF4444) else Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { cycleSpeed() }
                    )
                    Text("${fmt(durationMs)}", color = Color.White, fontSize = 12.sp)
                }
                // Seek bar
                if (durationMs > 0) {
                    LinearProgressIndicator(
                        progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .padding(top = 4.dp),
                        color = Color(0xFFEF4444),
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        }

        // Speed overlay (briefly shown when speed changes)
        if (speedOverlayVisible) {
            LaunchedEffect(speedOverlayVisible) {
                delay(2000)
                speedOverlayVisible = false
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    "Скорость: ${PLAYBACK_SPEEDS[speedIdx]}x",
                    color = Color.White,
                    fontSize = 18.sp,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            currentPlayer?.release()
            currentPlayer = null
        }
    }
}
