package com.videohost.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.data.model.PlaylistFull
import com.videohost.tv.data.model.VideoItem
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repo: VideoHostRepository,
    onPlayVideo: (playlistId: String?, videoId: String, allIds: List<String>) -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<PlaylistFull>>(emptyList()) }
    var allVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var continueWatching by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
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
            // Build "Continue watching" — for each video, fetch its progress; keep those with > 5s
            val cont = mutableListOf<VideoItem>()
            for (v in vids) {
                try {
                    val p = api.getProgress(v.id)
                    if (p.positionSec > 5f) cont.add(v)
                } catch (_: Exception) {}
            }
            continueWatching = cont
            loading = false
        } catch (e: Exception) {
            error = "Не удалось загрузить данные: ${e.message ?: "неизвестная ошибка"}"
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F10)),
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
                        Text(
                            "VideoHost",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
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
                                    onItemClick = { v ->
                                        onPlayVideo(null, v.id, continueWatching.map { it.id })
                                    },
                                )
                            }
                        }
                        if (allVideos.isNotEmpty()) {
                            item {
                                VideoRow(
                                    title = "Недавно добавленные",
                                    videos = allVideos.take(20),
                                    onItemClick = { v ->
                                        onPlayVideo(null, v.id, allVideos.map { it.id })
                                    },
                                )
                            }
                        }
                        for (pl in playlists) {
                            val plVideos = pl.items.sortedBy { it.order }.map { it.video }
                            if (plVideos.isNotEmpty()) {
                                item {
                                    VideoRow(
                                        title = pl.name,
                                        videos = plVideos,
                                        onItemClick = { v ->
                                            onPlayVideo(pl.id, v.id, plVideos.map { it.id })
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
}

@Composable
private fun VideoRow(
    title: String,
    videos: List<VideoItem>,
    onItemClick: (VideoItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(videos, key = { it.id }) { v ->
                VideoCard(
                    video = v,
                    onClick = { onItemClick(v) },
                    modifier = Modifier.size(width = 200.dp, height = 130.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1F1F23),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val thumbUrl = video.thumbnail?.let { thumb ->
                if (thumb.startsWith("http")) thumb else null
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
            // Title overlay at bottom
            Box(
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
            }
        }
    }
}
