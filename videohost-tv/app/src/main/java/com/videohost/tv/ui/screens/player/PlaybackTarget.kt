package com.videohost.tv.ui.screens.player

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackTarget(
    val playlistId: String?,     // null when playing from "all videos" list
    val videoId: String,
    val allVideoIds: List<String>,  // ordered list of video IDs for next/prev navigation
    val allVideoTitles: List<String> = emptyList(),  // titles aligned with allVideoIds (for display in player)
)
