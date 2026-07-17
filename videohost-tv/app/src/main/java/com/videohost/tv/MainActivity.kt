package com.videohost.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.videohost.tv.data.api.VideoHostRepository
import com.videohost.tv.logging.AppLogger
import com.videohost.tv.ui.NavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Init file logger + crash handler FIRST, before anything else can crash
        AppLogger.init(applicationContext)
        AppLogger.i("MainActivity", "onCreate, app v2.0.6 (code 14)")

        val repo = VideoHostRepository(applicationContext)
        // Read intent extras for CLI provisioning:
        //   adb shell am start -n com.videohost.tv/.MainActivity \
        //     -e server_url "http://..." -e username "x4" -e password "x4"
        val intent: Intent? = intent
        val serverUrl = intent?.getStringExtra("server_url")
        val username = intent?.getStringExtra("username")
        val password = intent?.getStringExtra("password")
        if (serverUrl != null) AppLogger.i("MainActivity", "provisioning: url=$serverUrl user=$username")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFEF4444),
                background = Color(0xFF0F0F10),
                surface = Color(0xFF1F1F23),
            )) {
                Surface(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F10)),
                ) {
                    NavGraph(
                        repo = repo,
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        AppLogger.i("MainActivity", "onDestroy")
        super.onDestroy()
    }
}
