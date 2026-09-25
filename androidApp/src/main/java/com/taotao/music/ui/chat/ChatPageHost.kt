package com.taotao.music.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.taotao.music.data.ImPeerStore
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
    onPeersChanged: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
) {
    val messages by client.messages.collectAsState()
    val peerNames by client.peerNames.collectAsState()
    val haptic = LocalHapticFeedback.current

    ChatPage(
        connection = connection,
        messages = messages,
        savedPeers = savedPeers,
        peerNames = peerNames,
        onSend = { peerUid, content ->
            onPeersChanged(peerStore.remember(accountId, peerUid))
            client.sendText(peerUid, content)
        },
        onPeerSelected = client::loadRecentMessages,
        onPeerActiveChanged = client::setActivePeer,
        onPeersVisible = client::loadPeerNames,
        onRevoke = { chatMessage ->
            runCatching { client.revokeMessage(chatMessage) }
                .onFailure { error -> onMessage("撤回失败: ${error.message}") }
        },
        onMessage = onMessage,
        onNewMessage = {
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        },
    )
}
