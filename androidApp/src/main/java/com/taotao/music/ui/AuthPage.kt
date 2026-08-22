package com.taotao.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.taotao.music.data.TencentMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 登录与注册页面：只负责收集凭据，令牌保存由调用方的会话层完成。 */
@Composable
fun AuthPage(api: TencentMusicApi, onAuthenticated: (TencentMusicApi.TokenPair) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 与服务端 /api/v1/auth/register 的校验规则保持一致，避免提交后才报错。
    val canSubmit = !loading && username.trim().length >= 3 && password.length >= 6

    fun submit() {
        if (!canSubmit) return
        loading = true
        message = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (registerMode) api.register(username.trim(), password) else api.login(username.trim(), password)
                }
            }.onSuccess(onAuthenticated).onFailure { message = it.message ?: "操作失败，请稍后重试" }
            loading = false
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("桃桃音乐", style = MaterialTheme.typography.headlineMedium)
                Text(if (registerMode) "创建账号" else "登录账号", modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    supportingText = { Text("3 至 32 位") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    supportingText = { Text("至少 6 位") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                Button(enabled = canSubmit, onClick = ::submit, modifier = Modifier.fillMaxWidth()) {
                    if (loading) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    else Text(if (registerMode) "注册并登录" else "登录")
                }
                TextButton(
                    enabled = !loading,
                    onClick = { registerMode = !registerMode; message = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (registerMode) "已有账号，去登录" else "没有账号，去注册") }
            }
        }
    }
}
