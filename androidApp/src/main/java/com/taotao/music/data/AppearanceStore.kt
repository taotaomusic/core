package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.AppearanceRow
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.PREFERENCE_ROW_ID
import com.taotao.music.data.db.TaotaoLocalDatabase

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
    private val mirror = object : MirroredPreference<AppearanceRow>(context, "appearance") {
        override fun default() = AppearanceRow(PREFERENCE_ROW_ID, null)
        override fun load(db: TaotaoLocalDatabase) = db.preferenceDao().getAppearance()
        override fun persist(db: TaotaoLocalDatabase, value: AppearanceRow) {
            db.preferenceDao().upsertAppearance(value)
        }
    }

    fun mode(): AppearanceMode = AppearanceMode.of(mirror.current().mode)

    fun setMode(mode: AppearanceMode) = mirror.commit(mirror.current().copy(mode = mode.name))
}
