package com.videohost.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videohost.tv.data.api.MarkUpdateRequest
import com.videohost.tv.data.api.WatchProgressUpdate
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone Activity for video playback — bypasses Compose entirely.
 *
 * Why: Compose's AndroidView wrapper doesn't render video on Allwinner H6
 * (VONTAR H1) — black screen, only audio. A native Activity with PlayerView
 * in XML layout works on ALL devices.
 *
 * Launched from HomeScreen via Intent. Parameters passed as extras:
 *   - playlistId: String?
 *   - videoId: String
 *   - allVideoIds: ArrayList<String>
 *   - allVideoTitles: ArrayList<String>
 *
 * D-pad controls (same as old Compose PlayerScreen):
 *   OK          — play/pause
 *   ←/→ tap     — seek ±10s
 *   ←/→ hold    — continuous seek (10→20→30s acceleration)
 *   ↑/↓         — prev/next video
 *   Menu        — cycle playback speed (0.5/0.75/1/1.25/1.5/1.75/2x)
 *   ChannelUp   — toggle watched mark
 *   ChannelDown — toggle favorite mark
 *   Back        — save progress + close
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlistId"
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_ALL_IDS = "allVideoIds"
        const val EXTRA_ALL_TITLES = "allVideoTitles"
        // Also accept X4 OK button HID code
        private const val X4_OK_KEYCODE = 94489280512L
    }

    private lateinit var repo: VideoHostRepository
    private lateinit var playerView: PlayerView
    private lateinit var controlsBar: View
    private lateinit var titleText: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var playPauseText: TextView
    private lateinit var speedText: TextView
    private lateinit var speedOverlay: TextView

    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var playlistId: String? = null
    private var allVideoIds: List<String> = emptyList()
    private var allVideoTitles: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var currentVideoId: String = ""
    private var currentTitle: String = ""

    private var controlsVisible = true
    private var lastInteraction = 0L
    private var speedIdx = 1  // default 1.0x

    // Seek hold state
    private var seekDirection = 0
    private var seekDeltaSec = 10
    private var seekHoldJob: Job? = null
    private var seekHoldStart = 0L

    private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    // ── Lifecycle ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i("PlayerActivity", "onCreate")
        setContentView(R.layout.player_activity)

        repo = VideoHostRepository(applicationContext)

        // Bind views
        playerView = findViewById(R.id.player_view)
        controlsBar = findViewById(R.id.controls_bar)
        titleText = findViewById(R.id.title_text)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        playPauseText = findViewById(R.id.play_pause_text)
        speedText = findViewById(R.id.speed_text)
        speedOverlay = findViewById(R.id.speed_overlay)

        // Read intent extras
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        currentVideoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        allVideoIds = intent.getStringArrayListExtra(EXTRA_ALL_IDS) ?: emptyList()
        allVideoTitles = intent.getStringArrayListExtra(EXTRA_ALL_TITLES) ?: emptyList()
        currentIndex = allVideoIds.indexOf(currentVideoId).coerceAtLeast(0)
        currentTitle = allVideoTitles.getOrNull(currentIndex) ?: ""

        AppLogger.i("PlayerActivity", "playlistId=$playlistId videoId=$currentVideoId idx=$currentIndex/${allVideoIds.size}")

        // Load saved speed
        scope.launch {
            val saved = playlistId?.let { repo.getPlaybackSpeed(it) } ?: 1.0f
            val idx = PLAYBACK_SPEEDS.indexOfFirst { kotlin.math.abs(it - saved) < 0.01f }
            if (idx >= 0) speedIdx = idx
        }

        // Build and start player (async — never block main thread)
        scope.launch { buildPlayer(currentVideoId) }

        // Auto-hide controls after 5s
        lastInteraction = System.currentTimeMillis()
        handler.postDelayed(::autoHideControls, 1000)
    }

    override fun onDestroy() {
        AppLogger.i("PlayerActivity", "onDestroy")
        saveProgressAndRelease()
        seekHoldJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── Player setup ───────────────────────────────────────────

    private suspend fun buildPlayer(videoId: String) {
        player?.release()
        // Read DataStore async — NEVER use runBlocking on main thread
        val baseUrl = repo.serverUrlFlow.first()
        val session = repo.sessionCookieFlow.first()
        val streamUrl = if (session.isNotEmpty()) {
            "$baseUrl/api/videos/$videoId/stream?session=$session"
        } else {
            "$baseUrl/api/videos/$videoId/stream"
        }
        AppLogger.i("PlayerActivity", "buildPlayer: $streamUrl")

        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
            playbackParameters = playbackParameters.withSpeed(PLAYBACK_SPEEDS[speedIdx])
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        scope.launch {
                            try { repo.getApi().deleteProgress(videoId) } catch (_: Exception) {}
                        }
                        switchTo(currentIndex + 1)
                    }
                }
            })
        }
        playerView.player = player

        // Seek to saved position
        try {
            val p = repo.getApi().getProgress(videoId)
            if (p.positionSec > 5f) {
                player?.seekTo((p.positionSec * 1000).toLong())
            }
        } catch (_: Exception) {}

        // Progress sync loop
        scope.launch {
            while (isActive) {
                delay(5000)
                player?.let { p ->
                    if (p.playbackState != Player.STATE_ENDED) {
                        try {
                            val pos = p.currentPosition / 1000f
                            val dur = p.duration.takeIf { it > 0 }?.let { it / 1000f }
                            repo.getApi().putProgress(videoId, WatchProgressUpdate(pos, dur))
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // UI update loop
        scope.launch {
            while (isActive) {
                delay(500)
                player?.let { p ->
                    val pos = p.currentPosition
                    val dur = p.duration.takeIf { it > 0 } ?: 0L
                    positionText.text = fmt(pos)
                    durationText.text = fmt(dur)
                    playPauseText.text = if (p.playWhenReady && p.isPlaying) "⏸" else "▶"
                }
            }
        }
    }

    private fun saveProgressAndRelease() {
        player?.let { p ->
            val pos = p.currentPosition / 1000f
            val dur = p.duration.takeIf { it > 0 }?.let { it / 1000f }
            scope.launch {
                try { repo.getApi().putProgress(currentVideoId, WatchProgressUpdate(pos, dur)) } catch (_: Exception) {}
            }
            p.release()
        }
        player = null
    }

    // ── Video switching ────────────────────────────────────────

    private fun switchTo(idx: Int) {
        cancelSeekHold()
        if (idx < 0 || idx >= allVideoIds.size) return
        currentIndex = idx
        currentVideoId = allVideoIds[idx]
        currentTitle = allVideoTitles.getOrNull(idx) ?: ""
        titleText.text = currentTitle
        scope.launch { buildPlayer(currentVideoId) }
    }

    // ── Speed ──────────────────────────────────────────────────

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % PLAYBACK_SPEEDS.size
        player?.playbackParameters = player!!.playbackParameters.withSpeed(PLAYBACK_SPEEDS[speedIdx])
        scope.launch { playlistId?.let { repo.setPlaybackSpeed(it, PLAYBACK_SPEEDS[speedIdx]) } }
        speedText.text = "${PLAYBACK_SPEEDS[speedIdx]}x"
        showSpeedOverlay()
        AppLogger.i("PlayerActivity", "speed cycled to ${PLAYBACK_SPEEDS[speedIdx]}x")
    }

    private fun showSpeedOverlay() {
        speedOverlay.text = "Speed: ${PLAYBACK_SPEEDS[speedIdx]}x"
        speedOverlay.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages("speed_overlay")
        handler.postDelayed({ speedOverlay.visibility = View.GONE }, 2000)
    }

    // ── Marks ──────────────────────────────────────────────────

    private enum class MarkField { WATCHED, FAVORITE }

    private fun toggleMark(field: MarkField) {
        scope.launch {
            try {
                val api = repo.getApi()
                val marks = api.getMarks(currentVideoId)
                when (field) {
                    MarkField.WATCHED -> {
                        val newVal = !marks.myWatched
                        api.putMarks(currentVideoId, MarkUpdateRequest(watched = newVal))
                        AppLogger.i("PlayerActivity", "toggle watched → $newVal")
                    }
                    MarkField.FAVORITE -> {
                        val newVal = !marks.myFavorite
                        api.putMarks(currentVideoId, MarkUpdateRequest(favorite = newVal))
                        AppLogger.i("PlayerActivity", "toggle favorite → $newVal")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("PlayerActivity", "toggleMark failed", e)
            }
        }
    }

    // ── Seek hold ──────────────────────────────────────────────

    private fun startSeekHold(dir: Int) {
        seekDirection = dir
        seekDeltaSec = 10
        seekHoldStart = System.currentTimeMillis()
        seekHoldJob?.cancel()
        seekHoldJob = scope.launch {
            while (isActive && seekDirection != 0) {
                player?.let { p ->
                    val newPos = (p.currentPosition + seekDeltaSec * 1000L * seekDirection)
                        .coerceIn(0, p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
                    p.seekTo(newPos)
                }
                delay(200)
                // Accelerate after 2s
                if (System.currentTimeMillis() - seekHoldStart > 2000) {
                    seekDeltaSec = (seekDeltaSec + 10).coerceAtMost(30)
                }
            }
        }
    }

    private fun endSeekHold() {
        seekDirection = 0
        seekHoldJob?.cancel()
        seekHoldJob = null
    }

    private fun cancelSeekHold() {
        endSeekHold()
    }

    // ── Controls visibility ────────────────────────────────────

    private fun showControls() {
        controlsVisible = true
        controlsBar.visibility = View.VISIBLE
        lastInteraction = System.currentTimeMillis()
    }

    private fun autoHideControls() {
        if (System.currentTimeMillis() - lastInteraction > 5000 && player?.playWhenReady == true) {
            controlsVisible = false
            controlsBar.visibility = View.GONE
        }
        handler.postDelayed(::autoHideControls, 1000)
    }

    // ── Key handling ───────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, null)
        showControls()

        // D-pad LEFT/RIGHT: seek (handle on KeyDown for both tap and hold)
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (event.repeatCount == 0) {
                // First press = single tap seek ±10s
                player?.let { p ->
                    val newPos = (p.currentPosition - 10000).coerceAtLeast(0)
                    p.seekTo(newPos)
                }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.repeatCount == 0) {
                player?.let { p ->
                    val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    val newPos = (p.currentPosition + 10000).coerceAtMost(dur)
                    p.seekTo(newPos)
                }
            }
            return true
        }
        // D-pad UP/DOWN: prev/next video (on KeyDown)
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            cancelSeekHold()
            switchTo(currentIndex - 1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            cancelSeekHold()
            switchTo(currentIndex + 1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyUp(keyCode, null)
        showControls()

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                cancelSeekHold()
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                cycleSpeed()
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                toggleMark(MarkField.WATCHED)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                toggleMark(MarkField.FAVORITE)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                cancelSeekHold()
                finish()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ── Utils ──────────────────────────────────────────────────

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
        else "$m:${sec.toString().padStart(2, '0')}"
    }
}
