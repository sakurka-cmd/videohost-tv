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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import com.videohost.tv.data.api.MarkUpdateRequest
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.data.api.WatchProgressUpdate
import com.videohost.tv.data.model.VideoMark
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

enum class MarkField { WATCHED, FAVORITE }

@Composable
fun PlayerScreen(repo: VideoHostRepository, target: PlaybackTarget, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var currentIndex by remember { mutableStateOf(target.allVideoIds.indexOf(target.videoId).coerceAtLeast(0)) }
    var currentVideoId by remember { mutableStateOf(target.videoId) }
    var speedIdx by remember { mutableStateOf(1) }
    var speedOverlay by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableStateOf(System.currentTimeMillis()) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var watched by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(false) }
    val autoplayNext = remember { mutableStateOf(true) }

    // Load current video's marks whenever currentVideoId changes
    LaunchedEffect(currentVideoId) {
        try {
            val mark = repo.getApi().getMarks(currentVideoId)
            watched = mark.watched
            favorite = mark.favorite
        } catch (_: Exception) {
            watched = false
            favorite = false
        }
    }

    fun toggleMark(field: MarkField) {
        val newValue = when (field) {
            MarkField.WATCHED -> !watched
            MarkField.FAVORITE -> !favorite
        }
        // Optimistic update
        when (field) {
            MarkField.WATCHED -> watched = newValue
            MarkField.FAVORITE -> favorite = newValue
        }
        scope.launch {
            try {
                val api = repo.getApi()
                val updated = api.putMarks(currentVideoId, MarkUpdateRequest(
                    watched = if (field == MarkField.WATCHED) newValue else null,
                    favorite = if (field == MarkField.FAVORITE) newValue else null,
                ))
                watched = updated.watched
                favorite = updated.favorite
            } catch (_: Exception) {
                // Revert on error
                when (field) {
                    MarkField.WATCHED -> watched = !newValue
                    MarkField.FAVORITE -> favorite = !newValue
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        autoplayNext.value = repo.autoplayNextFlow.first()
        val saved = repo.getPlaybackSpeed(target.playlistId)
        val idx = PLAYBACK_SPEEDS.indexOfFirst { kotlin.math.abs(it - saved) < 0.01f }
        if (idx >= 0) speedIdx = idx
    }
    LaunchedEffect(lastInteraction) {
        while (true) { delay(1000); if (System.currentTimeMillis() - lastInteraction > 5000 && isPlaying) { controlsVisible = false; speedOverlay = false } }
    }
    LaunchedEffect(currentPlayer) { while (true) { delay(500); currentPlayer?.let { positionMs = it.currentPosition; durationMs = it.duration.takeIf { d -> d > 0 } ?: 0L; isPlaying = it.isPlaying } } }
    LaunchedEffect(speedIdx, currentPlayer) { currentPlayer?.let { it.playbackParameters = it.playbackParameters.withSpeed(PLAYBACK_SPEEDS[speedIdx]) } }

    fun buildPlayer(videoId: String): ExoPlayer {
        val baseUrl = kotlinx.coroutines.runBlocking { repo.getServerUrl() }
        val sessionCookie = kotlinx.coroutines.runBlocking { repo.getSessionCookie() }
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttp3.OkHttpClient.Builder().build()).apply {
            setDefaultRequestProperties(mapOf("Cookie" to "vh_session=$sessionCookie", "User-Agent" to "UTube/1.5"))
        }
        val dsFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        val mediaItem = MediaItem.Builder().setUri("$baseUrl/api/videos/$videoId/stream").setMimeType("video/mp4").build()
        val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory).createMediaSource(mediaItem)
        val player = ExoPlayer.Builder(context).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory)).build()
        player.setMediaSource(mediaSource); player.prepare(); player.playWhenReady = true
        player.playbackParameters = player.playbackParameters.withSpeed(PLAYBACK_SPEEDS[speedIdx])
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { android.util.Log.e("UTube", "Player error: $videoId", e) }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { scope.launch {
                    try { repo.getApi().deleteProgress(videoId) } catch (_: Exception) {}
                    if (autoplayNext.value) { val n = currentIndex + 1; if (n < target.allVideoIds.size) { currentIndex = n; currentVideoId = target.allVideoIds[n]; currentPlayer?.release(); currentPlayer = buildPlayer(target.allVideoIds[n]) } else onClose() } else player.playWhenReady = false
                } }
            }
        })
        scope.launch { try { val p = repo.getApi().getProgress(videoId); if (p.positionSec > 5f) player.seekTo((p.positionSec * 1000).toLong()) } catch (_: Exception) {} }
        scope.launch { while (true) { delay(5000); if (player.playbackState == Player.STATE_ENDED) break; try { repo.getApi().putProgress(videoId, WatchProgressUpdate(player.currentPosition / 1000f, player.duration.takeIf { it > 0 }?.let { it / 1000f })) } catch (_: Exception) {} } }
        return player
    }
    LaunchedEffect(Unit) { currentPlayer = buildPlayer(currentVideoId) }
    fun switchTo(idx: Int) { if (idx < 0 || idx >= target.allVideoIds.size) return; currentIndex = idx; currentVideoId = target.allVideoIds[idx]; currentPlayer?.release(); currentPlayer = buildPlayer(target.allVideoIds[idx]) }
    fun fmt(ms: Long): String { if (ms <= 0) return "0:00"; val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    Box(Modifier.fillMaxSize().background(Color.Black)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controlsVisible = !controlsVisible; lastInteraction = System.currentTimeMillis() }
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            lastInteraction = System.currentTimeMillis(); controlsVisible = true
            when (e.key) {
                Key.DirectionLeft -> { currentPlayer?.seekTo((currentPlayer!!.currentPosition - 10000).coerceAtLeast(0)); true }
                Key.DirectionRight -> { currentPlayer?.seekTo((currentPlayer!!.currentPosition + 10000).coerceAtMost(currentPlayer!!.duration.coerceAtLeast(0))); true }
                Key.DirectionCenter, Key.Enter -> { currentPlayer?.let { it.playWhenReady = !it.playWhenReady }; true }
                Key.DirectionUp -> { switchTo(currentIndex - 1); true }
                Key.DirectionDown -> { switchTo(currentIndex + 1); true }
                Key.Menu -> { speedIdx = (speedIdx + 1) % PLAYBACK_SPEEDS.size; scope.launch { repo.setPlaybackSpeed(target.playlistId, PLAYBACK_SPEEDS[speedIdx]) }; speedOverlay = true; true }
                Key.ChannelUp -> { toggleMark(MarkField.WATCHED); true }
                Key.ChannelDown -> { toggleMark(MarkField.FAVORITE); true }
                Key.Back -> { scope.launch { try { val api = repo.getApi(); val pos = currentPlayer?.currentPosition?.div(1000f) ?: 0f; val dur = currentPlayer?.duration?.takeIf { it > 0 }?.div(1000f); api.putProgress(currentVideoId, WatchProgressUpdate(pos, dur)) } catch (_: Exception) {}; currentPlayer?.release(); onClose() }; true }
                else -> false
            }
        }
    ) {
        currentPlayer?.let { player ->
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                update = { playerView -> playerView.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (controlsVisible) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(text = "${fmt(positionMs)}", color = Color.White, fontSize = 12.sp)
                    Text(text = if (isPlaying) "\u23F8" else "\u25B6", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { currentPlayer?.let { it.playWhenReady = !it.playWhenReady } })
                    Text(text = "${PLAYBACK_SPEEDS[speedIdx]}x", color = if (speedIdx > 1) Color(0xFFEF4444) else Color.White, fontSize = 12.sp, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { speedIdx = (speedIdx + 1) % PLAYBACK_SPEEDS.size; scope.launch { repo.setPlaybackSpeed(target.playlistId, PLAYBACK_SPEEDS[speedIdx]) }; speedOverlay = true })
                    // Mark toggle buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (watched) "✓ Просм." else "✓",
                            color = if (watched) Color(0xFF10B981) else Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { toggleMark(MarkField.WATCHED) },
                        )
                        Text(
                            text = if (favorite) "★ Избр." else "★",
                            color = if (favorite) Color(0xFFF59E0B) else Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { toggleMark(MarkField.FAVORITE) },
                        )
                    }
                    Text(text = "${fmt(durationMs)}", color = Color.White, fontSize = 12.sp)
                }
                if (durationMs > 0) { LinearProgressIndicator(progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 4.dp), color = Color(0xFFEF4444), trackColor = Color.White.copy(alpha = 0.3f)) }
                Text("CH+ ↑↓: метки • Menu: скорость", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (speedOverlay) {
            LaunchedEffect(speedOverlay) { delay(2000); speedOverlay = false }
            Box(Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(text = "Speed: ${PLAYBACK_SPEEDS[speedIdx]}x", color = Color.White, fontSize = 18.sp)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { currentPlayer?.release(); currentPlayer = null } }
}
