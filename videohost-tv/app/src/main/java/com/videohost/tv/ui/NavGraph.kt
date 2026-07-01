package com.videohost.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.ui.screens.home.HomeScreen
import com.videohost.tv.ui.screens.login.LoginScreen
import com.videohost.tv.ui.screens.player.PlayerScreen
import com.videohost.tv.ui.screens.player.PlaybackTarget
import com.videohost.tv.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URLEncoder

object Routes {
    const val Splash = "splash"
    const val Login = "login"
    const val Settings = "settings"
    const val Home = "home"
    const val Player = "player"
}

@Composable
fun NavGraph(repo: VideoHostRepository) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.Splash) {

        composable(Routes.Splash) {
            LaunchedEffect(Unit) {
                val url = repo.serverUrlFlow.first()
                val session = repo.sessionCookieFlow.first()
                when {
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
                onPlayVideo = { playlistId, videoId, allIds, allTitles ->
                    val target = PlaybackTarget(
                        playlistId = playlistId,
                        videoId = videoId,
                        allVideoIds = allIds,
                        allVideoTitles = allTitles,
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
