package com.taotao.music.data.im

import android.content.Context
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.db.IM_SESSION_CREDENTIAL_ID
import com.taotao.music.data.db.ImSessionCredentialRow
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.TaotaoLocalDatabase

/** IM 凭据的镜像值；credential 为 null 表示本机未持有有效连接凭据。 */
private data class ImSessionState(
    val credential: ImSessionCredentialRow?,
)

/** 悟空 IM 的短期连接凭据缓存，按桃桃账号隔离。 */
class ImSessionStore(context: Context) {
    private val mirror = object : MirroredPreference<ImSessionState>(context, "im_session") {
        override fun default() = ImSessionState(null)
        override fun load(db: TaotaoLocalDatabase) = ImSessionState(db.imDao().getSession())
        override fun persist(db: TaotaoLocalDatabase, value: ImSessionState) {
            val dao = db.imDao()
            val credential = value.credential
            if (credential == null) dao.deleteSession() else dao.upsertSession(credential)
        }
    }

    fun save(accountId: Long, session: TencentMusicApi.ImSession) {
        require(accountId > 0) { "账号标识不正确" }
        mirror.commit(
            ImSessionState(
                ImSessionCredentialRow(
                    id = IM_SESSION_CREDENTIAL_ID,
                    accountId = accountId,
                    uid = session.uid,
                    token = session.token,
                    expiresAt = session.tokenExpiresAt,
                    deviceFlag = session.deviceFlag,
                    deviceLevel = session.deviceLevel,
                    gatewayUrl = session.gatewayUrl,
                )
            )
        )
    }

    fun validSession(accountId: Long, now: Long = System.currentTimeMillis()): TencentMusicApi.ImSession? {
        val credential = mirror.current().credential ?: return null
        if (credential.accountId != accountId) return null
        if (credential.token.isBlank() || credential.uid.isBlank() || credential.gatewayUrl.isBlank()) return null
        val expiresAt = credential.expiresAt
        if (expiresAt <= now + REFRESH_SAFETY_MARGIN_MILLIS) return null
        return TencentMusicApi.ImSession(credential.uid, credential.token, expiresAt, credential.deviceFlag, credential.deviceLevel, credential.gatewayUrl)
    }

    fun clear() {
        mirror.commit(ImSessionState(null))
    }

    private companion object {
        const val REFRESH_SAFETY_MARGIN_MILLIS = 30_000L
    }
}
