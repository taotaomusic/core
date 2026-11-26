package com.taotao.music.crypto

import com.taotao.music.AppHttp
import com.taotao.music.data.SessionExpiredException
import com.taotao.music.data.TokenProvider
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64

/**
 * 安卓侧传输加密的高层封装：管理惰性握手、会话生命周期与请求/响应加解密。
 *
 * 设计要点：
 * - **失败关闭**：握手或封帧失败时先清理状态、重新获取 PSK 并有限重试；仍失败则抛出
 *   [CryptoConnectionException]，受保护的业务请求绝不回退明文。
 * - **线程安全**：句柄与会话状态用 [lock] 串行化；网络握手在锁内完成，简单可靠
 *   （握手很短，且并发首个请求本就该等同一次握手）。
 *
 * PSK 由**后端动态下发**（`GET /api/v1/crypto/psk`，需登录令牌），客户端不再内嵌密钥：
 * 两端用同一把（后端那把），密钥天然一致；`.so` 混淆强度有限、易被提取的问题也随之消除。
 * **安全权衡**：PSK 经 HTTPS + 登录令牌下发，此层不再独立于 TLS，主要提供设备绑定与抗篡改。
 *
 * @param endpoint 后端根地址，如 `https://music.xydaigua.cn`。
 * @param deviceIdProvider 硬件设备号来源（[com.taotao.music.data.crypto.HardwareDeviceId]）。
 * @param tokenProvider 登录令牌来源；PSK 请求被 401 拒绝时通过同一个会话续期一次。
 */
class CryptoTransport internal constructor(
    private val endpoint: String,
    private val deviceIdProvider: () -> String,
    private val tokenProvider: TokenProvider,
    private val client: CryptoClient = JniCryptoClient,
    private val deviceInfo: String = defaultDeviceInfo(),
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val reportFailure: (String, Throwable) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {
    /** 单次可加密请求的产物：`header` 进 `X-Taotao-Crypto`，`frame` 作为请求体（GET 可忽略）。 */
    data class Sealed(val header: String, val frame: ByteArray)

    private val lock = Any()

    /** 客户端句柄；0 表示尚未创建或已释放。 */
    private var handle: Long = 0L

    /** 后端下发并缓存的 PSK；null 表示尚未取到。 */
    private var pskId: String? = null
    private var pskHex: String? = null

    /** 加密链路是否具备可用前提：库已加载、协议 >= 2。PSK 在握手时向后端拉取。 */
    val enabled: Boolean
        get() = client.available &&
            runCatching { client.nativeVersion() >= 2 }.getOrDefault(false)

    /**
     * 为一次请求准备加密头与帧。首次失败后清理句柄和 PSK，稍后重新引导一次，
     * 避免冷启动网络抖动或服务端密钥轮换直接打断业务；失败期间不发送业务请求。
     *
     * GET 等无体请求只用 [Sealed.header]（把帧作为体会被 HttpURLConnection 转成 POST）；
     * 服务端对空体按空明文处理，响应仍会被加密。
     */
    fun seal(method: String, path: String, body: ByteArray): Sealed = synchronized(lock) {
        if (!enabled) {
            val cause = CryptoException("本地加密组件不可用或协议版本不兼容")
            reportFailure("无法准备安全连接", cause)
            throw CryptoConnectionException(cause)
        }
        repeat(MAX_PREPARE_ATTEMPTS) { attempt ->
            try {
                ensureSessionLocked()
                val aad = client.nativeAad(method, path)
                val frame = client.clientSeal(handle, aad, body, now())
                val header = client.clientHeaderForFrame(handle, frame)
                return@synchronized Sealed(header, frame)
            } catch (e: SessionExpiredException) {
                resetSessionLocked()
                throw e
            } catch (e: Exception) {
                resetSessionLocked()
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CryptoConnectionException(e)
                }
                if (attempt == MAX_PREPARE_ATTEMPTS - 1) {
                    reportFailure("安全连接准备失败，已停止本次请求", e)
                    throw CryptoConnectionException(e)
                }
                reportFailure("安全连接尚未就绪，将重新获取密钥并重试", e)
                try {
                    pause(PREPARE_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CryptoConnectionException(e)
                }
            }
        }
        error("连接准备重试次数配置错误")
    }

    /** 解密响应帧为明文。失败返回 null（调用方回退用原始字节或报错）。 */
    fun openResponse(method: String, path: String, frame: ByteArray): ByteArray? = synchronized(lock) {
        if (handle == 0L) return null
        return try {
            val aad = client.nativeAad(method, path)
            client.clientOpen(handle, aad, frame, now())
        } catch (e: Exception) {
            reportFailure("响应解密失败", e)
            null
        }
    }

    /**
     * 使当前会话失效，强制下次 [seal] 重新握手。
     * 服务端返回 409（会话失效，code 4091）时调用。
     */
    fun invalidateSession() = synchronized(lock) {
        // 服务端重启会同时丢失会话与随机 PSK，只重握手会一直使用旧密钥而无法恢复。
        resetSessionLocked()
    }

    // ---- 内部 ----

    /** 确保存在一条可用会话；无会话或需 rekey 时执行一次握手。必须在 [lock] 内调用。 */
    private fun ensureSessionLocked() {
        if (handle != 0L && !client.clientNeedsRekey(handle, now())) return
        // needs rekey 或无句柄：重建。
        freeHandleLocked()

        // 取后端下发的 PSK（首次拉取后缓存），用它构造客户端引擎。
        ensurePskLocked()
        val device = deviceIdProvider()
        val newHandle = client.clientNew(pskId!!, pskHex!!, device)
        if (newHandle == 0L) throw CryptoException("创建客户端引擎失败")
        try {
            val hello = client.clientHandshake(newHandle, now())
            val serverHello = postHandshake(hello, device)
            if (!client.clientFinish(newHandle, serverHello, now())) {
                throw CryptoException("finish 未能建立会话")
            }
            // 握手完整成功后才发布句柄，失败的半成品不能被后续请求复用。
            handle = newHandle
        } finally {
            if (handle != newHandle) client.clientFree(newHandle)
        }
    }

    /** 确保已从后端取到 PSK（缓存）。必须在 [lock] 内调用。 */
    private fun ensurePskLocked() {
        if (pskId != null && pskHex != null) return
        var token = tokenProvider.validToken() ?: throw SessionExpiredException()
        repeat(2) { attempt ->
            val connection = connectionFactory(URL("$endpoint/api/v1/crypto/psk")).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("User-Agent", AppHttp.USER_AGENT)
                setRequestProperty("X-Taotao-Device", deviceInfo)
            }
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    connection.errorStream?.close()
                    if (attempt == 1) throw SessionExpiredException()
                    token = tokenProvider.renewToken(token) ?: throw SessionExpiredException()
                } else {
                    if (code !in 200..299) {
                        connection.errorStream?.close()
                        throw CryptoException("获取 PSK 失败：HTTP $code")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    // 后端全局 EnvelopeInterceptor 把响应包成 {code,message,data}，取 data 层。
                    val json = JSONObject(body).let { it.optJSONObject("data") ?: it }
                    val id = json.optString("pskId")
                    val hex = json.optString("pskHex")
                    if (id.isBlank() || !hex.matches(Regex("[0-9a-fA-F]{64}"))) {
                        throw CryptoException("PSK 响应缺少有效字段")
                    }
                    pskId = id
                    pskHex = hex
                    return
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** 释放句柄。必须在 [lock] 内调用。 */
    private fun freeHandleLocked() {
        if (handle != 0L) {
            runCatching { client.clientFree(handle) }
            handle = 0L
        }
    }

    /** 清理失败状态与可能已轮换的密钥，下一次尝试必须重新引导。必须在 [lock] 内调用。 */
    private fun resetSessionLocked() {
        freeHandleLocked()
        pskId = null
        pskHex = null
    }

    /** POST 明文握手：`{clientHello, deviceId}` → 取回 `serverHello`。握手本身不加密。 */
    private fun postHandshake(clientHello: ByteArray, deviceId: String): ByteArray {
        val payload = JSONObject()
            .put("clientHello", Base64.encode(clientHello))
            .put("deviceId", deviceId)
            .toString()

        val connection = connectionFactory(URL("$endpoint/api/v1/crypto/handshake")).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", AppHttp.USER_AGENT)
            setRequestProperty("X-Taotao-Device", deviceInfo)
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                runCatching { connection.errorStream?.close() }
                throw CryptoException("握手请求失败：HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // 同 /crypto/psk：响应经 EnvelopeInterceptor 包成 {code,message,data}。
            val json = JSONObject(body).let { it.optJSONObject("data") ?: it }
            val serverHelloB64 = json.optString("serverHello")
            if (serverHelloB64.isBlank()) throw CryptoException("握手响应缺少 serverHello")
            return Base64.decode(serverHelloB64)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "CryptoTransport"
        private const val MAX_PREPARE_ATTEMPTS = 2
        private const val PREPARE_RETRY_DELAY_MS = 300L

        /** 明文机型/系统版本头值只保留可打印 ASCII。 */
        private fun defaultDeviceInfo(): String {
            val raw = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            return raw.filter { it.code in 0x20..0x7e }.take(120).ifBlank { "unknown-device" }
        }
    }
}

