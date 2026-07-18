package com.videohost.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.videohost.tv.R
import com.videohost.tv.data.api.MarkUpdateRequest
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.data.model.AllMarksEntry
import com.videohost.tv.data.model.PlaylistFull
import com.videohost.tv.data.model.PlaylistGroup
import com.videohost.tv.data.model.VideoItem
import com.videohost.tv.logging.AppLogger
import com.videohost.tv.ui.components.VideoContextMenu
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repo: VideoHostRepository,
    onPlayVideo: (playlistId: String?, videoId: String, allIds: List<String>, allTitles: List<String>) -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<PlaylistFull>>(emptyList()) }
    var allVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var marksMap by remember { mutableStateOf<Map<String, AllMarksEntry>>(emptyMap()) }
    var playlistGroups by remember { mutableStateOf<List<PlaylistGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sortDesc by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Long-press context menu state
    var contextMenuVideo by remember { mutableStateOf<VideoItem?>(null) }

    suspend fun reloadMarks() {
        try {
            val api = repo.getApi()
            val resp = api.getAllMarks()
            marksMap = resp.marks.associateBy { mark -> mark.videoId }
        } catch (e: Exception) {
            AppLogger.w("HomeScreen", "reloadMarks failed: ${e.message}")
        }
    }

    LaunchedEffect(Unit) {
        AppLogger.i("HomeScreen", "LaunchedEffect init")
        try {
            baseUrl = repo.serverUrlFlow.first()
            val api = repo.getApi()
            val me = api.me()
            if (!me.isApproved) {
                error = "Аккаунт не одобрен администратором"
                loading = false
                return@LaunchedEffect
            }
            val pls = api.listPlaylists()
            val vids = api.listVideos()
            playlists = pls
            allVideos = vids
            AppLogger.i("HomeScreen", "loaded ${pls.size} playlists, ${vids.size} videos")
            // Load groups (best-effort — endpoint may not exist on older backends)
            try {
                playlistGroups = api.listPlaylistGroups()
            } catch (e: Exception) {
                AppLogger.w("HomeScreen", "listPlaylistGroups failed: ${e.message}")
                playlistGroups = emptyList()
            }
            // Build "Continue watching" — for each video, fetch its progress; keep those with > 5s
            val cont = mutableListOf<VideoItem>()
            for (v in vids) {
                try {
                    val p = api.getProgress(v.id)
                    if (p.positionSec > 5f) cont.add(v)
                } catch (_: Exception) {}
            }
            continueWatching = cont
            reloadMarks()
            loading = false
            AppLogger.i("HomeScreen", "init done, loading=false")
        } catch (e: Exception) {
            AppLogger.e("HomeScreen", "init failed", e)
            error = "Не удалось загрузить данные: ${e.message ?: "неизвестная ошибка"}"
            loading = false
        }
    }

    // Auto-refresh every 30 seconds
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30000)
            try {
                val api = repo.getApi()
                playlists = api.listPlaylists()
                allVideos = api.listVideos()
                try { playlistGroups = api.listPlaylistGroups() } catch (_: Exception) {}
                reloadMarks()
            } catch (_: Exception) {}
        }
    }

    // Long-press menu handlers
    val onToggleWatched: () -> Unit = {
        val v = contextMenuVideo
        if (v != null) {
            scope.launch {
                try {
                    val api = repo.getApi()
                    val current = marksMap[v.id]?.myWatched == true
                    api.putMarks(v.id, MarkUpdateRequest(watched = !current))
                    reloadMarks()
                    AppLogger.i("HomeScreen", "toggle watched for ${v.id} → ${!current}")
                } catch (e: Exception) {
                    AppLogger.e("HomeScreen", "toggle watched failed", e)
                }
            }
        }
    }
    val onToggleFavorite: () -> Unit = {
        val v = contextMenuVideo
        if (v != null) {
            scope.launch {
                try {
                    val api = repo.getApi()
                    val current = marksMap[v.id]?.myFavorite == true
                    api.putMarks(v.id, MarkUpdateRequest(favorite = !current))
                    reloadMarks()
                    AppLogger.i("HomeScreen", "toggle favorite for ${v.id} → ${!current}")
                } catch (e: Exception) {
                    AppLogger.e("HomeScreen", "toggle favorite failed", e)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F10))
            .onPreviewKeyEvent { e ->
                // DEBUG: log all key events to file logger (so user can send logs from Settings)
                // Log EVERY event (Down, Up, multiple) so we can see exact key codes from any remote
                AppLogger.i("KeyEvent", "key=${e.key} keyCode=${e.key.keyCode} type=${e.type}")
                false  // don't intercept — let normal focus traversal handle it
            },
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFFEF4444),
                )
            }
            error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error!!, color = Color.White, fontSize = 18.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            kotlinx.coroutines.MainScope().launch {
                                repo.clearSession()
                                onLogout()
                            }
                        },
                    ) {
                        Text("Сменить аккаунт")
                    }
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AsyncImage(model = R.mipmap.ic_launcher, contentDescription = "UTube", modifier = Modifier.size(28.dp))
                            Text(
                                "UTube",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Button(
                            onClick = onOpenSettings,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1F1F23),
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Настройки")
                        }
                        Button(
                            onClick = { sortDesc = !sortDesc },
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 100.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1F1F23),
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(if (sortDesc) "↓ Новые" else "↑ Старые", fontSize = 13.sp)
                        }
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        if (continueWatching.isNotEmpty()) {
                            item {
                                VideoRow(
                                    title = "Продолжить просмотр",
                                    videos = continueWatching,
                                    baseUrl = baseUrl,
                                    marksMap = marksMap,
                                    onItemClick = { v ->
                                        onPlayVideo(null, v.id, continueWatching.map { it.id }, continueWatching.map { it.title })
                                    },
                                    onItemLongPress = { v -> contextMenuVideo = v },
                                )
                            }
                        }
                        if (allVideos.isNotEmpty()) {
                            item {
                                VideoRow(
                                    title = "Недавно добавленные",
                                    videos = allVideos.take(20),
                                    baseUrl = baseUrl,
                                    marksMap = marksMap,
                                    onItemClick = { v ->
                                        onPlayVideo(null, v.id, allVideos.map { it.id }, allVideos.map { it.title })
                                    },
                                    onItemLongPress = { v -> contextMenuVideo = v },
                                )
                            }
                        }
                        // Playlists without a group
                        val ungroupedPlaylists = playlists.filter { it.groupId == null }
                        for (pl in ungroupedPlaylists) {
                            val plVideos = if (sortDesc) {
                                pl.items.sortedByDescending { it.order }.map { it.video }
                            } else {
                                pl.items.sortedBy { it.order }.map { it.video }
                            }
                            if (plVideos.isNotEmpty()) {
                                item {
                                    VideoRow(
                                        title = pl.name,
                                        videos = plVideos,
                                        baseUrl = baseUrl,
                                        marksMap = marksMap,
                                        onItemClick = { v ->
                                            onPlayVideo(pl.id, v.id, plVideos.map { it.id }, plVideos.map { it.title })
                                        },
                                        onItemLongPress = { v -> contextMenuVideo = v },
                                        onMarkAllWatched = {
                                            scope.launch {
                                                try {
                                                    val api = repo.getApi()
                                                    api.markAllWatched(pl.id)
                                                    reloadMarks()
                                                } catch (_: Exception) {}
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        // Grouped playlists
                        for (g in playlistGroups) {
                            val groupPlaylists = playlists.filter { it.groupId == g.id }
                            if (groupPlaylists.isEmpty()) continue
                            item {
                                GroupHeader(group = g)
                            }
                            for (pl in groupPlaylists) {
                                val plVideos = if (sortDesc) {
                                    pl.items.sortedByDescending { it.order }.map { it.video }
                                } else {
                                    pl.items.sortedBy { it.order }.map { it.video }
                                }
                                if (plVideos.isNotEmpty()) {
                                    item {
                                        VideoRow(
                                            title = pl.name,
                                            videos = plVideos,
                                            baseUrl = baseUrl,
                                            marksMap = marksMap,
                                            onItemClick = { v ->
                                                onPlayVideo(pl.id, v.id, plVideos.map { it.id }, plVideos.map { it.title })
                                            },
                                            onItemLongPress = { v -> contextMenuVideo = v },
                                            onMarkAllWatched = {
                                                scope.launch {
                                                    try {
                                                        val api = repo.getApi()
                                                        api.markAllWatched(pl.id)
                                                        reloadMarks()
                                                    } catch (_: Exception) {}
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Long-press context menu overlay
        contextMenuVideo?.let { v ->
            val marks = marksMap[v.id]
            VideoContextMenu(
                video = v,
                watched = marks?.watchedByAny == true,
                favorite = marks?.favoriteByAny == true,
                onToggleWatched = onToggleWatched,
                onToggleFavorite = onToggleFavorite,
                onPlay = {
                    // Play within the row's context (find which list it belongs to)
                    val list = continueWalkingListFor(v, continueWatching, allVideos, playlists)
                    onPlayVideo(null, v.id, list.map { it.id }, list.map { it.title })
                },
                onDismiss = { contextMenuVideo = null },
            )
        }
    }
}

private fun continueWalkingListFor(
    v: VideoItem,
    continueWatching: List<VideoItem>,
    allVideos: List<VideoItem>,
    playlists: List<PlaylistFull>,
): List<VideoItem> {
    if (continueWatching.any { it.id == v.id }) return continueWatching
    if (allVideos.any { it.id == v.id }) return allVideos
    for (pl in playlists) {
        val plVids = pl.items.map { it.video }
        if (plVids.any { it.id == v.id }) return plVids
    }
    return listOf(v)
}

@Composable
private fun GroupHeader(group: PlaylistGroup) {
    val groupColor = try { Color(android.graphics.Color.parseColor(group.color)) } catch (_: Exception) { Color(0xFF6366f1) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!group.icon.isNullOrEmpty()) {
            Text(group.icon, color = groupColor, fontSize = 20.sp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            group.name.uppercase(),
            color = groupColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VideoRow(
    title: String,
    videos: List<VideoItem>,
    onItemClick: (VideoItem) -> Unit,
    baseUrl: String = "",
    marksMap: Map<String, AllMarksEntry> = emptyMap(),
    onItemLongPress: (VideoItem) -> Unit = {},
    onMarkAllWatched: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (onMarkAllWatched != null) {
                Button(
                    onClick = onMarkAllWatched,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1F1F23),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("✓ Все просмотрено", fontSize = 11.sp)
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(videos, key = { it.id }) { v ->
                VideoCard(
                    video = v,
                    onClick = { onItemClick(v) },
                    onLongPress = { onItemLongPress(v) },
                    modifier = Modifier.size(width = 200.dp, height = 130.dp),
                    baseUrl = baseUrl,
                    watched = marksMap[v.id]?.watchedByAny == true,
                    favorite = marksMap[v.id]?.favoriteByAny == true,
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    watched: Boolean = false,
    favorite: Boolean = false,
) {
    // Track OK button press duration for long-press detection.
    // Standard Android TV pattern: long-press OK (DPAD_CENTER) >500ms = context menu.
    // Also respond to Menu key (some remotes have a dedicated Menu button).
    //
    // X4 remote sends HID code 0x60 (Keyboard OK) which Compose 1.5.14 may not map to
    // Key.DirectionCenter. We compare both: the named Key (DirectionCenter) AND the
    // raw Long value we observed from the X4 pult (94489280512L = 0x16000000000).
    val okKeys = setOf(Key.DirectionCenter, Key(94489280512L))
    var pressStartTime by remember { mutableStateOf(0L) }
    var longPressFired by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier.onKeyEvent { e ->
            val isOk = e.key in okKeys
            when {
                // OK pressed down — start tracking
                e.type == KeyEventType.KeyDown && isOk -> {
                    pressStartTime = System.currentTimeMillis()
                    longPressFired = false
                    false  // let click handle short-press; we'll detect long-press on KeyUp
                }
                // OK released — check if it was a long press
                e.type == KeyEventType.KeyUp && isOk -> {
                    val duration = System.currentTimeMillis() - pressStartTime
                    if (duration >= 500 && !longPressFired) {
                        AppLogger.i("VideoCard", "long-press OK detected (${duration}ms) for video ${video.id}")
                        onLongPress()
                        true  // consume — don't let onClick fire
                    } else {
                        false  // short press — let Card.onClick handle it
                    }
                }
                // Repeat KeyDown events come in while holding — fire long-press after threshold
                e.type == KeyEventType.KeyDown && isOk && pressStartTime > 0 && !longPressFired -> {
                    val duration = System.currentTimeMillis() - pressStartTime
                    if (duration >= 500) {
                        longPressFired = true
                        AppLogger.i("VideoCard", "long-press OK fired on repeat (${duration}ms) for video ${video.id}")
                        onLongPress()
                        true
                    } else {
                        false
                    }
                }
                // Menu key (some remotes have a dedicated button)
                e.type == KeyEventType.KeyDown && e.key == Key.Menu -> {
                    AppLogger.i("VideoCard", "Menu key for video ${video.id}")
                    onLongPress()
                    true
                }
                else -> false
            }
        },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1F1F23),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val thumbUrl = if (baseUrl.isNotEmpty()) {
                "$baseUrl/api/videos/${video.id}/thumbnail"
            } else {
                video.thumbnail?.takeIf { it.startsWith("http") }
            }
            if (thumbUrl != null) {
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2F2F35)))
            }

            if (watched || favorite) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (watched) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color(0xCC10B981), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (favorite) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color(0xCCF59E0B), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("★", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(8.dp),
            ) {
                Text(
                    video.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                video.publishedAt?.takeIf { it.isNotEmpty() }?.let { dateStr ->
                    Text(
                        text = formatPubDate(dateStr),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private fun formatPubDate(iso: String): String {
    return try {
        val input = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        val output = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("ru"))
        val date = input.parse(iso.take(19))
        if (date != null) output.format(date) else ""
    } catch (_: Exception) { "" }
}
