package com.taotao.music.data.im

import org.json.JSONArray

/** 悟空 IM 网关的连接状态，供 Compose 页面展示。 */
enum class ImConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    NO_NETWORK,
    FAILED,
}

data class ImConnectionInfo(
    val uid: String? = null,
    val state: ImConnectionState = ImConnectionState.IDLE,
    val detail: String? = null,
)

/** 当前进程的消息视图；持久化与离线历史完全由悟空 SDK 的本地库负责。 */
data class ImChatMessage(
    val id: String,
    val clientMsgNo: String = id,
    val peerUid: String,
    val content: String,
    val sentAtMillis: Long,
    val messageSeq: Int = 0,
    val isMine: Boolean,
    val isRead: Boolean = false,
    val isRevoked: Boolean = false,
)

/** 服务端转发的悟空会话同步结果。 */
data class ImConversationSync(
    val uid: String?,
    val conversations: JSONArray,
)
