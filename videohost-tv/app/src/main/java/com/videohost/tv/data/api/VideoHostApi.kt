package com.videohost.tv.data.api

import com.videohost.tv.data.model.MarksBulkResponse
import com.videohost.tv.data.model.PlaylistFull
import com.videohost.tv.data.model.Session
import com.videohost.tv.data.model.VideoItem
import com.videohost.tv.data.model.VideoMark
import com.videohost.tv.data.model.WatchProgress
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.Query

interface VideoHostApi {
    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Session

    @GET("api/auth/me")
    suspend fun me(): Session

    @GET("api/playlists")
    suspend fun listPlaylists(): List<PlaylistFull>

    @GET("api/videos")
    suspend fun listVideos(): List<VideoItem>

    @GET("api/videos/{id}/progress")
    suspend fun getProgress(@Path("id") id: String): WatchProgress

    @PUT("api/videos/{id}/progress")
    suspend fun putProgress(@Path("id") id: String, @Body body: WatchProgressUpdate)

    @DELETE("api/videos/{id}/progress")
    suspend fun deleteProgress(@Path("id") id: String)

    /** Per-video marks for the current user. */
    @GET("api/videos/{id}/marks")
    suspend fun getMarks(@Path("id") id: String): VideoMark

    /** Partial update — only supplied fields are touched. */
    @PUT("api/videos/{id}/marks")
    suspend fun putMarks(@Path("id") id: String, @Body body: MarkUpdateRequest): VideoMark

    /** Clear all marks (watched + favorite) for this video. */
    @DELETE("api/videos/{id}/marks")
    suspend fun deleteMarks(@Path("id") id: String)

    /** Bulk list of all of the current user's marks. Optional playlistId filter. */
    @GET("api/me/marks")
    suspend fun getMyMarks(@Query("playlistId") playlistId: String? = null): MarksBulkResponse

    /** Marks every video in the given playlist as "watched" for the current user. */
    @POST("api/playlists/{id}/mark-all-watched")
    suspend fun markAllWatched(@Path("id") id: String): MarkAllWatchedResponse
}

@kotlinx.serialization.Serializable
data class MarkAllWatchedResponse(val success: Boolean = false, val marked: Int = 0)

@kotlinx.serialization.Serializable
data class LoginRequest(val username: String, val password: String)

@kotlinx.serialization.Serializable
data class WatchProgressUpdate(val positionSec: Float, val durationSec: Float? = null)

@kotlinx.serialization.Serializable
data class MarkUpdateRequest(val watched: Boolean? = null, val favorite: Boolean? = null)
