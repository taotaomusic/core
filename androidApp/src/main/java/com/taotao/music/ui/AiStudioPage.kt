package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.taotao.music.data.TencentMusicApi

/**
 * AI 工作台只调用本项目的图片任务接口；第三方 GPT Image Key 完全由服务端的后援团管理保存。
 */
@Composable
fun AiStudioPage(
    signedIn: Boolean,
    submitting: Boolean,
    task: TencentMusicApi.ImageTask?,
    onGenerate: (String, String, String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf("1:1") }
    var quality by remember { mutableStateOf("medium") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text("AI 工作台", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("使用 GPT Image 创作音乐灵感视觉", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "创作请求由服务端安全转发，API Key 不会保存到设备。",
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)).padding(14.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("描述你想创作的画面") },
            placeholder = { Text("例如：雨夜里戴耳机的少女，专辑封面风格") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Text("画面比例", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1:1", "3:4", "9:16", "16:9").forEach { item ->
                FilterChip(selected = ratio == item, onClick = { ratio = item }, label = { Text(item) })
            }
        }
        Text("清晰度", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("low" to "快速", "medium" to "标准", "high" to "精细").forEach { (value, label) ->
                FilterChip(selected = quality == value, onClick = { quality = value }, label = { Text(label) })
            }
        }
        Button(
            onClick = { onGenerate(prompt.trim(), ratio, quality) },
            enabled = signedIn && prompt.isNotBlank() && !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Icon(Icons.Default.AutoAwesome, null)
            Text(if (submitting) "正在创作…" else if (signedIn) "开始生成" else "登录后可生成", Modifier.padding(start = 8.dp))
        }
        task?.let { current ->
            Spacer(Modifier.height(4.dp))
            when {
                current.imageUrl != null -> {
                    AsyncImage(current.imageUrl, "AI 生成图片", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)))
                    Text("生成完成", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                current.state == "FAILED" -> Text(current.error ?: "图片生成失败", color = MaterialTheme.colorScheme.error)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(" 正在生成 ${current.progress.coerceIn(0, 100)}%", Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
