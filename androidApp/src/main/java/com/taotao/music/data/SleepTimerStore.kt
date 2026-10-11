package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.PREFERENCE_ROW_ID
import com.taotao.music.data.db.SleepTimerRow
import com.taotao.music.data.db.TaotaoLocalDatabase

/**
 * 定时关闭面板的记忆项：上次使用的时长与「播完整首歌再停止」勾选。
 *
 * 服务端只持有本次会话的定时器状态，跨进程重启的记忆放在这里，
 * 让「上次定时」开关在下次打开应用时仍然可用。
 */
class SleepTimerStore(context: Context) {
    private val mirror = object : MirroredPreference<SleepTimerRow>(context, "sleep_timer") {
        override fun default() = SleepTimerRow(PREFERENCE_ROW_ID, DEFAULT_LAST_MINUTES, false)
        override fun load(db: TaotaoLocalDatabase) = db.preferenceDao().getSleepTimer()
        override fun persist(db: TaotaoLocalDatabase, value: SleepTimerRow) {
            db.preferenceDao().upsertSleepTimer(value)
        }
    }

    /** 上次设置的时长（分钟）；从未设置过时用 45 分钟兜底，与快捷键一致。 */
    fun lastMinutes(): Int = mirror.current().lastMinutes

    fun setLastMinutes(minutes: Int) {
        mirror.commit(mirror.current().copy(lastMinutes = minutes.coerceIn(1, MAX_MINUTES)))
    }

    fun waitForSongEnd(): Boolean = mirror.current().waitForSongEnd

    fun setWaitForSongEnd(waitForSongEnd: Boolean) {
        mirror.commit(mirror.current().copy(waitForSongEnd = waitForSongEnd))
    }

    private companion object {
        const val DEFAULT_LAST_MINUTES = 45
        const val MAX_MINUTES = 1_440
    }
}
