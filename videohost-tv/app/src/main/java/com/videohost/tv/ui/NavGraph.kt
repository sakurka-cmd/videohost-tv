package com.videohost.tv.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.data.api.LoginRequest
import com.videohost.tv.ui.screens.home.HomeScreen
import com.videohost.tv.ui.screens.login.LoginScreen
import com.videohost.tv.ui.screens.player.PlayerScreen
import com.videohost.tv.ui.screens.player.PlaybackTarget
import com.videohost.tv.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URLEncoder

object Routes {
    const val Splash = "splash"
    const val Login = "login"
    const val Settings = "settings"
    const val Home = "home"
    const val Player = "player"
}

/**
 * Process CLI intent extras passed via `adb shell am start -e ...`.
 *
 * Supported extras:
 *   -e server_url "http://192.168.0.3:3002"
 *       Persist server URL to DataStore. App will then navigate to Login.
 *   -e username "x4" -e password "x4"
 *       Auto-login with given credentials. Requires server_url to be set
 *       (either previously or in the same intent).
 *
 * Example (full provisioning via adb):
 *   adb shell am start -n com.videohost.tv/.MainActivity \
 *     -e server_url "http://192.168.0.3:3002" \
 *     -e username "x4" -e password "x4"
 *
 * Returns a PendingAction describing what should happen next in NavGraph.
 */
suspend fun processIntentExtras(
    repo: VideoHostRepository,
    serverUrl: String?,
    username: String?,
    password: String?,
): PendingAction {
    // 1. Persist server URL if provided
    if (!serverUrl.isNullOrEmpty()) {
        val cleaned = serverUrl.trim().trimEnd('/')
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
            repo.setServerUrl(cleaned)
            Log.i("NavGraph", "CLI: server_url set to $cleaned")
        }
    }
    // 2. Auto-login if both username and password provided
    if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
        return withContext(Dispatchers.IO) {
            try {
                val api = repo.getApi()
                val session = api.login(LoginRequest(username, password))
                repo.setSessionCookie(session.id)
                Log.i("NavGraph", "CLI: logged in as ${session.username} (id=${session.id})")
                PendingAction.GoHome
            } catch (e: Exception) {
                Log.e("NavGraph", "CLI: auto-login failed", e)
                PendingAction.GoLogin
            }
        }
    }
    // 3. Only server_url was provided — go to Login screen
    return if (!serverUrl.isNullOrEmpty()) PendingAction.GoLogin else PendingAction.ProceedNormally
}

sealed class PendingAction {
    object ProceedNormally : PendingAction()
    object GoLogin : PendingAction()
    object GoHome : PendingAction()
}

@Composable
fun NavGraph(repo: VideoHostRepository, serverUrl: String? = null, username: String? = null, password: String? = null) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.Splash) {

        composable(Routes.Splash) {
            LaunchedEffect(Unit) {
                // Process CLI intent extras first (if any)
                val action = processIntentExtras(repo, serverUrl, username, password)
                val url = repo.serverUrlFlow.first()
                val session = repo.sessionCookieFlow.first()
                when {
                    // CLI override: explicit actions take precedence
                    action is PendingAction.GoHome -> nav.navigate(Routes.Home) { popUpTo(0) }
                    action is PendingAction.GoLogin -> nav.navigate(Routes.Login) { popUpTo(0) }
                    // Normal flow
                    url.isEmpty() -> nav.navigate(Routes.Settings) { popUpTo(0) }
                    session.isEmpty() -> nav.navigate(Routes.Login) { popUpTo(0) }
                    else -> nav.navigate(Routes.Home) { popUpTo(0) }
                }
            }
        }

        composable(Routes.Settings) {
            SettingsScreen(
                repo = repo,
                onDone = {
                    nav.navigate(Routes.Login) { popUpTo(0) }
                },
            )
        }

        composable(Routes.Login) {
            LoginScreen(
                repo = repo,
                onLoggedIn = {
                    nav.navigate(Routes.Home) { popUpTo(0) }
                },
                onOpenSettings = {
                    nav.navigate(Routes.Settings)
                },
            )
        }

        composable(Routes.Home) {
            HomeScreen(
                repo = repo,
                onPlayVideo = { playlistId, videoId, allIds, allTitles, allHasSubtitles ->
                    val target = PlaybackTarget(
                        playlistId = playlistId,
                        videoId = videoId,
                        allVideoIds = allIds,
                        allVideoTitles = allTitles,
                        allVideoHasSubtitles = allHasSubtitles,
                    )
                    val json = Json.encodeToString(PlaybackTarget.serializer(), target)
                    val encoded = URLEncoder.encode(json, "UTF-8")
                    nav.navigate("${Routes.Player}/$encoded")
                },
                onOpenSettings = {
                    nav.navigate(Routes.Settings)
                },
                onLogout = {
                    kotlinx.coroutines.MainScope().launch {
                        repo.clearSession()
                        nav.navigate(Routes.Login) { popUpTo(0) }
                    }
                },
            )
        }

        composable(
            route = "${Routes.Player}/{target}",
            arguments = listOf(navArgument("target") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString("target") ?: ""
            val json = java.net.URLDecoder.decode(encoded, "UTF-8")
            val target = Json.decodeFromString(PlaybackTarget.serializer(), json)
            PlayerScreen(
                repo = repo,
                target = target,
                onClose = { nav.popBackStack() },
            )
        }
    }
}
