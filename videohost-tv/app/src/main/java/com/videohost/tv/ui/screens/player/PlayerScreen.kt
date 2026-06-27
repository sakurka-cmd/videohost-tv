package com.videohost.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.data.api.WatchProgressUpdate
import kotlinx.coroutines.delay
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

    // Build the player for a given video id
    fun buildPlayer(videoId: String): ExoPlayer {
        val baseUrl = kotlinx.coroutines.runBlocking { repo.getServerUrl() }
        val streamUrl = "$baseUrl/api/videos/$videoId/stream"
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true
        player.repeatMode = Player.REPEAT_MODE_OFF

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

        // When video ends, save final progress = 0 (so next time starts from beginning)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    scope.launch {
                        try {
                            val api = repo.getApi()
                            api.deleteProgress(videoId)
                        } catch (_: Exception) {}
                        // Auto-play next if available
                        val nextIdx = currentIndex + 1
                        if (nextIdx < target.allVideoIds.size) {
                            currentIndex = nextIdx
                            currentVideoId = target.allVideoIds[nextIdx]
                            currentPlayer?.release()
                            currentPlayer = buildPlayer(target.allVideoIds[nextIdx])
                        } else {
                            onClose()
                        }
                    }
                }
            }
        })

        return player
    }

    // Build initial player
    LaunchedEffect(Unit) {
        val p = buildPlayer(currentVideoId)
        currentPlayer = p
    }

    // D-pad key handler: BACK to close, LEFT/RIGHT seek, OK play/pause, UP/DOWN next/prev
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                val player = currentPlayer ?: return@onKeyEvent false
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
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
                        val prevIdx = currentIndex - 1
                        if (prevIdx >= 0) {
                            currentIndex = prevIdx
                            currentVideoId = target.allVideoIds[prevIdx]
                            currentPlayer?.release()
                            currentPlayer = buildPlayer(target.allVideoIds[prevIdx])
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        val nextIdx = currentIndex + 1
                        if (nextIdx < target.allVideoIds.size) {
                            currentIndex = nextIdx
                            currentVideoId = target.allVideoIds[nextIdx]
                            currentPlayer?.release()
                            currentPlayer = buildPlayer(target.allVideoIds[nextIdx])
                        }
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
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
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
