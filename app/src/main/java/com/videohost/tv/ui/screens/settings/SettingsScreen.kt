package com.videohost.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videohost.tv.data.api.VideoHostRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repo: VideoHostRepository,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val urlFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        url = repo.serverUrlFlow.first()
        urlFocus.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F10)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp).width(500.dp),
        ) {
            Text("Настройки VideoHost TV", color = Color.White, fontSize = 28.sp)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Адрес VideoHost") },
                placeholder = { Text("http://your-server:3002") },
                singleLine = true,
                modifier = Modifier
                    .width(500.dp)
                    .focusRequester(urlFocus),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1F1F23),
                    unfocusedContainerColor = Color(0xFF1F1F23),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                    cursorColor = Color.White,
                ),
            )

            error?.let {
                Text(it, color = Color(0xFFEF4444), fontSize = 13.sp)
            }

            Button(
                onClick = {
                    val cleaned = url.trim().trimEnd('/')
                    if (cleaned.isEmpty()) {
                        error = "URL не может быть пустым"
                        return@Button
                    }
                    if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
                        error = "URL должен начинаться с http:// или https://"
                        return@Button
                    }
                    error = null
                    scope.launch {
                        repo.setServerUrl(cleaned)
                        onDone()
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444),
                    contentColor = Color.White,
                ),
                modifier = Modifier.width(500.dp).height(48.dp),
            ) {
                Text("Сохранить")
            }
        }
    }
}
