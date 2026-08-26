package com.taotao.music.data

import android.content.Context

/**
 * 悟空 IM 的最近连接凭据缓存。
 *
 * 缓存仅用于应用重建后的快速恢复，真正可用性始终以 `tokenExpiresAt` 和服务端 Gateway 校验为准。
 * 它按桃桃账号 ID 隔离，防止同一手机切换账号后把 A 的聊天凭据拿给 B 使用。
 */
class ImSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("im_session", Context.MODE_PRIVATE)

    fun save(accountId: Long, session: TencentMusicApi.ImSession) {
        require(accountId > 0) { "账号标识不正确" }
        preferences.edit()
            .putLong(ACCOUNT_ID, accountId)
            .putString(UID, session.uid)
            .putString(TOKEN, session.token)
            .putLong(EXPIRES_AT, session.tokenExpiresAt)
            .putInt(DEVICE_FLAG, session.deviceFlag)
            .putInt(DEVICE_LEVEL, session.deviceLevel)
            .putString(GATEWAY_URL, session.gatewayUrl)
            .apply()
    }

    /** 只返回尚有 30 秒余量、且归属当前登录帐号的凭据。 */
    fun validSession(accountId: Long, now: Long = System.currentTimeMillis()): TencentMusicApi.ImSession? {
        if (accountId <= 0 || preferences.getLong(ACCOUNT_ID, 0L) != accountId) return null
        val token = preferences.getString(TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val uid = preferences.getString(UID, null)?.takeIf { it.isNotBlank() } ?: return null
        val gatewayUrl = preferences.getString(GATEWAY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = preferences.getLong(EXPIRES_AT, 0L)
        if (expiresAt <= now + REFRESH_SAFETY_MARGIN_MILLIS) return null
        return TencentMusicApi.ImSession(
            uid = uid,
            token = token,
            tokenExpiresAt = expiresAt,
            deviceFlag = preferences.getInt(DEVICE_FLAG, 1),
            deviceLevel = preferences.getInt(DEVICE_LEVEL, 1),
            gatewayUrl = gatewayUrl,
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val ACCOUNT_ID = "account_id"
        const val UID = "uid"
        const val TOKEN = "token"
        const val EXPIRES_AT = "expires_at"
        const val DEVICE_FLAG = "device_flag"
        const val DEVICE_LEVEL = "device_level"
        const val GATEWAY_URL = "gateway_url"
        const val REFRESH_SAFETY_MARGIN_MILLIS = 30_000L
    }
}
