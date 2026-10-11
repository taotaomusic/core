package com.taotao.music.ui.player.skin

import android.content.Context
import com.taotao.music.data.db.CoverSkinRow
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.PREFERENCE_ROW_ID
import com.taotao.music.data.db.TaotaoLocalDatabase
import com.taotao.music.playerui.skin.CoverSkinId

/**
 * Android 端皮肤偏好：本地库单行表持久化（平台宿主自持，共享层不管存储）。
 *
 * 惯例与 [com.taotao.music.data.AppearanceStore] 一致 —— 读是同步返回值（非 Flow）、
 * 写先改内存镜像再异步落库。
 * 注意存储不可观察：切换皮肤后的界面刷新由详情页的
 * Compose 状态驱动，不要指望读这个类拿到变化通知。
 */
class CoverSkinStore(context: Context) {
    private val mirror = object : MirroredPreference<CoverSkinRow>(context, "cover_skin") {
        override fun default() = CoverSkinRow(PREFERENCE_ROW_ID, null)
        override fun load(db: TaotaoLocalDatabase) = db.preferenceDao().getCoverSkin()
        override fun persist(db: TaotaoLocalDatabase, value: CoverSkinRow) {
            db.preferenceDao().upsertCoverSkin(value)
        }
    }

    fun skin(): CoverSkinId = CoverSkinId.of(mirror.current().skin)

    fun setSkin(skin: CoverSkinId) = mirror.commit(mirror.current().copy(skin = skin.name))
}
