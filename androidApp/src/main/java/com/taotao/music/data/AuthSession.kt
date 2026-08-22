package com.taotao.music.data

import android.content.Context

/**
 * 管理访问令牌和刷新令牌，避免业务页面自行处理鉴权细节。
 *
 * 界面和播放服务各自持有实例，但底层是同一份 SharedPreferences，
 * 因此续期锁定义在伴生对象上，保证整个进程内串行刷新。
 */
class AuthSession(context: Context) : TokenProvider {
    private val preferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    /** 会话彻底失效时通知界面回到登录页，由界面层注册。 */
    @Volatile
    var onSessionExpired: (() -> Unit)? = null

    val isSignedIn: Boolean get() = !refreshToken.isNullOrBlank()
    private val refreshToken: String? get() = preferences.getString(REFRESH_TOKEN, null)

    /** 预留 30 秒余量，避免令牌在请求途中刚好过期。 */
    private val isAccessValid: Boolean
        get() = !currentToken().isNullOrBlank() && preferences.getLong(EXPIRES_AT, 0L) > System.currentTimeMillis() + 30_000L

    override fun currentToken(): String? = preferences.getString(ACCESS_TOKEN, null)

    override fun validToken(): String? {
        currentToken()?.takeIf { isAccessValid }?.let { return it }
        return renew(null)
    }

    override fun renewToken(rejectedToken: String?): String? = renew(rejectedToken)

    fun save(tokens: TencentMusicApi.TokenPair) = synchronized(TOKEN_LOCK) { store(tokens) }

    /** 退出登录：先清空本地会话，再请服务端撤销刷新令牌。 */
    fun signOut() {
        val token = synchronized(TOKEN_LOCK) { refreshToken.also { clearTokens() } }
        token?.takeIf { it.isNotBlank() }?.let { TencentMusicApi.revokeRefreshToken(it) }
    }

    /**
     * 用刷新令牌换取新的访问令牌。整体加锁串行执行，因为刷新令牌是一次性的：
     * 并发刷新会让后到的请求拿着已被服务端轮换掉的令牌，反而把会话弄丢。
     *
     * @param rejectedToken 调用方持有的失效令牌。若锁内发现当前令牌已经和它不同，
     *   说明别的请求刚完成刷新，直接复用结果即可。
     */
    private fun renew(rejectedToken: String?): String? {
        var expired = false
        val token = synchronized(TOKEN_LOCK) {
            currentToken()?.takeIf { it != rejectedToken && isAccessValid }?.let { return@synchronized it }
            val refresh = refreshToken?.takeIf { it.isNotBlank() }
            if (refresh == null) {
                expired = true
                return@synchronized null
            }
            val tokens = runCatching { TencentMusicApi.refreshTokens(refresh) }
            tokens.getOrNull()?.let { return@synchronized store(it).accessToken }
            // 只有服务端明确拒绝才清空会话；网络不可用时保留本地令牌，等下次有网再续期。
            if (tokens.exceptionOrNull() is CredentialsRejectedException) {
                clearTokens()
                expired = true
            }
            null
        }
        // 回调可能触发界面重组和播放释放，放到锁外通知，避免与其他请求线程互相等待。
        if (expired) onSessionExpired?.invoke()
        return token
    }

    private fun store(tokens: TencentMusicApi.TokenPair): TencentMusicApi.TokenPair {
        preferences.edit()
            .putString(ACCESS_TOKEN, tokens.accessToken)
            .putString(REFRESH_TOKEN, tokens.refreshToken)
            .putLong(EXPIRES_AT, System.currentTimeMillis() + tokens.expiresIn * 1000L)
            .apply()
        return tokens
    }

    /** 清空本地保存的令牌，调用方负责在锁外通知界面。 */
    private fun clearTokens() {
        preferences.edit().remove(ACCESS_TOKEN).remove(REFRESH_TOKEN).remove(EXPIRES_AT).apply()
    }

    private companion object {
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val EXPIRES_AT = "expires_at"

        /** 进程级续期锁：界面与播放服务的实例共用，防止并发消耗一次性刷新令牌。 */
        val TOKEN_LOCK = Any()
    }
}
