package com.taotao.music.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 管理访问令牌和刷新令牌，避免业务页面自行处理鉴权细节。 */
class AuthSession(context: Context) {
    private val preferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val refreshMutex = Mutex()

    val accessToken: String? get() = preferences.getString(ACCESS_TOKEN, null)
    val refreshToken: String? get() = preferences.getString(REFRESH_TOKEN, null)
    val isSignedIn: Boolean get() = !accessToken.isNullOrBlank()
    val isAccessValid: Boolean get() = preferences.getLong(EXPIRES_AT, 0L) > System.currentTimeMillis() + 30_000L

    fun save(tokens: TencentMusicApi.TokenPair) {
        preferences.edit().putString(ACCESS_TOKEN, tokens.accessToken).putString(REFRESH_TOKEN, tokens.refreshToken).putLong(EXPIRES_AT, System.currentTimeMillis() + tokens.expiresIn * 1000L).apply()
    }

    suspend fun refresh(api: TencentMusicApi): Boolean = refreshMutex.withLock {
        val token = refreshToken ?: return@withLock false
        return@withLock runCatching { api.refresh(token).also(::save) }.isSuccess
    }

    /** 网络暂时不可用时保留本地会话，避免更新或重启后被误退出。 */
    fun hasRefreshToken(): Boolean = !refreshToken.isNullOrBlank()

    fun clear() { preferences.edit().remove(ACCESS_TOKEN).remove(REFRESH_TOKEN).remove(EXPIRES_AT).apply() }

    private companion object { const val ACCESS_TOKEN = "access_token"; const val REFRESH_TOKEN = "refresh_token"; const val EXPIRES_AT = "expires_at" }
}
