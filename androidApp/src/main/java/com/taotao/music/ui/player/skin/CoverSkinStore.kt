package com.taotao.music.ui.player.skin

import android.content.Context
import com.taotao.music.data.db.LocalKvStore
import com.taotao.music.playerui.skin.CoverSkinId

/**
 * Android 端皮肤偏好：本地键值库单值持久化（平台宿主自持，共享层不管存储）。
 *
 * 惯例与 [com.taotao.music.data.AppearanceStore] 一致 —— 键即配置名、
 * key 常量放 companion、读是同步返回值（非 Flow）、写先改内存再异步落库。
 * 注意存储不可观察：切换皮肤后的界面刷新由详情页的
 * Compose 状态驱动，不要指望读这个类拿到变化通知。
 */
class CoverSkinStore(context: Context) {
    private val kv = LocalKvStore.get(context)

    fun skin(): CoverSkinId = CoverSkinId.of(kv.getString(KEY_SKIN))

    fun setSkin(skin: CoverSkinId) = kv.putString(KEY_SKIN, skin.name)

    private companion object {
        const val KEY_SKIN = "cover_skin.skin"
    }
}
