package com.taotao.music.ui.player.skin

import android.content.Context

/**
 * 封面皮肤偏好：SharedPreferences 单值持久化。
 *
 * 惯例与 [com.taotao.music.data.AppearanceStore] 一致 —— 文件名即配置名、
 * key 常量放 companion、读是同步返回值（非 Flow）、写 `.apply()`。
 * 注意 SharedPreferences 不可观察：切换皮肤后的界面刷新由详情页的
 * Compose 状态驱动，不要指望读这个类拿到变化通知。
 */
class CoverSkinStore(context: Context) {
    private val preferences = context.getSharedPreferences("cover_skin", Context.MODE_PRIVATE)

    fun skin(): CoverSkinId = CoverSkinId.of(preferences.getString(KEY_SKIN, null))

    fun setSkin(skin: CoverSkinId) = preferences.edit().putString(KEY_SKIN, skin.name).apply()

    private companion object {
        const val KEY_SKIN = "skin"
    }
}
