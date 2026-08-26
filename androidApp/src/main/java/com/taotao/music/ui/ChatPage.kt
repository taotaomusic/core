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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taotao.music.data.ImChatMessage
import com.taotao.music.data.ImConnectionInfo
import com.taotao.music.data.ImConnectionState

/**
 * 悟空 IM 的最小可用私聊页。
 *
 * 用户将对方分享的聊天 UUID 填入顶部输入框，即可通过个人频道进行在线文本聊天；会话列表、
 * 联系人和离线通知后续由业务服务提供同步接口后再扩展，避免客户端直接暴露悟空管理 API。
 */
@Composable
fun ChatPage(
    connection: ImConnectionInfo,
    messages: List<ImChatMessage>,
    onSend: (peerUid: String, content: String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var peerUid by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    val peerMessages = remember(messages, peerUid) {
        messages.filter { it.peerUid == peerUid.trim().lowercase() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("悟空 IM 在线私聊", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ConnectionBadge(connection.state)
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp)) {
                Text("我的聊天 ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = connection.uid ?: "正在获取聊天身份…",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text("将此 UUID 分享给好友；它不是登录令牌。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedTextField(
            value = peerUid,
            onValueChange = { peerUid = it },
            label = { Text("对方聊天 ID（UUID）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (peerUid.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("输入好友的聊天 ID，开始一对一聊天", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peerMessages, key = { it.id }) { message -> ChatBubble(message) }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("消息") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.size(8.dp))
            Button(
                enabled = peerUid.isNotBlank() && draft.isNotBlank() && connection.state == ImConnectionState.CONNECTED,
                onClick = {
                    runCatching { onSend(peerUid, draft) }
                        .onSuccess { draft = "" }
                        .onFailure { onMessage(it.message ?: "消息发送失败") }
                },
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun ConnectionBadge(state: ImConnectionState) {
    val (label, color) = when (state) {
        ImConnectionState.CONNECTED -> "已连接" to MaterialTheme.colorScheme.primary
        ImConnectionState.CONNECTING -> "连接中" to MaterialTheme.colorScheme.tertiary
        ImConnectionState.NO_NETWORK -> "无网络" to MaterialTheme.colorScheme.error
        ImConnectionState.FAILED -> "连接失败" to MaterialTheme.colorScheme.error
        ImConnectionState.IDLE -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.12f), CircleShape).padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun ChatBubble(message: ImChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = message.content,
            modifier = Modifier.background(
                if (message.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp),
            ).padding(horizontal = 12.dp, vertical = 9.dp),
            color = if (message.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
