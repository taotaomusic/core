package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taotao.music.data.im.ImChatMessage
import com.taotao.music.data.im.ImConnectionInfo
import com.taotao.music.data.im.ImConnectionState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 悟空 IM 私聊页：左侧抽屉用于切换已保存的聊天。 */
@Composable
fun ChatPage(
    connection: ImConnectionInfo,
    messages: List<ImChatMessage>,
    savedPeers: List<String>,
    peerNames: Map<String, String>,
    onSend: (peerUid: String, content: String) -> Unit,
    onPeerSelected: (peerUid: String) -> Unit,
    onPeersVisible: (List<String>) -> Unit,
    onRevoke: (ImChatMessage) -> Unit,
    onMessage: (String) -> Unit,
) {
    var peerUid by remember(savedPeers) { mutableStateOf(savedPeers.firstOrNull().orEmpty()) }
    var draft by remember { mutableStateOf("") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()
    val peerMessages = remember(messages, peerUid) { messages.filter { it.peerUid == peerUid.trim().lowercase() } }
    LaunchedEffect(peerUid) {
        val normalizedPeerUid = peerUid.trim().lowercase()
        if (UUID_PATTERN.matches(normalizedPeerUid)) onPeerSelected(normalizedPeerUid)
    }
    LaunchedEffect(savedPeers) { onPeersVisible(savedPeers) }
    // 进入会话或本地历史补齐后，定位到最后一条而非列表顶部。
    LaunchedEffect(peerUid, peerMessages.lastOrNull()?.id) {
        if (peerMessages.isNotEmpty()) messageListState.scrollToItem(peerMessages.lastIndex)
    }

    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet {
            Text("聊天", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp))
            if (savedPeers.isEmpty()) {
                Text("还没有聊天。发送第一条消息后，好友会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            } else {
                savedPeers.forEach { savedUid ->
                    Text(
                        text = peerNames[savedUid] ?: "加载昵称…",
                        color = if (savedUid == peerUid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (savedUid == peerUid) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable {
                            peerUid = savedUid
                            scope.launch { drawerState.close() }
                        }.padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Menu, "打开聊天列表", modifier = Modifier.size(28.dp).clickable { scope.launch { drawerState.open() } })
                Spacer(Modifier.size(12.dp))
                Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(peerNames[peerUid] ?: peerUid.takeIf { it.isBlank() } ?: "加载昵称…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ConnectionBadge(connection.state)
            }
            if (peerUid.isBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("从左上角打开聊天列表", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(
                    state = messageListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { items(peerMessages, key = { it.id }) { ChatBubble(it, onRevoke) } }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("消息") }, modifier = Modifier.weight(1f), maxLines = 4)
                Spacer(Modifier.size(8.dp))
                Button(enabled = peerUid.isNotBlank() && draft.isNotBlank() && connection.state == ImConnectionState.CONNECTED, onClick = {
                    runCatching { onSend(peerUid, draft) }.onSuccess { draft = "" }.onFailure { onMessage(it.message ?: "消息发送失败") }
                }) { Icon(Icons.Default.Send, "发送") }
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
    Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.background(color.copy(alpha = 0.12f), CircleShape).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
private fun ChatBubble(message: ImChatMessage, onRevoke: (ImChatMessage) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.isMine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start) {
            Text(message.content, modifier = Modifier.background(if (message.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 9.dp), color = if (message.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = MESSAGE_TIME_FORMAT.format(Date(message.sentAtMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (message.isMine && !message.isRevoked) {
                Text(
                    if (message.isRead) "已读 · 撤回" else "未读 · 撤回",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onRevoke(message) }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val MESSAGE_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
