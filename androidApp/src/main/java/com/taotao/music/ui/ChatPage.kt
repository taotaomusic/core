package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taotao.music.data.ImConnectionInfo
import com.taotao.music.data.ImConnectionState
import com.taotao.music.data.TencentMusicApi

/** 好友与消息均由业务服务持久化；离线用户上线后会按游标补齐消息。 */
@Composable
fun ChatPage(
    connection: ImConnectionInfo,
    friends: List<TencentMusicApi.ImFriend>,
    requests: List<TencentMusicApi.ImFriendRequest>,
    messages: List<TencentMusicApi.ImChatMessage>,
    onAddFriend: (String) -> Unit,
    onAcceptFriend: (String) -> Unit,
    onSend: (String, String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var selectedUid by remember { mutableStateOf<String?>(null) }
    var friendUid by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    val selected = friends.firstOrNull { it.uid == selectedUid }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("好友与离线消息同步", style = MaterialTheme.typography.bodySmall) }
            ConnectionBadge(connection.state)
        }
        if (selected == null) {
            Text(
                text = "我的聊天 ID：${connection.uid ?: "正在获取"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = friendUid, onValueChange = { friendUid = it }, label = { Text("添加好友：输入对方聊天 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = {
                Icon(Icons.Default.PersonAdd, "发送好友申请", modifier = Modifier.clickable(enabled = friendUid.isNotBlank()) {
                    runCatching { onAddFriend(friendUid) }.onSuccess { friendUid = "" }.onFailure { onMessage(it.message ?: "好友申请失败") }
                })
            })
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (requests.isNotEmpty()) item { Text("好友申请", style = MaterialTheme.typography.titleSmall) }
                items(requests, key = { "request-${it.uid}" }) { request ->
                    Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(request.nickname); Text(if (request.direction == "incoming") "请求添加你为好友" else "等待对方接受", style = MaterialTheme.typography.bodySmall) }
                        if (request.direction == "incoming") Button(onClick = { onAcceptFriend(request.uid) }) { Text("接受") }
                    } }
                }
                item { Text("好友", style = MaterialTheme.typography.titleSmall) }
                items(friends, key = { it.uid }) { friend ->
                    Card(Modifier.fillMaxWidth().clickable { selectedUid = friend.uid }) { Column(Modifier.padding(14.dp)) { Text(friend.nickname, fontWeight = FontWeight.Medium); Text(friend.username, style = MaterialTheme.typography.bodySmall) } }
                }
                if (friends.isEmpty()) item { Text("还没有好友。添加好友后，即使对方离线也会保留消息。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            Text(selected.nickname, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { selectedUid = null })
            val conversation = messages.filter { it.senderUid == selected.uid || it.recipientUid == selected.uid }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(conversation, key = { it.id }) { message -> ChatBubble(message, message.senderUid == connection.uid) } }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("消息") }, modifier = Modifier.weight(1f), maxLines = 4)
                Spacer(Modifier.width(8.dp))
                Button(enabled = draft.isNotBlank(), onClick = { runCatching { onSend(selected.uid, draft) }.onSuccess { draft = "" }.onFailure { onMessage(it.message ?: "消息发送失败") } }) { Icon(Icons.Default.Send, "发送") }
            }
        }
    }
}

@Composable
private fun ConnectionBadge(state: ImConnectionState) {
    val (label, color) = when (state) { ImConnectionState.CONNECTED -> "已连接" to MaterialTheme.colorScheme.primary; ImConnectionState.CONNECTING -> "同步中" to MaterialTheme.colorScheme.tertiary; ImConnectionState.NO_NETWORK -> "无网络" to MaterialTheme.colorScheme.error; ImConnectionState.FAILED -> "连接失败" to MaterialTheme.colorScheme.error; ImConnectionState.IDLE -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.background(color.copy(alpha = 0.12f), CircleShape).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
private fun ChatBubble(message: TencentMusicApi.ImChatMessage, isMine: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart) { Text(message.content, modifier = Modifier.background(if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 9.dp), color = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
}
