package com.taotao.music.crypto

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 安卓侧传输加密的高层封装：管理惰性握手、会话生命周期与请求/响应加解密。
 *
 * 设计要点：
 * - **优雅降级**：native 库缺失、内嵌的是占位 PSK、或握手失败时 [enabled] 为 false
 *   （或单次调用返回 null），调用方一律回退明文，绝不因加密崩溃阻断功能。
 * - **灰度**：本类可用不代表一定加密——真正是否加密由调用方（[com.taotao.music.data.TencentMusicApi]）
 *   的开关 + 接口白名单决定。这里只提供能力。
 * - **线程安全**：句柄与会话状态用 [lock] 串行化；网络握手在锁内完成，简单可靠
 *   （握手很短，且并发首个请求本就该等同一次握手）。
 *
 * PSK 由**后端动态下发**（`GET /api/v1/crypto/psk`，需登录令牌），客户端不再内嵌密钥：
 * 两端用同一把（后端那把），密钥天然一致；`.so` 混淆强度有限、易被提取的问题也随之消除。
 * **安全权衡**：PSK 经 HTTPS + 登录令牌下发，此层不再独立于 TLS，主要提供设备绑定与抗篡改。
 *
 * @param endpoint 后端根地址，如 `https://music.xydaigua.cn`。
 * @param deviceIdProvider 硬件设备号来源（[com.taotao.music.data.crypto.HardwareDeviceId]）。
 * @param tokenProvider 取当前登录访问令牌（Bearer）；无令牌时返回 null，则不下发 PSK、回退明文。
 */
class CryptoTransport(
    private val endpoint: String,
    private val deviceIdProvider: () -> String,
    private val tokenProvider: () -> String?,
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
        get() = NativeCrypto.available &&
            runCatching { NativeCrypto.nativeVersion() >= 2 }.getOrDefault(false)

    // PLACEHOLDER_APPEND
    /**
     * 为一次请求准备加密头与帧。会在需要时惰性握手；不可用或握手失败返回 null，
     * 调用方据此回退明文。
     *
     * GET 等无体请求只用 [Sealed.header]（把帧作为体会被 HttpURLConnection 转成 POST）；
     * 服务端对空体按空明文处理，响应仍会被加密。
     */
    fun seal(method: String, path: String, body: ByteArray): Sealed? = synchronized(lock) {
        if (!enabled) return null
        return try {
            ensureSessionLocked()
            val now = System.currentTimeMillis()
            val aad = NativeCrypto.nativeAad(method, path)
            val frame = NativeCrypto.clientSeal(handle, aad, body, now)
            val header = NativeCrypto.clientHeaderForFrame(handle, frame)
            Sealed(header, frame)
        } catch (e: Throwable) {
            Log.w(TAG, "封帧失败，本次回退明文：${e.message}")
            null
        }
    }

    /** 解密响应帧为明文。失败返回 null（调用方回退用原始字节或报错）。 */
    fun openResponse(method: String, path: String, frame: ByteArray): ByteArray? = synchronized(lock) {
        if (handle == 0L) return null
        return try {
            val aad = NativeCrypto.nativeAad(method, path)
            NativeCrypto.clientOpen(handle, aad, frame, System.currentTimeMillis())
        } catch (e: Throwable) {
            Log.w(TAG, "响应解密失败：${e.message}")
            null
        }
    }

    /**
     * 使当前会话失效，强制下次 [seal] 重新握手。
     * 服务端返回 409（会话失效，code 4091）时调用。
     */
    fun invalidateSession() = synchronized(lock) {
        freeHandleLocked()
    }

    // ---- 内部 ----

    /** 确保存在一条可用会话；无会话或需 rekey 时执行一次握手。必须在 [lock] 内调用。 */
    private fun ensureSessionLocked() {
        val now = System.currentTimeMillis()
        if (handle != 0L && !NativeCrypto.clientNeedsRekey(handle, now)) return
        // needs rekey 或无句柄：重建。
        freeHandleLocked()

        // 取后端下发的 PSK（首次拉取后缓存），用它构造客户端引擎。
        ensurePskLocked()
        val device = deviceIdProvider()
        val newHandle = NativeCrypto.clientNew(pskId!!, pskHex!!, device)
        if (newHandle == 0L) throw CryptoException("创建客户端引擎失败")
        handle = newHandle

        val hello = NativeCrypto.clientHandshake(handle, now)
        val serverHello = postHandshake(hello, device)
        if (!NativeCrypto.clientFinish(handle, serverHello, now)) {
            throw CryptoException("finish 未能建立会话")
        }
    }

    /** 确保已从后端取到 PSK（缓存）。必须在 [lock] 内调用。 */
    private fun ensurePskLocked() {
        if (pskId != null && pskHex != null) return
        val token = tokenProvider() ?: throw CryptoException("未登录，无法获取 PSK")
        val connection = (URL("$endpoint/api/v1/crypto/psk").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            runCatching { connection.errorStream?.close() }
            throw CryptoException("获取 PSK 失败：HTTP $code")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val id = json.optString("pskId")
        val hex = json.optString("pskHex")
        if (id.isBlank() || hex.isBlank()) throw CryptoException("PSK 响应缺少字段")
        pskId = id
        pskHex = hex
    }

    /** 释放句柄。必须在 [lock] 内调用。 */
    private fun freeHandleLocked() {
        if (handle != 0L) {
            runCatching { NativeCrypto.clientFree(handle) }
            handle = 0L
        }
    }

    /** POST 明文握手：`{clientHello, deviceId}` → 取回 `serverHello`。握手本身不加密。 */
    private fun postHandshake(clientHello: ByteArray, deviceId: String): ByteArray {
        val payload = JSONObject()
            .put("clientHello", Base64.encodeToString(clientHello, Base64.NO_WRAP))
            .put("deviceId", deviceId)
            .toString()

        val connection = (URL("$endpoint/api/v1/crypto/handshake").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
        }
        connection.outputStream.use { it.write(payload.toByteArray()) }

        val code = connection.responseCode
        if (code !in 200..299) {
            runCatching { connection.errorStream?.close() }
            throw CryptoException("握手请求失败：HTTP $code")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val serverHelloB64 = JSONObject(body).optString("serverHello")
        if (serverHelloB64.isBlank()) throw CryptoException("握手响应缺少 serverHello")
        return Base64.decode(serverHelloB64, Base64.DEFAULT)
    }

    companion object {
        private const val TAG = "CryptoTransport"
    }
}

