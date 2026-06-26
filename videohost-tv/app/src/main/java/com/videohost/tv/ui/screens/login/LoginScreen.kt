package com.videohost.tv.ui.screens.login

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videohost.tv.data.api.VideoHostRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    repo: VideoHostRepository,
    onLoggedIn: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val usernameFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        usernameFocus.requestFocus()
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
            modifier = Modifier.padding(32.dp).width(400.dp),
        ) {
            Text("VideoHost", color = Color.White, fontSize = 36.sp)
            Text(
                "Войдите в свой аккаунт",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Имя пользователя") },
                singleLine = true,
                modifier = Modifier
                    .width(400.dp)
                    .focusRequester(usernameFocus),
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

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(400.dp),
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
                    if (username.isBlank() || password.isBlank()) {
                        error = "Заполните оба поля"
                        return@Button
                    }
                    error = null
                    loading = true
                    scope.launch {
                        try {
                            val api = repo.getApi()
                            api.login(username, password)
                            loading = false
                            onLoggedIn()
                        } catch (e: Exception) {
                            loading = false
                            error = "Ошибка входа: ${e.message ?: "неизвестная"}"
                        }
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444),
                    contentColor = Color.White,
                ),
                modifier = Modifier.width(400.dp).height(48.dp),
            ) {
                Text(if (loading) "Вход..." else "Войти")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1F1F23),
                    contentColor = Color.White.copy(alpha = 0.9f),
                ),
                modifier = Modifier.width(400.dp).height(40.dp),
            ) {
                Text("Настройки сервера")
            }
        }
    }
}
