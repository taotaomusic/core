package com.taotao.music.data

/**
 * 访问令牌提供方：把"令牌是否过期、如何续期"收敛到会话层，
 * 网络客户端只需要取令牌和在被拒绝后请求续期。
 */
interface TokenProvider {
    /** 当前保存的访问令牌，可能已经过期。 */
    fun currentToken(): String?

    /** 取一个尽量可用的访问令牌：本地未过期直接返回，否则先续期。 */
    fun validToken(): String?

    /**
     * 访问令牌被服务端拒绝后续期。
     * @param rejectedToken 被拒绝的令牌，用于判断是否已有其他请求完成了续期，避免重复消耗一次性刷新令牌。
     * @return 可用的新访问令牌；刷新令牌同样失效时返回 null。
     */
    fun renewToken(rejectedToken: String?): String?
}

/** 刷新令牌也已失效，必须重新登录。 */
class SessionExpiredException : IllegalStateException("登录状态已过期，请重新登录")
