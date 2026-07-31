package com.videohost.tv.data.api

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "videohost_prefs")
private val SERVER_URL_KEY = stringPreferencesKey("server_url")
private val SESSION_COOKIE_KEY = stringPreferencesKey("session_cookie")
private val AUTOPLAY_NEXT_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("autoplay_next")
private val SORT_DESC_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("sort_desc")
private val HIDE_WATCHED_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("hide_watched")
private val SPEED_PREFIX = "speed_"

/**
 * Holds runtime state for the VideoHost client: base URL + session cookie.
 * Rebuilds the api when URL changes.
 */
class VideoHostRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val serverUrlFlow: Flow<String> = context.dataStore.data.map { it[SERVER_URL_KEY] ?: "" }
    val sessionCookieFlow: Flow<String> = context.dataStore.data.map { it[SESSION_COOKIE_KEY] ?: "" }
    val autoplayNextFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTOPLAY_NEXT_KEY] ?: true }

    suspend fun getServerUrl(): String = serverUrlFlow.first()
    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL_KEY] = url.trimEnd('/') }
    }

    suspend fun getSessionCookie(): String = sessionCookieFlow.first()
    suspend fun setSessionCookie(cookie: String) {
        context.dataStore.edit { it[SESSION_COOKIE_KEY] = cookie }
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.remove(SESSION_COOKIE_KEY) }
    }
    suspend fun getAutoplayNext(): Boolean = autoplayNextFlow.first()
    suspend fun setAutoplayNext(value: Boolean) {
        context.dataStore.edit { it[AUTOPLAY_NEXT_KEY] = value }
    }

    // ---- Home screen UI preferences (persisted across app restarts) ----
    // Sort direction for playlist videos: true = newest first (desc), false = oldest first (asc).
    val sortDescFlow: Flow<Boolean> = context.dataStore.data.map { it[SORT_DESC_KEY] ?: false }
    suspend fun getSortDesc(): Boolean = sortDescFlow.first()
    suspend fun setSortDesc(value: Boolean) {
        context.dataStore.edit { it[SORT_DESC_KEY] = value }
    }

    // Hide videos already marked as "watched" by anyone on the home screen.
    val hideWatchedFlow: Flow<Boolean> = context.dataStore.data.map { it[HIDE_WATCHED_KEY] ?: false }
    suspend fun getHideWatched(): Boolean = hideWatchedFlow.first()
    suspend fun setHideWatched(value: Boolean) {
        context.dataStore.edit { it[HIDE_WATCHED_KEY] = value }
    }
    suspend fun getPlaybackSpeed(playlistId: String?): Float {
        if (playlistId.isNullOrEmpty()) return 1.0f
        val key = stringPreferencesKey(SPEED_PREFIX + playlistId)
        return context.dataStore.data.map { it[key]?.toFloatOrNull() ?: 1.0f }.first()
    }
    suspend fun setPlaybackSpeed(playlistId: String?, speed: Float) {
        if (playlistId.isNullOrEmpty()) return
        val key = stringPreferencesKey(SPEED_PREFIX + playlistId)
        context.dataStore.edit { it[key] = speed.toString() }
    }

    /**
     * Simple cookie jar that only handles vh_session cookie. Reads/writes the
     * persisted value via runBlocking on DataStore (okhttp callbacks are not
     * coroutine-aware). The cookie is small and synchronous access is acceptable.
     */
    private class SessionCookieJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val session = cookies.firstOrNull { it.name == "vh_session" }
            if (session != null) {
                runBlocking {
                    context.dataStore.edit { it[SESSION_COOKIE_KEY] = session.value }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val value = runBlocking { sessionCookieFlow.first() }
            if (value.isEmpty()) return emptyList()
            val cookie = Cookie.Builder()
                .name("vh_session")
                .value(value)
                .domain(url.host)
                .build()
            return listOf(cookie)
        }

        // lateinit context — set by outer class before any request
        lateinit var context: Context
        lateinit var sessionCookieFlow: Flow<String>
    }

    private fun buildOkHttp(): OkHttpClient {
        val jar = SessionCookieJar().apply {
            this.context = this@VideoHostRepository.context
            this.sessionCookieFlow = this@VideoHostRepository.sessionCookieFlow
        }
        return OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()
    }

    suspend fun getApi(): VideoHostApi {
        val baseUrl = getServerUrl().ifEmpty { throw IllegalStateException("Server URL not set") }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(buildOkHttp())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VideoHostApi::class.java)
    }
}
