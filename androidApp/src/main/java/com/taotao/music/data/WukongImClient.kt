package com.taotao.music.data

import android.content.Context
import com.xinbida.wukongim.WKIM
import com.xinbida.wukongim.message.type.WKConnectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URI

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
    private val _connection = MutableStateFlow(ImConnectionInfo())

    /** 页面订阅的连接状态；不会暴露悟空 IM Token。 */
    val connection: StateFlow<ImConnectionInfo> = _connection.asStateFlow()


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
        // SDK 认证成功后会进入「同步最近会话」阶段，并等待业务服务回调。桃桃当前尚未提供
        // 会话列表同步接口；若不显式回调空结果，SDK 会永远停在 syncMsg 状态，页面看起来
        // 就是「连接中」，尽管 Gateway 已经握手成功。聊天正文以业务服务的游标同步为准。
        wkIm.conversationManager.addOnSyncConversationListener { _, _, _, callback ->
            callback?.onBack(null)
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

    /** 退出账号或会话彻底失效时停止重连并清除 SDK 中保留的 Token。 */
    fun signOut() {
        gateway = null
        currentAccountId = null
        _connection.value = ImConnectionInfo()
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

    private companion object {
        const val CONNECTION_STATUS_LISTENER = "taotao-im-connection"
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
