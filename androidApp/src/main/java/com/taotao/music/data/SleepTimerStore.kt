package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.LocalKvStore

/**
 * 定时关闭面板的记忆项：上次使用的时长与「播完整首歌再停止」勾选。
 *
 * 服务端只持有本次会话的定时器状态，跨进程重启的记忆放在这里，
 * 让「上次定时」开关在下次打开应用时仍然可用。
 */
class SleepTimerStore(context: Context) {
    private val kv = LocalKvStore.get(context)

    /** 上次设置的时长（分钟）；从未设置过时用 45 分钟兜底，与快捷键一致。 */
    fun lastMinutes(): Int = kv.getInt(KEY_LAST_MINUTES, DEFAULT_LAST_MINUTES)

    fun setLastMinutes(minutes: Int) {
        kv.putInt(KEY_LAST_MINUTES, minutes.coerceIn(1, MAX_MINUTES))
    }

    fun waitForSongEnd(): Boolean = kv.getBoolean(KEY_WAIT_FOR_SONG_END, false)

    fun setWaitForSongEnd(waitForSongEnd: Boolean) {
        kv.putBoolean(KEY_WAIT_FOR_SONG_END, waitForSongEnd)
    }

    private companion object {
        const val KEY_LAST_MINUTES = "sleep_timer.last_minutes"
        const val KEY_WAIT_FOR_SONG_END = "sleep_timer.wait_for_song_end"
        const val DEFAULT_LAST_MINUTES = 45
        const val MAX_MINUTES = 1_440
    }
}
