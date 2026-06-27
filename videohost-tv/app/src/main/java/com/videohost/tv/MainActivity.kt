package com.videohost.tv

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
import com.videohost.tv.ui.NavGraph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = VideoHostRepository(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFEF4444),
                background = Color(0xFF0F0F10),
                surface = Color(0xFF1F1F23),
            )) {
                Surface(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F10)),
                ) {
                    NavGraph(repo)
                }
            }
        }
    }
}
