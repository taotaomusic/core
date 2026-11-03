package com.taotao.music.update

import android.content.Context
import com.taotao.music.AppHttp
import com.taotao.music.data.AuthSession
import com.taotao.music.data.DeviceIdStore
import com.taotao.music.data.RemoteConfigStore
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.PREFERENCE_ROW_ID
import com.taotao.music.data.db.TaotaoLocalDatabase
import com.taotao.music.data.db.UpdateDismissalRow

/**
 * `:hotupdate` 模块的宿主接线。
 *
 * 热更新代码已抽到独立模块（包名保持不变），但它需要的令牌、设备号、远程配置
 * 与划掉记录都归 app 所有 —— 这个类按 [UpdateHost] 契约把这些能力递给模块，
 * 依赖方向保持 app -> 模块，模块内不出现任何 app 类型。
 *
 * 划掉记录沿用原 UpdateManager 的镜像存储实现：domain 键（"update_dismissal"）
 * 与行结构保持不变，老版本记录的"已划掉"在升级后继续生效。
 */
class TaotaoUpdateHost(context: Context, private val authSession: AuthSession) : UpdateHost {
    private val appContext = context.applicationContext

    override val endpoint: String = TencentMusicApi.ENDPOINT

    override val userAgent: String = AppHttp.USER_AGENT

    override val config: UpdateConfigStore = RemoteConfigStore(appContext)

    override fun currentToken(): String? = authSession.currentToken()

    override fun deviceId(): String = DeviceIdStore(appContext).deviceId()

    override fun dismissedVersionCode(): Long = dismissalMirror.current().dismissedVersionCode

    override fun commitDismissed(versionCode: Long) {
        dismissalMirror.commit(UpdateDismissalRow(PREFERENCE_ROW_ID, versionCode))
    }

    /** 用户已划掉的版本号，跨启动记住（原 UpdateManager 的实现原样迁来）。 */
    private val dismissalMirror = object : MirroredPreference<UpdateDismissalRow>(appContext, "update_dismissal") {
        override fun default() = UpdateDismissalRow(PREFERENCE_ROW_ID, 0L)
        override fun load(db: TaotaoLocalDatabase) = db.preferenceDao().getUpdateDismissal()
        override fun persist(db: TaotaoLocalDatabase, value: UpdateDismissalRow) {
            db.preferenceDao().upsertUpdateDismissal(value)
        }
    }
}
