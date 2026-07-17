package com.videohost.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videohost.tv.data.model.VideoItem

/**
 * Long-press context menu for a video card. Opens as a centered Dialog with
 * D-pad navigation (up/down to select, OK to confirm, Back to dismiss).
 *
 * Shows current state of watched/favorite marks with checkmarks, and lets
 * the user toggle them without opening the player.
 */
@Composable
fun VideoContextMenu(
    video: VideoItem,
    watched: Boolean,
    favorite: Boolean,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,  // TV: no pointer, dismiss only via Back
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            MenuContent(
                video = video,
                watched = watched,
                favorite = favorite,
                onToggleWatched = {
                    onToggleWatched()
                },
                onToggleFavorite = {
                    onToggleFavorite()
                },
                onPlay = {
                    onPlay()
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun MenuContent(
    video: VideoItem,
    watched: Boolean,
    favorite: Boolean,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var selectedIndex by remember { mutableStateOf(0) }
    val items = listOf("watched", "favorite", "play", "close")

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .width(440.dp)
            .background(Color(0xFF1F1F23), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
            .padding(20.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> {
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionDown -> {
                        selectedIndex = (selectedIndex + 1).coerceAtMost(items.size - 1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        when (items[selectedIndex]) {
                            "watched" -> onToggleWatched()
                            "favorite" -> onToggleFavorite()
                            "play" -> onPlay()
                            "close" -> onDismiss()
                        }
                        true
                    }
                    Key.Back -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title
        Text(
            text = video.title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!video.publishedAt.isNullOrEmpty()) {
            Text(
                text = video.publishedAt.take(10),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))

        // Menu items
        MenuRow(
            label = if (watched) "✓ Просмотрено (снять)" else "✓ Отметить просмотрено",
            selected = selectedIndex == 0,
            accent = if (watched) Color(0xFF10B981) else Color.White,
        )
        MenuRow(
            label = if (favorite) "★ Избранное (снять)" else "★ Добавить в избранное",
            selected = selectedIndex == 1,
            accent = if (favorite) Color(0xFFF59E0B) else Color.White,
        )
        MenuRow(
            label = "▶ Смотреть",
            selected = selectedIndex == 2,
            accent = Color(0xFFEF4444),
        )
        MenuRow(
            label = "✕ Закрыть",
            selected = selectedIndex == 3,
            accent = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun MenuRow(label: String, selected: Boolean, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(
                if (selected) Color(0xFF2F2F35) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) accent else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
