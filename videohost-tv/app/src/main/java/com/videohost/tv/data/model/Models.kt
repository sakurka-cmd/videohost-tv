package com.videohost.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val filename: String = "",
    val mimeType: String = "video/mp4",
    val size: Long = 0L,
    val createdAt: String = "",
    val thumbnail: String? = null,
    val publishedAt: String? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String = "",
)

@Serializable
data class PlaylistFull(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String = "",
    val items: List<PlaylistItem> = emptyList(),
)

@Serializable
data class PlaylistItem(
    val id: String,
    val order: Int = 0,
    val addedAt: String = "",
    val video: VideoItem,
)

@Serializable
data class Session(
    val id: String,
    val username: String,
    val role: String = "USER",
    val isApproved: Boolean = true,
)

@Serializable
data class WatchProgress(
    val positionSec: Float = 0f,
    val durationSec: Float? = null,
    val updatedAt: String? = null,
)
