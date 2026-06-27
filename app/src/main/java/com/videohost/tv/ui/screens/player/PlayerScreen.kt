package com.videohost.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    // Available playback speeds: 0.5, 1.0, 1.25, 1.5, 1.75, 2.0
    // (0.25 and 0.75 removed per user request, 1.75 added)
    val speeds = remember { floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f) }
    var speedIdx by remember { mutableStateOf(1) }  // default 1.0x
    var showSpeedMenu by remember { mutableStateOf(false) }
    var speedMenuDismissAt by remember { mutableStateOf(0L) }

    // Read autoplay setting (default true)
    val autoplayNext = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        autoplayNext.value = repo.autoplayNextFlow.first()
        // Load saved playback speed for this playlist
        val savedSpeed = repo.getPlaybackSpeed(target.playlistId)
        val idx = speeds.indexOfFirst { kotlin.math.abs(it - savedSpeed) < 0.01f }
        if (idx >= 0) speedIdx = idx
    }

    // Apply speed to player whenever it changes
    LaunchedEffect(speedIdx, currentPlayer) {
        currentPlayer?.playbackParameters = currentPlayer!!.playbackParameters.withSpeed(speeds[speedIdx])
    }

    // Build the player for a given video id
    fun buildPlayer(videoId: String): ExoPlayer {
        val baseUrl = kotlinx.coroutines.runBlocking { repo.getServerUrl() }
        val streamUrl = "$baseUrl/api/videos/$videoId/stream"
        val sessionCookie = kotlinx.coroutines.runBlocking { repo.getSessionCookie() }

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            okhttp3.OkHttpClient.Builder().build()
        ).apply {
            setDefaultRequestProperties(mapOf(
                "Cookie" to "vh_session=$sessionCookie",
                "User-Agent" to "VideoHostTV/1.0 (Android TV)",
            ))
        }
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            context, httpDataSourceFactory
        )

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType("video/mp4")
            .build()

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

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("VideoHostTV", "ExoPlayer error for $videoId", error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    scope.launch {
                        try {
                            val api = repo.getApi()
                            api.deleteProgress(videoId)
                        } catch (_: Exception) {}
                        // Auto-play next if autoplay is enabled and there's a next video
                        if (autoplayNext.value) {
                            val nextIdx = currentIndex + 1
                            if (nextIdx < target.allVideoIds.size) {
                                currentIndex = nextIdx
                                currentVideoId = target.allVideoIds[nextIdx]
                                currentPlayer?.release()
                                currentPlayer = buildPlayer(target.allVideoIds[nextIdx])
                            } else {
                                onClose()
                            }
                        } else {
                            // Autoplay disabled — just stop, stay on current video
                            player.playWhenReady = false
                        }
                    }
                }
            }
        })

        // Resume from saved position
        scope.launch {
            try {
                val api = repo.getApi()
                val progress = api.getProgress(videoId)
                if (progress.positionSec > 5f) {
                    player.seekTo((progress.positionSec * 1000).toLong())
                }
            } catch (_: Exception) {}
        }

        // Periodic save of progress (every 5s)
        scope.launch {
            while (true) {
                delay(5000)
                if (!player.isPlaying && player.playbackState == Player.STATE_ENDED) break
                val pos = player.currentPosition / 1000f
                val dur = player.duration.takeIf { it > 0 }?.let { it / 1000f }
                try {
                    val api = repo.getApi()
                    api.putProgress(videoId, WatchProgressUpdate(pos, dur))
                } catch (_: Exception) {}
            }
        }

        return player
    }

    // Build initial player
    LaunchedEffect(Unit) {
        val p = buildPlayer(currentVideoId)
        currentPlayer = p
    }

    // Helper: switch to a different video by index
    fun switchTo(idx: Int) {
        if (idx < 0 || idx >= target.allVideoIds.size) return
        currentIndex = idx
        currentVideoId = target.allVideoIds[idx]
        currentPlayer?.release()
        currentPlayer = buildPlayer(target.allVideoIds[idx])
    }

    // D-pad key handler using onPreviewKeyEvent — intercepts events BEFORE
    // PlayerView (native Android View inside AndroidView) can consume them.
    // This is critical: PlayerView captures D-pad events by default, preventing
    // Compose's onKeyEvent from ever seeing them.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val player = currentPlayer ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        player.seekBack()
                        true
                    }
                    Key.DirectionRight -> {
                        player.seekForward()
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        player.playWhenReady = !player.playWhenReady
                        true
                    }
                    Key.DirectionUp -> {
                        switchTo(currentIndex - 1)
                        true
                    }
                    Key.DirectionDown -> {
                        switchTo(currentIndex + 1)
                        true
                    }
                    Key.Menu -> {
                        // Cycle to next speed
                        speedIdx = (speedIdx + 1) % speeds.size
                        // Persist for this playlist
                        scope.launch {
                            repo.setPlaybackSpeed(target.playlistId, speeds[speedIdx])
                        }
                        // Show speed indicator briefly
                        showSpeedMenu = true
                        speedMenuDismissAt = System.currentTimeMillis() + 2000
                        true
                    }
                    Key.Back -> {
                        scope.launch {
                            try {
                                val api = repo.getApi()
                                val pos = player.currentPosition / 1000f
                                val dur = player.duration.takeIf { it > 0 }?.let { it / 1000f }
                                api.putProgress(currentVideoId, WatchProgressUpdate(pos, dur))
                            } catch (_: Exception) {}
                            player.release()
                            onClose()
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
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

        // Speed indicator overlay (shows briefly when speed changes via Menu key)
        if (showSpeedMenu) {
            // Auto-dismiss after 2 seconds
            LaunchedEffect(speedMenuDismissAt) {
                if (speedMenuDismissAt > 0) {
                    kotlinx.coroutines.delay(2000)
                    showSpeedMenu = false
                }
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(16.dp)
                    .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "Скорость: ${speeds[speedIdx]}x",
                    color = Color.White,
                    fontSize = 16.sp,
                )
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            currentPlayer?.release()
            currentPlayer = null
        }
    }
}
