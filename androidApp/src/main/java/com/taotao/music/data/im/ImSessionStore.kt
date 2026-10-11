package com.taotao.music.data.im

import android.content.Context
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.db.LocalKvStore

/** 悟空 IM 的短期连接凭据缓存，按桃桃账号隔离。 */
class ImSessionStore(context: Context) {
    private val kv = LocalKvStoreAlias.get(context)

    fun save(accountId: Long, session: TencentMusicApi.ImSession) {
        require(accountId > 0) { "账号标识不正确" }
        kv.putAll(
            mapOf(
                ACCOUNT_ID to accountId.toString(),
                UID to session.uid,
                TOKEN to session.token,
                EXPIRES_AT to session.tokenExpiresAt.toString(),
                DEVICE_FLAG to session.deviceFlag.toString(),
                DEVICE_LEVEL to session.deviceLevel.toString(),
                GATEWAY_URL to session.gatewayUrl,
            )
        )
    }

    fun validSession(accountId: Long, now: Long = System.currentTimeMillis()): TencentMusicApi.ImSession? {
        if (accountId <= 0 || kv.getLong(ACCOUNT_ID, 0L) != accountId) return null
        val token = kv.getString(TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val uid = kv.getString(UID)?.takeIf { it.isNotBlank() } ?: return null
        val gatewayUrl = kv.getString(GATEWAY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = kv.getLong(EXPIRES_AT, 0L)
        if (expiresAt <= now + REFRESH_SAFETY_MARGIN_MILLIS) return null
        return TencentMusicApi.ImSession(uid, token, expiresAt, kv.getInt(DEVICE_FLAG, 1), kv.getInt(DEVICE_LEVEL, 1), gatewayUrl)
    }

    fun clear() {
        kv.removePrefix(PREFIX)
    }

    private companion object {
        const val PREFIX = "im_session."
        const val ACCOUNT_ID = "${PREFIX}account_id"
        const val UID = "${PREFIX}uid"
        const val TOKEN = "${PREFIX}token"
        const val EXPIRES_AT = "${PREFIX}expires_at"
        const val DEVICE_FLAG = "${PREFIX}device_flag"
        const val DEVICE_LEVEL = "${PREFIX}device_level"
        const val GATEWAY_URL = "${PREFIX}gateway_url"
        const val REFRESH_SAFETY_MARGIN_MILLIS = 30_000L
    }
}
