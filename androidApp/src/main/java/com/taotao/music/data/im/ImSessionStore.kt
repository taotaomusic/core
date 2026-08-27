package com.taotao.music.data.im

import android.content.Context
import com.taotao.music.data.TencentMusicApi

/** 悟空 IM 的短期连接凭据缓存，按桃桃账号隔离。 */
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

    fun validSession(accountId: Long, now: Long = System.currentTimeMillis()): TencentMusicApi.ImSession? {
        if (accountId <= 0 || preferences.getLong(ACCOUNT_ID, 0L) != accountId) return null
        val token = preferences.getString(TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val uid = preferences.getString(UID, null)?.takeIf { it.isNotBlank() } ?: return null
        val gatewayUrl = preferences.getString(GATEWAY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = preferences.getLong(EXPIRES_AT, 0L)
        if (expiresAt <= now + REFRESH_SAFETY_MARGIN_MILLIS) return null
        return TencentMusicApi.ImSession(uid, token, expiresAt, preferences.getInt(DEVICE_FLAG, 1), preferences.getInt(DEVICE_LEVEL, 1), gatewayUrl)
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
