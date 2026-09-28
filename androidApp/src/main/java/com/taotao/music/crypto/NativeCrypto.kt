package com.taotao.music.crypto

/**
 * 传输加密 native 库（`libtaotao_crypto.so`）的 JNI 绑定。
 *
 * 每个 `external fun` 与 `crypto-src/jni/src/lib.rs` 里的
 * `Java_com_taotao_music_crypto_NativeCrypto_*` 导出符号一一对应——**包名、类名、
 * 方法名、签名都不能改**，否则运行时 `UnsatisfiedLinkError`。
 *
 * 这里只声明**客户端**用到的方法；`server*` 系列是给「后端也用 JVM 跑」的场景
 * 准备的，Android 用不到，不声明。
 *
 * 句柄语义：`clientNew` 返回一个 `Long` 句柄（0 表示失败），后续调用
 * 都带上它；用完必须 `clientFree` 释放，否则 native 侧注册表泄漏。
 *
 * 领域错误经 [CryptoException] 抛回；native 库缺失时 [available] 为 false，
 * 上层据此完全走明文。
 */
object NativeCrypto {

    /** 库是否成功加载。false 时所有 external 调用都不可用，上层必须走明文。 */
    val available: Boolean

    init {
        available = try {
            System.loadLibrary("taotao_crypto")
            true
        } catch (_: Throwable) {
            // 产物缺失 / ABI 不匹配 / 符号被 strip：不崩，交给上层降级明文。
            false
        }
    }

    // ---- 元信息 ----

    /** 协议版本。设备绑定要求 >= 2。 */
    external fun nativeVersion(): Int

    /** 构造 AAD 上下文（method + path），必须走它，不要自己拼字符串。 */
    external fun nativeAad(method: String, pathAndQuery: String): ByteArray

    // ---- 客户端 ----

    /** 用**后端下发的** PSK 创建客户端引擎，返回句柄（0 = 失败）。`deviceId` 折进握手密钥。 */
    external fun clientNew(pskId: String, pskHex: String, deviceId: String): Long

    /** 发起握手，返回 ClientHello 字节。 */
    external fun clientHandshake(handle: Long, nowMs: Long): ByteArray

    /** 处理 ServerHello 建立会话，成功返回 true。 */
    external fun clientFinish(handle: Long, serverHello: ByteArray, nowMs: Long): Boolean

    /** 加密请求体，返回帧字节。 */
    external fun clientSeal(handle: Long, aad: ByteArray, plaintext: ByteArray, nowMs: Long): ByteArray

    /** 解密响应体，返回明文字节。 */
    external fun clientOpen(handle: Long, aad: ByteArray, frame: ByteArray, nowMs: Long): ByteArray

    /** 从帧反推 `X-Taotao-Crypto` 头值。 */
    external fun clientHeaderForFrame(handle: Long, frame: ByteArray): String

    /** 会话 ID（十六进制）。无会话返回空串。 */
    external fun clientSessionId(handle: Long): String

    /** 是否需要重新握手。 */
    external fun clientNeedsRekey(handle: Long, nowMs: Long): Boolean

    /** 会话剩余有效毫秒数。无会话返回 0。 */
    external fun clientSessionRemainingMs(handle: Long, nowMs: Long): Long

    /** 释放客户端引擎，返回是否确实释放了。 */
    external fun clientFree(handle: Long): Boolean
}
