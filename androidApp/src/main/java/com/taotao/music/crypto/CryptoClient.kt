package com.taotao.music.crypto

/** 隔离 JNI 句柄操作，便于在 JVM 测试中验证握手恢复与资源释放。 */
internal interface CryptoClient {
    val available: Boolean
    fun nativeVersion(): Int
    fun nativeAad(method: String, path: String): ByteArray
    fun clientNew(pskId: String, pskHex: String, deviceId: String): Long
    fun clientHandshake(handle: Long, nowMs: Long): ByteArray
    fun clientFinish(handle: Long, serverHello: ByteArray, nowMs: Long): Boolean
    fun clientNeedsRekey(handle: Long, nowMs: Long): Boolean
    fun clientSeal(handle: Long, aad: ByteArray, body: ByteArray, nowMs: Long): ByteArray
    fun clientHeaderForFrame(handle: Long, frame: ByteArray): String
    fun clientOpen(handle: Long, aad: ByteArray, frame: ByteArray, nowMs: Long): ByteArray
    fun clientFree(handle: Long): Boolean
}

internal object JniCryptoClient : CryptoClient {
    override val available: Boolean get() = NativeCrypto.available
    override fun nativeVersion() = NativeCrypto.nativeVersion()
    override fun nativeAad(method: String, path: String) = NativeCrypto.nativeAad(method, path)
    override fun clientNew(pskId: String, pskHex: String, deviceId: String) =
        NativeCrypto.clientNew(pskId, pskHex, deviceId)
    override fun clientHandshake(handle: Long, nowMs: Long) = NativeCrypto.clientHandshake(handle, nowMs)
    override fun clientFinish(handle: Long, serverHello: ByteArray, nowMs: Long) =
        NativeCrypto.clientFinish(handle, serverHello, nowMs)
    override fun clientNeedsRekey(handle: Long, nowMs: Long) = NativeCrypto.clientNeedsRekey(handle, nowMs)
    override fun clientSeal(handle: Long, aad: ByteArray, body: ByteArray, nowMs: Long) =
        NativeCrypto.clientSeal(handle, aad, body, nowMs)
    override fun clientHeaderForFrame(handle: Long, frame: ByteArray) = NativeCrypto.clientHeaderForFrame(handle, frame)
    override fun clientOpen(handle: Long, aad: ByteArray, frame: ByteArray, nowMs: Long) =
        NativeCrypto.clientOpen(handle, aad, frame, nowMs)
    override fun clientFree(handle: Long) = NativeCrypto.clientFree(handle)
}
