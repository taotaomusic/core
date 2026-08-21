package com.taotao.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taotao.music.data.TencentMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AuthPage(api: TencentMusicApi, onAuthenticated: (TencentMusicApi.TokenPair) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("桃桃音乐", style = MaterialTheme.typography.headlineMedium)
                Text(if (registerMode) "创建账号" else "登录账号", modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
                Spacer(Modifier.height(18.dp))
                Button(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { if (registerMode) api.register(username, password) else api.login(username, password) }
                            }.onSuccess(onAuthenticated).onFailure { message = it.message ?: "操作失败" }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (registerMode) "注册并登录" else "登录") }
                Button(onClick = { registerMode = !registerMode; message = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (registerMode) "已有账号，去登录" else "没有账号，去注册")
                }
            }
        }
    }
}
