package com.taotao.music.crypto

/**
 * 传输加密 native 层抛回的领域错误（握手失败、解密失败、会话未就绪等）。
 *
 * 全限定名 **必须** 是 `com.taotao.music.crypto.CryptoException`：JNI 侧
 * `throw_new` 按这个字符串查类，不一致会静默失败，Kotlin 侧只会看到
 * `NullPointerException` 而拿不到真正的错误原因。
 */
class CryptoException(message: String) : RuntimeException(message)
