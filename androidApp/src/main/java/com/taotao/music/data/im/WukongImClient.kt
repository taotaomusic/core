package com.taotao.music.data.im

import android.content.Context
import android.util.Base64
import com.taotao.music.data.TencentMusicApi
import com.xinbida.wukongim.WKIM
import com.xinbida.wukongim.entity.WKChannel
import com.xinbida.wukongim.entity.WKChannelType
import com.xinbida.wukongim.entity.WKCMDKeys
import com.xinbida.wukongim.entity.WKMsg
import com.xinbida.wukongim.entity.WKSendOptions
import com.xinbida.wukongim.entity.WKSyncChat
import com.xinbida.wukongim.entity.WKSyncChannelMsg
import com.xinbida.wukongim.entity.WKSyncConvMsg
import com.xinbida.wukongim.entity.WKSyncExtraMsg
import com.xinbida.wukongim.entity.WKSyncRecent
import com.xinbida.wukongim.interfaces.IGetOrSyncHistoryMsgBack
import com.xinbida.wukongim.message.type.WKConnectStatus
import com.xinbida.wukongim.msgmodel.WKTextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

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
    /** SDK 的消息回调在主线程触发；用 IO 队列串行处理，避免阻塞界面和事件乱序。 */
    private val imEventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imEventMutex = Mutex()
    private val notifier = ImNotifier(applicationContext)
    private val _connection = MutableStateFlow(ImConnectionInfo())
    private val _messages = MutableStateFlow(emptyList<ImChatMessage>())
    private val _syncedPeers = MutableStateFlow(emptyList<String>())
    private val _peerNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _syncDetail = MutableStateFlow("等待悟空 IM 同步")
    private val messageStateLock = Any()
    /**
     * 已读与撤回命令本身由悟空持久化。同步原始消息时先恢复这些命令，避免 SDK 只给
     * 普通消息建索引而导致冷启动后状态暂时回退。
     */
    private val confirmedReadMessageIds = ConcurrentHashMap.newKeySet<String>()
    private val confirmedRevokedMessageIds = ConcurrentHashMap.newKeySet<String>()

    /** 页面订阅的连接状态；不会暴露悟空 IM Token。 */
    val connection: StateFlow<ImConnectionInfo> = _connection.asStateFlow()

    /** 当前进程内已收发的文本消息，按私聊对端 UUID 归类。 */
    val messages: StateFlow<List<ImChatMessage>> = _messages.asStateFlow()

    /** 最近同步到的私聊对端，供页面恢复离线会话入口。 */
    val syncedPeers: StateFlow<List<String>> = _syncedPeers.asStateFlow()
    val peerNames: StateFlow<Map<String, String>> = _peerNames.asStateFlow()

    /** 同步诊断，避免网络或协议错误被误显示为「没有消息」。 */
    val syncDetail: StateFlow<String> = _syncDetail.asStateFlow()

    @Volatile
    private var gateway: Gateway? = null

    @Volatile
    private var currentAccountId: Long? = null

    /** 当前可见的私聊。在线新消息只有落在这里时才自动回传已读，避免后台误标已读。 */
    @Volatile
    private var activePeerUid: String? = null

    /** 应用是否在前台；切后台后收到消息时需要显示系统通知。 */
    @Volatile
    private var isAppInForeground: Boolean = true

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
                    .onSuccess { sync ->
                        val rows = sync.conversations
                        val expectedUid = _connection.value.uid
                        if (sync.uid != null && expectedUid != null && sync.uid != expectedUid) {
                            _syncDetail.value = "身份不一致：连接 ${expectedUid.take(8)}，同步 ${sync.uid.take(8)}"
                            callback?.onBack(null)
                            return@onSuccess
                        }
                        val restored = mergeSyncedConversations(rows)
                        _syncDetail.value = "已同步 ${rows.length()} 个会话、${restored} 条消息"
                        callback?.onBack(toSyncChat(rows))
                    }
                    .onFailure { error ->
                        _syncDetail.value = "同步失败：${error.message ?: error.javaClass.simpleName}"
                        callback?.onBack(null)
                    }
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
            if (received.isNotEmpty()) {
                // SDK 可能复用回调列表；切到后台队列前先复制容器，避免异步读取被修改。
                enqueueImEvent { handleNewMessages(received.toList()) }
            }
        }
        // 自己发送的消息不会走“新消息”监听；以 SDK 回调提供的 clientMsgNo 为准，不能保留自造的 local-* ID。
        wkIm.msgManager.addOnSendMsgCallback(SEND_MESSAGE_LISTENER) { message ->
            enqueueImEvent { handleSentMessage(message) }
        }
        wkIm.msgManager.addOnRefreshMsgListener(REFRESH_MESSAGE_LISTENER) { message, _ ->
            enqueueImEvent { handleRefreshedMessage(message) }
        }
        // 悟空通过 TCP 下发原生撤回命令；先立即更新当前界面，离线端则在下次同步时恢复状态。
        wkIm.getCMDManager().addCmdListener(REVOKE_COMMAND_LISTENER) { command ->
            enqueueImEvent { handleCommand(command) }
        }
    }

    private fun enqueueImEvent(block: () -> Unit) {
        imEventScope.launch {
            imEventMutex.withLock { block() }
        }
    }

    private fun handleNewMessages(received: List<WKMsg>) {
        val currentUid = _connection.value.uid?.trim()?.lowercase() ?: return
        val newlyReadIds = ArrayList<String>()
        val added = received.mapNotNull { message ->
            val fromUid = message.fromUID?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val content = message.baseContentMsgModel?.displayContent?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val peerUid = if (fromUid == currentUid) message.channelID else fromUid
            if (fromUid != currentUid) {
                if (!isAppInForeground) {
                    val peerName = _peerNames.value[peerUid] ?: peerUid.take(8)
                    notifier.showMessage(peerName, content)
                }
                if (isAppInForeground && peerUid == activePeerUid) {
                    (message.messageID ?: message.clientMsgNO)?.takeIf { it.isNotBlank() }
                        ?.let(newlyReadIds::add)
                }
            }
            toChatMessage(message, currentUid, content, peerUid)
        }
        mergeMessages(added)
        // 双方都在线时历史加载不会再次发生；这里对正在看的会话即时确认已读。
        val activePeer = activePeerUid
        if (activePeer != null && isAppInForeground) sendReadReceipt(activePeer, newlyReadIds)
    }

    private fun handleSentMessage(message: WKMsg) {
        val currentUid = _connection.value.uid?.trim()?.lowercase() ?: return
        val content = message.baseContentMsgModel?.displayContent?.takeIf { it.isNotBlank() } ?: return
        mergeMessage(toChatMessage(message, currentUid, content, message.channelID))
    }

    private fun handleRefreshedMessage(message: WKMsg) {
        val currentUid = _connection.value.uid?.trim()?.lowercase() ?: return
        val content = message.baseContentMsgModel?.displayContent?.takeIf { it.isNotBlank() } ?: return
        mergeMessage(toChatMessage(message, currentUid, content, message.channelID))
    }

    private fun toChatMessage(message: WKMsg, currentUid: String, content: String, peerUid: String): ImChatMessage =
        ImChatMessage(
            id = message.messageID ?: message.clientMsgNO,
            clientMsgNo = message.clientMsgNO ?: message.messageID,
            peerUid = peerUid,
            content = if (message.remoteExtra?.revoke == 1) "消息已撤回" else content,
            sentAtMillis = message.timestamp * 1000,
            messageSeq = message.messageSeq,
            isMine = message.fromUID?.trim()?.lowercase() == currentUid || message.fromUID.isNullOrBlank(),
            isRead = message.remoteExtra?.readed == 1 || isReadConfirmed(message.messageID, message.clientMsgNO),
            isRevoked = message.remoteExtra?.revoke == 1,
        )

    private fun handleCommand(command: com.xinbida.wukongim.entity.WKCMD) {
        when (command.cmdKey) {
            WKCMDKeys.wk_messageRevoke -> {
                val parameters = command.paramJsonObject ?: return
                markMessageRevoked(
                    messageId = parameters.optString("message_id"),
                    clientMsgNo = parameters.optString("client_msg_no"),
                )
            }
            READ_RECEIPT_COMMAND -> {
                val parameters = command.paramJsonObject ?: return
                val readerUid = parameters.optString("reader_uid").trim().lowercase()
                applyReadReceipt(readerUid, parameters.optJSONArray("message_ids"))
            }
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
        if (currentAccountId != accountId) {
            confirmedReadMessageIds.clear()
            confirmedRevokedMessageIds.clear()
        }
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

        wkIm.msgManager.sendWithOptions(
            WKTextContent(normalizedContent),
            WKChannel(normalizedPeerUid, WKChannelType.PERSONAL),
            WKSendOptions().apply { setting.receipt = 1 },
        )
    }

    fun revokeMessage(message: ImChatMessage) {
        require(message.isMine) { "只能撤回自己发送的消息" }
        require(message.id.isNotBlank() || message.clientMsgNo.isNotBlank()) { "消息尚未送达，暂不能撤回" }
        syncScope.launch {
            runCatching { api.revokeImMessage(message.peerUid, message.id, message.clientMsgNo) }
                .onSuccess { markMessageRevoked(message.id, message.clientMsgNo) }
                .onFailure { error -> _syncDetail.value = "撤回失败：${error.message ?: "网络错误"}" }
        }
    }

    /**
     * 打开某个私聊时优先读取悟空 SDK 的本地消息库。SDK 的会话增量游标已经推进后，
     * 服务端会正确返回空增量；这里不能把空增量误当成「没有历史消息」。
     */
    fun loadRecentMessages(peerUid: String) {
        val normalizedPeerUid = peerUid.trim().lowercase()
        if (!UUID_PATTERN.matches(normalizedPeerUid)) return
        activePeerUid = normalizedPeerUid
        syncScope.launch {
            runCatching { api.imContacts(listOf(normalizedPeerUid)) }.onSuccess { contacts ->
                _peerNames.value = _peerNames.value + contacts.associate { it.uid to it.nickname }
            }
        }
        syncScope.launch { runCatching { api.markImConversationRead(normalizedPeerUid) } }

        wkIm.msgManager.getOrSyncHistoryMessages(
            normalizedPeerUid,
            WKChannelType.PERSONAL,
            0,
            false,
            0,
            HISTORY_PAGE_SIZE,
            0,
            object : IGetOrSyncHistoryMsgBack {
                override fun onSyncing() = Unit

                override fun onResult(rows: List<com.xinbida.wukongim.entity.WKMsg>) {
                    val currentUid = _connection.value.uid?.trim()?.lowercase() ?: return
                    val restored = rows.mapNotNull { message ->
                        val fromUid = message.fromUID?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val content = message.baseContentMsgModel?.displayContent
                            ?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val messagePeerUid = if (fromUid == currentUid) message.channelID else fromUid
                        if (!UUID_PATTERN.matches(messagePeerUid)) return@mapNotNull null
                        ImChatMessage(
                            id = message.messageID ?: message.clientMsgNO,
                            clientMsgNo = message.clientMsgNO ?: message.messageID,
                            peerUid = messagePeerUid,
                            content = if (message.remoteExtra?.revoke == 1) "消息已撤回" else content,
                            sentAtMillis = message.timestamp * 1000,
                            messageSeq = message.messageSeq,
                            isMine = fromUid == currentUid,
                            isRead = message.remoteExtra?.readed == 1 || isReadConfirmed(message.messageID, message.clientMsgNO),
                            isRevoked = message.remoteExtra?.revoke == 1,
                        )
                    }
                    if (restored.isEmpty()) return
                    mergeMessages(restored)
                    _syncedPeers.value = (_syncedPeers.value + normalizedPeerUid).distinct().sorted()
                    sendReadReceipt(normalizedPeerUid, restored.filterNot { it.isMine || it.isRevoked }.map { it.id })
                }
            },
        )
    }

    fun loadPeerNames(peerUids: List<String>) {
        val normalized = peerUids.map { it.trim().lowercase() }
            .filter { UUID_PATTERN.matches(it) }
            .distinct()
            .take(50)
        if (normalized.isEmpty()) return
        syncScope.launch {
            runCatching { api.imContacts(normalized) }.onSuccess { contacts ->
                _peerNames.value = _peerNames.value + contacts.associate { it.uid to it.nickname }
            }
        }
    }

    /** 对端打开会话后，以悟空内部消息回传实际已读的消息编号。 */
    private fun sendReadReceipt(peerUid: String, messageIds: List<String>) {
        val readerUid = _connection.value.uid ?: return
        if (messageIds.isEmpty()) return
        wkIm.msgManager.sendWithOptions(
            ImInternalCommandContent(
                READ_RECEIPT_COMMAND,
                JSONObject()
                    .put("reader_uid", readerUid)
                    .put("message_ids", JSONArray(messageIds.take(MAX_READ_RECEIPT_IDS))),
            ),
            WKChannel(peerUid, WKChannelType.PERSONAL),
            WKSendOptions().apply {
                // 保持在原私聊频道，READ 同步模式能随会话历史恢复；sync_once 的命令频道
                // 仅由 WRITE 同步模式拉取，当前客户端不会走那条链路。
                header.redDot = false
                header.syncOnce = false
            },
        )
    }

    /** 聊天页切换或离开时更新可见会话，避免应用在后台把新消息误标已读。 */
    fun setActivePeer(peerUid: String?) {
        activePeerUid = peerUid?.trim()?.lowercase()?.takeIf(UUID_PATTERN::matches)
    }

    /** 设置应用前后台状态，用于决定是否显示系统通知。 */
    fun setAppForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
    }

    /** 退出账号或会话彻底失效时停止重连并清除 SDK 中保留的 Token。 */
    fun signOut() {
        gateway = null
        currentAccountId = null
        _connection.value = ImConnectionInfo()
        synchronized(messageStateLock) { _messages.value = emptyList() }
        _syncedPeers.value = emptyList()
        _peerNames.value = emptyMap()
        activePeerUid = null
        confirmedReadMessageIds.clear()
        confirmedRevokedMessageIds.clear()
        _syncDetail.value = "等待悟空 IM 同步"
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

    /**
     * 悟空 SDK 会把同步数据落入自己的数据库，但不会触发 addOnNewMsgListener。当前聊天页
     * 展示的是状态流，因此这里同步更新内存投影，避免「已同步但页面空白」。
     */
    private fun mergeSyncedConversations(rows: org.json.JSONArray): Int {
        val currentUid = _connection.value.uid?.trim()?.lowercase() ?: return 0
        // 先扫描整批内部命令。会话最近消息的顺序不是状态顺序，若先建气泡再遇到回执，
        // 冷启动时仍可能短暂甚至永久显示为未读。
        for (rowIndex in 0 until rows.length()) {
            val recents = rows.optJSONObject(rowIndex)?.optJSONArray("recents") ?: continue
            for (messageIndex in 0 until recents.length()) {
                recents.optJSONObject(messageIndex)?.let(::consumePersistedInternalCommand)
            }
        }
        val restored = buildList {
            for (rowIndex in 0 until rows.length()) {
                val row = rows.optJSONObject(rowIndex) ?: continue
                val channelId = row.optString("channel_id").trim().lowercase()
                if (!UUID_PATTERN.matches(channelId) || row.optInt("channel_type") != WKChannelType.PERSONAL.toInt()) continue
                val recents = row.optJSONArray("recents") ?: continue
                for (messageIndex in 0 until recents.length()) {
                    val message = recents.optJSONObject(messageIndex) ?: continue
                    if (consumePersistedInternalCommand(message)) continue
                    val fromUid = message.optString("from_uid").trim().lowercase()
                    val peerUid = if (fromUid == currentUid) channelId else fromUid
                    if (!UUID_PATTERN.matches(peerUid)) continue
                    val content = payloadOf(message).optString("content").trim()
                    if (content.isBlank()) continue
                    val revoked = isRevokedConfirmed(message)
                    add(ImChatMessage(
                        id = message.optString("message_idstr", message.optString("client_msg_no")),
                        clientMsgNo = message.optString("client_msg_no", message.optString("message_idstr")),
                        peerUid = peerUid,
                        content = if (revoked) "消息已撤回" else content,
                        sentAtMillis = message.optLong("timestamp") * 1000,
                        messageSeq = message.optInt("message_seq"),
                        isMine = fromUid == currentUid,
                        isRead = fromUid == currentUid && isReadConfirmed(message),
                        isRevoked = revoked,
                    ))
                }
            }
        }
        if (restored.isEmpty()) return 0
        mergeMessages(restored)
        _syncedPeers.value = ( _syncedPeers.value + restored.map { it.peerUid } ).distinct().sorted()
        return restored.size
    }

    private fun toSyncRecents(messages: org.json.JSONArray?): ArrayList<WKSyncRecent> = ArrayList<WKSyncRecent>().apply {
        if (messages == null) return@apply
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val payload = payloadMapOf(message)
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
                val extra = message.optJSONObject("message_extra") ?: message
                revoke = extra.optInt("revoke")
                revoker = extra.optString("revoker")
                unread_count = extra.optInt("unread_count")
                readed_count = extra.optInt("readed_count")
                readed = extra.optInt("readed")
                extra_version = extra.optLong("extra_version")
                message_extra = toSyncExtra(message.optJSONObject("message_extra"))
                this.payload = payload
            })
        }
    }

    private fun payloadMapOf(message: org.json.JSONObject): HashMap<String, Any> = HashMap<String, Any>().apply {
        val json = payloadOf(message)
        json.keys().forEach { key -> put(key, json.get(key)) }
    }

    private fun payloadOf(message: org.json.JSONObject): org.json.JSONObject {
        val rawPayload = message.optString("payload")
        val payloadText = runCatching {
            String(Base64.decode(rawPayload, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(rawPayload)
        return runCatching { org.json.JSONObject(payloadText) }.getOrDefault(org.json.JSONObject())
    }

    /** 将产品 API 的 message_extra 完整交给 SDK，本地消息库才能在冷启动时恢复状态。 */
    private fun toSyncExtra(extra: org.json.JSONObject?): WKSyncExtraMsg? {
        if (extra == null) return null
        return WKSyncExtraMsg().apply {
            message_id_str = extra.optString("message_id_str", extra.optString("message_id"))
            message_id = message_id_str
            revoke = extra.optInt("revoke")
            revoker = extra.optString("revoker")
            unread_count = extra.optInt("unread_count")
            readed_count = extra.optInt("readed_count")
            readed = extra.optInt("readed")
            is_mutual_deleted = extra.optInt("is_mutual_deleted")
            extra_version = extra.optLong("extra_version")
        }
    }

    /**
     * type=99 的内部消息不会进入聊天气泡。它仍会随悟空的历史分页返回，所以必须在
     * 建立 UI 投影前消费它；否则发送方杀进程后会丢失已经收到的已读回执。
     */
    private fun consumePersistedInternalCommand(message: org.json.JSONObject): Boolean {
        val payload = payloadOf(message)
        val command = payload.optString("cmd")
        val parameters = payload.optJSONObject("param") ?: return false
        return when (command) {
            READ_RECEIPT_COMMAND -> {
                applyReadReceipt(parameters.optString("reader_uid").trim().lowercase(), parameters.optJSONArray("message_ids"))
                true
            }
            WKCMDKeys.wk_messageRevoke -> {
                markMessageRevoked(parameters.optString("message_id"), parameters.optString("client_msg_no"))
                true
            }
            else -> false
        }
    }

    private fun applyReadReceipt(readerUid: String, messageIds: org.json.JSONArray?) {
        if (readerUid.isBlank() || messageIds == null) return
        val readIds = buildSet {
            for (index in 0 until messageIds.length()) {
                messageIds.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        if (readIds.isEmpty()) return
        confirmedReadMessageIds += readIds
        persistMessageExtra(readerUid, readIds, readed = true, revoked = false)
        synchronized(messageStateLock) {
            _messages.value = _messages.value.map { message ->
                if (message.isMine && message.peerUid == readerUid &&
                    (message.id in readIds || message.clientMsgNo in readIds)
                ) {
                    message.copy(isRead = true)
                } else message
            }
        }
    }

    private fun isReadConfirmed(message: org.json.JSONObject): Boolean {
        val extra = message.optJSONObject("message_extra")
        return extra?.optInt("readed") == 1 ||
            message.optInt("readed") == 1 ||
            messageIdsOf(message).any(confirmedReadMessageIds::contains)
    }

    private fun isReadConfirmed(messageId: String?, clientMsgNo: String?): Boolean =
        listOf(messageId, clientMsgNo).any { it != null && it in confirmedReadMessageIds }

    private fun isRevokedConfirmed(message: org.json.JSONObject): Boolean {
        val extra = message.optJSONObject("message_extra")
        return extra?.optInt("revoke") == 1 ||
            message.optInt("revoke") == 1 ||
            messageIdsOf(message).any(confirmedRevokedMessageIds::contains)
    }

    private fun messageIdsOf(message: org.json.JSONObject): Set<String> = setOf(
        message.optString("message_idstr"),
        message.optString("message_id"),
        message.optString("client_msg_no"),
    ).filter { it.isNotBlank() }.toSet()

    /**
     * 合并悟空的推送、历史页和刷新事件。网络事件可以乱序到达，不能用后来的旧快照
     * 把已读或撤回状态倒退回去；两种状态一旦在悟空事件中确认，就只能保持或增强。
     */
    private fun mergeMessages(incoming: List<ImChatMessage>) {
        if (incoming.isEmpty()) return
        synchronized(messageStateLock) {
            val merged = _messages.value.toMutableList()
            incoming.forEach { candidate ->
                val index = merged.indexOfFirst { existing -> sameMessage(existing, candidate.id, candidate.clientMsgNo) }
                if (index < 0) {
                    merged += candidate
                } else {
                    val previous = merged[index]
                    val revoked = previous.isRevoked || candidate.isRevoked
                    merged[index] = candidate.copy(
                        id = candidate.id.ifBlank { previous.id },
                        clientMsgNo = candidate.clientMsgNo.ifBlank { previous.clientMsgNo },
                        content = if (revoked) "消息已撤回" else candidate.content,
                        messageSeq = candidate.messageSeq.takeIf { it > 0 } ?: previous.messageSeq,
                        isRead = previous.isRead || candidate.isRead,
                        isRevoked = revoked,
                    )
                }
            }
            val nextMessages = merged.sortedBy { it.sentAtMillis }.takeLast(MAX_IN_MEMORY_MESSAGES)
            // 悟空的刷新/同步回调可能重复投递同一条消息；没有实际变化时不要通知 Compose。
            if (nextMessages != _messages.value) _messages.value = nextMessages
        }
    }

    private fun mergeMessage(message: ImChatMessage) = mergeMessages(listOf(message))

    /** 悟空不同链路可能只携带 message_id 或 client_msg_no，二者都必须能命中。 */
    private fun markMessageRevoked(messageId: String, clientMsgNo: String) {
        if (messageId.isBlank() && clientMsgNo.isBlank()) return
        listOf(messageId, clientMsgNo).filter { it.isNotBlank() }.forEach(confirmedRevokedMessageIds::add)

        // 先写入悟空 SDK 本地库，确保消息存在于数据库中
        val resolvedMessageId = messageId.ifBlank {
            wkIm.msgManager.getWithClientMsgNO(clientMsgNo)?.messageID.orEmpty()
        }
        if (resolvedMessageId.isNotBlank()) {
            wkIm.msgManager.updateContentAndRefresh(resolvedMessageId, "消息已撤回", true)
        }

        // SDK 写入完成后，再持久化 extra 表（此时 getWithMessageID 才能成功）
        val peerUid = synchronized(messageStateLock) {
            _messages.value.firstOrNull { sameMessage(it, messageId, clientMsgNo) }?.peerUid
        }
        if (peerUid != null) {
            persistMessageExtra(peerUid, listOf(messageId, clientMsgNo), readed = false, revoked = true)
        }

        synchronized(messageStateLock) {
            _messages.value = _messages.value.map { message ->
                if (sameMessage(message, messageId, clientMsgNo)) {
                    message.copy(content = "消息已撤回", isRevoked = true)
                } else message
            }
        }
    }

    private fun sameMessage(message: ImChatMessage, messageId: String, clientMsgNo: String): Boolean =
        (messageId.isNotBlank() && (message.id == messageId || message.clientMsgNo == messageId)) ||
            (clientMsgNo.isNotBlank() && (message.id == clientMsgNo || message.clientMsgNo == clientMsgNo))

    /**
     * 自定义回执的事实来源仍是悟空内部命令；命令到达后写入 SDK 的 remoteExtra 表，
     * 这样 SDK 在冷启动读取历史时会返回 readed/revoke，而不是只保留一份页面内存。
     */
    private fun persistMessageExtra(peerUid: String, identities: Collection<String>, readed: Boolean, revoked: Boolean) {
        val extras = identities.asSequence().filter { it.isNotBlank() }.mapNotNull { identity ->
            val message = wkIm.msgManager.getWithMessageID(identity)
                ?: wkIm.msgManager.getWithClientMsgNO(identity)
                ?: return@mapNotNull null
            val messageId = message.messageID ?: return@mapNotNull null
            WKSyncExtraMsg().apply {
                message_id = messageId
                message_id_str = messageId
                this.readed = if (readed) 1 else message.remoteExtra?.readed ?: 0
                revoke = if (revoked) 1 else message.remoteExtra?.revoke ?: 0
                extra_version = System.currentTimeMillis()
            }
        }.distinctBy { it.message_id }.toList()
        if (extras.isNotEmpty()) {
            wkIm.msgManager.saveRemoteExtraMsg(WKChannel(peerUid, WKChannelType.PERSONAL), extras)
        }
    }

    private companion object {
        const val CONNECTION_STATUS_LISTENER = "taotao-im-connection"
        const val NEW_MESSAGE_LISTENER = "taotao-im-message"
        const val SEND_MESSAGE_LISTENER = "taotao-im-send"
        const val REFRESH_MESSAGE_LISTENER = "taotao-im-refresh"
        const val REVOKE_COMMAND_LISTENER = "taotao-im-revoke"
        const val HISTORY_PAGE_SIZE = 50
        const val MAX_IN_MEMORY_MESSAGES = 300
        const val READ_RECEIPT_COMMAND = "taotao.messageRead"
        const val MAX_READ_RECEIPT_IDS = 100
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
