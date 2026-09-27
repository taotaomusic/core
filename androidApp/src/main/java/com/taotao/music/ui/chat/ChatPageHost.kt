package com.taotao.music.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.taotao.music.data.ImPeerStore
import com.taotao.music.data.im.ImChatMessage
import com.taotao.music.data.im.ImConnectionInfo
import com.taotao.music.data.im.WukongImClient

/**
 * 聊天页宿主：只在聊天标签存在时订阅高频消息状态，IM 客户端本身始终独立接收消息。
 * 这样切换标签不会让主页面跟着每条消息重组，返回聊天页也能从状态流恢复最新内容。
 */
@Composable
internal fun ChatPageHost(
    connection: ImConnectionInfo,
    savedPeers: List<String>,
    peerStore: ImPeerStore,
    accountId: Long?,
    client: WukongImClient,
    ownAvatarUrl: String?,
    onPeersChanged: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
) {
    val messages by client.messages.collectAsState()
    val peerContacts by client.peerContacts.collectAsState()
    val haptic = LocalHapticFeedback.current

    // 消息流每次发射都会重组宿主；撤回回调必须保持同一实例，
    // 否则全部气泡的「参数未变即跳过」比对都会因 lambda 换新而失效，列表被无谓重画。
    val handleRevoke = remember(client, onMessage) {
        { chatMessage: ImChatMessage ->
            runCatching { client.revokeMessage(chatMessage) }
                .onFailure { error -> onMessage("撤回失败: ${error.message}") }
            Unit
        }
    }

    ChatPage(
        connection = connection,
        messages = messages,
        savedPeers = savedPeers,
        peerContacts = peerContacts,
        ownAvatarUrl = ownAvatarUrl,
        onSend = { peerUid, content ->
            onPeersChanged(peerStore.remember(accountId, peerUid))
            client.sendText(peerUid, content)
        },
        onPeerSelected = client::loadRecentMessages,
        onPeerActiveChanged = client::setActivePeer,
        onPeersVisible = client::loadPeerNames,
        onRevoke = handleRevoke,
        onMessage = onMessage,
        onNewMessage = {
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        },
    )
}
