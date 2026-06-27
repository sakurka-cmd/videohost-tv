package com.videohost.tv.data.api

import com.videohost.tv.data.model.PlaylistFull
import com.videohost.tv.data.model.Session
import com.videohost.tv.data.model.VideoItem
import com.videohost.tv.data.model.WatchProgress
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path

interface VideoHostApi {
    /**
     * Login — server returns Set-Cookie header with vh_session=<userId>
     * that we capture via cookie jar.
     *
     * NOTE: server expects JSON body (not form-encoded). Send a
     * LoginRequest via @Body.
     */
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
    suspend fun putProgress(
        @Path("id") id: String,
        @Body body: WatchProgressUpdate,
    )

    @DELETE("api/videos/{id}/progress")
    suspend fun deleteProgress(@Path("id") id: String)
}

@kotlinx.serialization.Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@kotlinx.serialization.Serializable
data class WatchProgressUpdate(
    val positionSec: Float,
    val durationSec: Float? = null,
)
