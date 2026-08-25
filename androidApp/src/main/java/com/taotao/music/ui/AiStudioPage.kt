package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.taotao.music.data.TencentMusicApi

private data class AiChatMessage(
    val role: AiChatRole,
    val text: String,
    val taskId: String? = null,
    val progress: Int = 0,
    val imageUrl: String? = null,
    val error: String? = null,
)

private enum class AiChatRole { USER, ASSISTANT }

/** GPT Image 创作工作台：用对话承载意图、进度和图片结果。 */
@Composable
fun AiStudioPage(
    signedIn: Boolean,
    submitting: Boolean,
    task: TencentMusicApi.ImageTask?,
    onGenerate: (String, String, String, String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var ratio by remember { mutableStateOf("1:1") }
    var imageSize by remember { mutableStateOf("1K") }
    var quality by remember { mutableStateOf("low") }
    val messages = remember {
        mutableStateListOf(
            AiChatMessage(AiChatRole.ASSISTANT, "你好，我可以把你的音乐灵感变成一张图片。告诉我你想看到的画面吧。"),
        )
    }
    val listState = rememberLazyListState()

    LaunchedEffect(task?.taskId, task?.state, task?.progress, task?.imageUrl, task?.error) {
        val current = task ?: return@LaunchedEffect
        val index = messages.indexOfLast { it.taskId == current.taskId }
        // 任务创建成功后首次拿到 taskId 时，替换刚插入的“正在提交”占位消息；后续轮询
        // 再按 taskId 精确更新同一气泡，避免每次进度都追加一条聊天记录。
        val targetIndex = if (index >= 0) index else messages.lastIndex
        messages[targetIndex] = when {
            current.imageUrl != null -> AiChatMessage(AiChatRole.ASSISTANT, "创作完成，喜欢这张图吗？", current.taskId, imageUrl = current.imageUrl)
            current.state == "FAILED" -> AiChatMessage(AiChatRole.ASSISTANT, "这次创作没有完成。", current.taskId, error = current.error ?: "图片生成失败")
            else -> AiChatMessage(AiChatRole.ASSISTANT, "正在根据你的描述创作…", current.taskId, progress = current.progress)
        }
        listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text("AI 工作台", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("GPT Image 创作对话", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(messages) { _, message -> AiChatBubble(message) }
            if (submitting && task == null) item { AiChatBubble(AiChatMessage(AiChatRole.ASSISTANT, "正在提交创作请求…")) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("1:1", "3:4", "9:16", "16:9").forEach { item -> FilterChip(ratio == item, { ratio = item }, { Text(item) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("1K", "2K", "4K").forEach { item -> FilterChip(imageSize == item, { imageSize = item }, { Text(item) }) }
            listOf("low" to "低", "medium" to "中", "high" to "高").forEach { (value, label) -> FilterChip(quality == value, { quality = value }, { Text(label) }) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                placeholder = { Text(if (signedIn) "描述你想创作的画面…" else "登录后可开始对话") },
                modifier = Modifier.weight(1f), minLines = 1, maxLines = 4,
                enabled = signedIn && !submitting, shape = RoundedCornerShape(22.dp),
            )
            IconButton(
                onClick = {
                    val prompt = draft.trim()
                    if (prompt.isBlank()) return@IconButton
                    messages += AiChatMessage(AiChatRole.USER, prompt)
                    messages += AiChatMessage(AiChatRole.ASSISTANT, "正在提交创作请求…")
                    draft = ""
                    onGenerate(prompt, ratio, imageSize, quality)
                },
                enabled = signedIn && draft.isNotBlank() && !submitting,
                modifier = Modifier.padding(start = 8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)),
            ) { Icon(Icons.Default.Send, "发送", tint = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

@Composable
private fun AiChatBubble(message: AiChatMessage) {
    val mine = message.role == AiChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(if (mine) 0.78f else 0.88f)
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .padding(14.dp),
        ) {
            Text(message.text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.progress > 0) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(" ${message.progress.coerceIn(0, 100)}%", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message.imageUrl?.let { imageUrl ->
                AsyncImage(imageUrl, "AI 生成图片", Modifier.fillMaxWidth().padding(top = 10.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)))
            }
            message.error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
        }
    }
}
