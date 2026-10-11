package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.LocalKvStore

/** 外观模式。[FOLLOW_SYSTEM] 是默认值。 */
enum class AppearanceMode(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        fun of(name: String?): AppearanceMode = entries.firstOrNull { it.name == name } ?: FOLLOW_SYSTEM
    }
}

/** 外观偏好。跟随系统时由 `isSystemInDarkTheme()` 决定，手动选择则覆盖它。 */
class AppearanceStore(context: Context) {
    private val kv = LocalKvStore.get(context)

    fun mode(): AppearanceMode = AppearanceMode.of(kv.getString(KEY_MODE))

    fun setMode(mode: AppearanceMode) = kv.putString(KEY_MODE, mode.name)

    private companion object {
        const val KEY_MODE = "appearance.mode"
    }
}
