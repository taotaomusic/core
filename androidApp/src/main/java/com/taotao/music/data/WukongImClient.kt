package com.taotao.music.data

import android.content.Context
import android.util.Base64
import com.xinbida.wukongim.WKIM
import com.xinbida.wukongim.entity.WKChannel
import com.xinbida.wukongim.entity.WKChannelType
import com.xinbida.wukongim.entity.WKSyncChat
import com.xinbida.wukongim.entity.WKSyncChannelMsg
import com.xinbida.wukongim.entity.WKSyncConvMsg
import com.xinbida.wukongim.entity.WKSyncRecent
import com.xinbida.wukongim.message.type.WKConnectStatus
import com.xinbida.wukongim.msgmodel.WKTextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.ArrayList
import java.util.HashMap

/**
 * 桃桃音乐对悟空 IM Android SDK 的连接适配层。
 *
 * SDK 只能连接 WKProto TCP Gateway；它不持有桃桃音乐的登录凭据，也不直接请求悟空 IM
 * 的管理 API。每次连接前都由 [TencentMusicApi] 向本服务申请一次短期 IM Token。
 */
class WukongImClient(
    context: Context,
    private val api: TencentMusicApi,
    private val sessionStore: ImSessionStore,
    private val deviceId: String,
) {
    private val applicationContext = context.applicationContext
    private val wkIm = WKIM.getInstance()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connection = MutableStateFlow(ImConnectionInfo())
    private val _messages = MutableStateFlow(emptyList<ImChatMessage>())

    /** 页面订阅的连接状态；不会暴露悟空 IM Token。 */
    val connection: StateFlow<ImConnectionInfo> = _connection.asStateFlow()

    /** 当前进程内已收发的文本消息，按私聊对端 UUID 归类。 */
    val messages: StateFlow<List<ImChatMessage>> = _messages.asStateFlow()

    @Volatile
    private var gateway: Gateway? = null

    @Volatile
    private var currentAccountId: Long? = null

    init {
        // 悟空 SDK 在每次重连时都会询问 Gateway；地址只接受服务端下发的 tcp URL。
        wkIm.connectionManager.addOnGetIpAndPortListener { callback ->
            gateway?.let { callback.onGetSocketIpAndPort(it.host, it.port) }
        }
        wkIm.connectionManager.addOnConnectionStatusListener(CONNECTION_STATUS_LISTENER) { code, reason ->
            _connection.value = _connection.value.copy(
                state = when (code) {
                    WKConnectStatus.connecting, WKConnectStatus.syncMsg -> ImConnectionState.CONNECTING
                    WKConnectStatus.success, WKConnectStatus.syncCompleted -> ImConnectionState.CONNECTED
                    WKConnectStatus.noNetwork -> ImConnectionState.NO_NETWORK
                    else -> ImConnectionState.FAILED
                },
                detail = reason?.takeIf { it.isNotBlank() },
            )
        }
        // 将悟空 API 的会话数据交回 SDK；SDK 随后负责写入本地库和离线消息恢复。
        wkIm.conversationManager.addOnSyncConversationListener { lastSeqs, count, version, callback ->
            syncScope.launch {
                runCatching { api.syncImConversations(lastSeqs, count, version) }
                    .onSuccess { callback?.onBack(toSyncChat(it)) }
                    .onFailure { callback?.onBack(null) }
            }
        }
        // SDK 只有在用户进入会话、需要更多历史时才触发该回调；每次最多一页。
        wkIm.msgManager.addOnSyncChannelMsgListener { channelId, _, startSeq, endSeq, limit, pullMode, callback ->
            val normalizedChannelId = channelId?.trim().orEmpty()
            syncScope.launch {
                runCatching {
                    api.syncImChannelMessages(
                        channelId = normalizedChannelId,
                        startMessageSeq = startSeq,
                        endMessageSeq = endSeq,
                        limit = limit.coerceIn(1, 50),
                        pullMode = pullMode.toInt().coerceIn(0, 1),
                    )
                }.onSuccess { callback?.onBack(toSyncChannelMessages(it)) }
                    .onFailure { callback?.onBack(null) }
            }
        }
        wkIm.msgManager.addOnNewMsgListener(NEW_MESSAGE_LISTENER) { received ->
            val currentUid = _connection.value.uid ?: return@addOnNewMsgListener
            val added = received.mapNotNull { message ->
                val fromUid = message.fromUID?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val content = message.baseContentMsgModel?.displayContent?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ImChatMessage(
                    id = message.messageID ?: message.clientMsgNO,
                    peerUid = if (fromUid == currentUid) message.channelID else fromUid,
                    content = content,
                    sentAtMillis = message.timestamp * 1000,
                    isMine = fromUid == currentUid,
                )
            }
            if (added.isNotEmpty()) _messages.value = (_messages.value + added).takeLast(MAX_IN_MEMORY_MESSAGES)
        }
    }

    /**
     * 建立或恢复当前账号的 IM 连接。网络或 IM 服务暂不可用时抛出异常，由界面层静默降级，
     * 不能影响音乐播放、登录等主流程。
     */
    suspend fun connect(accountId: Long) {
        require(accountId > 0) { "账号标识不正确" }
        val session = withContext(Dispatchers.IO) {
            sessionStore.validSession(accountId) ?: api.requestImSession(deviceId).also {
                sessionStore.save(accountId, it)
            }
        }
        val resolvedGateway = Gateway.parse(session.gatewayUrl)

        // 切换账号或刷新连接凭据时，先停止旧连接，避免旧 Token 在后台继续重连。
        if (currentAccountId != null) wkIm.connectionManager.disconnect(false)
        gateway = resolvedGateway
        wkIm.setDeviceId(deviceId)
        wkIm.init(applicationContext, session.uid, session.token)
        currentAccountId = accountId
        _connection.value = ImConnectionInfo(uid = session.uid, state = ImConnectionState.CONNECTING)
        wkIm.connectionManager.connection()
    }

    /** 通过悟空 IM 的个人频道发送文本；对端 UID 必须是桃桃用户获得的 UUID。 */
    fun sendText(peerUid: String, content: String) {
        val normalizedPeerUid = peerUid.trim().lowercase()
        val normalizedContent = content.trim()
        require(UUID_PATTERN.matches(normalizedPeerUid)) { "对方聊天 ID 必须是 UUID" }
        require(normalizedContent.isNotEmpty()) { "消息不能为空" }
        require(_connection.value.state == ImConnectionState.CONNECTED) { "聊天服务尚未连接" }

        _messages.value = (_messages.value + ImChatMessage(
            id = "local-${System.nanoTime()}",
            peerUid = normalizedPeerUid,
            content = normalizedContent,
            sentAtMillis = System.currentTimeMillis(),
            isMine = true,
        )).takeLast(MAX_IN_MEMORY_MESSAGES)
        wkIm.msgManager.send(WKTextContent(normalizedContent), WKChannel(normalizedPeerUid, WKChannelType.PERSONAL))
    }

    /** 退出账号或会话彻底失效时停止重连并清除 SDK 中保留的 Token。 */
    fun signOut() {
        gateway = null
        currentAccountId = null
        _connection.value = ImConnectionInfo()
        _messages.value = emptyList()
        wkIm.connectionManager.disconnect(true)
    }

    private data class Gateway(
        val host: String,
        val port: Int,
    ) {
        companion object {
            fun parse(rawUrl: String): Gateway {
                val uri = runCatching { URI(rawUrl) }
                    .getOrElse { throw IllegalArgumentException("IM Gateway 地址格式错误") }
                require(uri.scheme.equals("tcp", ignoreCase = true)) { "IM Gateway 必须使用 tcp 协议" }
                val host = uri.host?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("IM Gateway 缺少主机地址")
                require(uri.port in 1..65535) { "IM Gateway 端口不正确" }
                return Gateway(host, uri.port)
            }
        }
    }

    private fun toSyncChat(rows: org.json.JSONArray): WKSyncChat = WKSyncChat().apply {
        conversations = ArrayList<WKSyncConvMsg>().apply {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                add(WKSyncConvMsg().apply {
                    channel_id = row.optString("channel_id")
                    channel_type = row.optInt("channel_type").toByte()
                    last_client_msg_no = row.optString("last_client_msg_no")
                    last_msg_seq = row.optLong("last_msg_seq")
                    offset_msg_seq = row.optInt("offset_msg_seq")
                    timestamp = row.optLong("timestamp")
                    unread = row.optInt("unread")
                    version = row.optLong("version")
                    recents = toSyncRecents(row.optJSONArray("recents"))
                })
            }
        }
    }

    private fun toSyncChannelMessages(row: org.json.JSONObject): WKSyncChannelMsg = WKSyncChannelMsg().apply {
        min_message_seq = row.optLong("start_message_seq")
        max_message_seq = row.optLong("end_message_seq")
        more = row.optInt("more")
        messages = toSyncRecents(row.optJSONArray("messages"))
    }

    private fun toSyncRecents(messages: org.json.JSONArray?): ArrayList<WKSyncRecent> = ArrayList<WKSyncRecent>().apply {
        if (messages == null) return@apply
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val rawPayload = message.optString("payload")
            val payloadText = runCatching {
                String(Base64.decode(rawPayload, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault(rawPayload)
            val payload = HashMap<String, Any>().apply {
                val json = runCatching { org.json.JSONObject(payloadText) }.getOrNull()
                json?.keys()?.forEach { key -> put(key, json.get(key)) }
            }
            add(WKSyncRecent().apply {
                message_id = message.optString("message_idstr", message.optString("message_id"))
                message_seq = message.optInt("message_seq")
                client_msg_no = message.optString("client_msg_no")
                from_uid = message.optString("from_uid")
                channel_id = message.optString("channel_id")
                channel_type = message.optInt("channel_type").toByte()
                timestamp = message.optLong("timestamp")
                setting = message.optInt("setting")
                expire = message.optInt("expire")
                this.payload = payload
            })
        }
    }

    private companion object {
        const val CONNECTION_STATUS_LISTENER = "taotao-im-connection"
        const val NEW_MESSAGE_LISTENER = "taotao-im-message"
        const val MAX_IN_MEMORY_MESSAGES = 300
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}

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

/** 当前应用进程内的私聊文本项；消息持久化与离线同步由悟空 SDK 的本地库负责。 */
data class ImChatMessage(
    val id: String,
    val peerUid: String,
    val content: String,
    val sentAtMillis: Long,
    val isMine: Boolean,
)
