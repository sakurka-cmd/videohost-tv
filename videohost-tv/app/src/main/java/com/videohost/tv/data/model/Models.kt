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
    val groupId: String? = null,
    val group: PlaylistGroup? = null,
)

@Serializable
data class PlaylistGroup(
    val id: String,
    val name: String,
    val color: String = "#6366f1",
    val icon: String? = null,
    val order: Int = 0,
    val createdAt: String = "",
    val playlists: List<PlaylistSummary> = emptyList(),
)

@Serializable
data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val lifetimeDays: Int? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class PlaylistFull(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String = "",
    val items: List<PlaylistItem> = emptyList(),
    val lifetimeDays: Int? = null,
    val groupId: String? = null,
    val group: PlaylistGroup? = null,
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

@Serializable
data class VideoMark(
    val watched: Boolean = false,
    val favorite: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class MarksBulkResponse(
    val marks: List<MarksBulkEntry> = emptyList(),
)

@Serializable
data class MarksBulkEntry(
    val videoId: String,
    val watched: Boolean = false,
    val favorite: Boolean = false,
    val updatedAt: String? = null,
)
