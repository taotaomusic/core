package com.taotao.music.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * 播放定时器在 MediaController 与 PlaybackService 之间使用的协议。
 *
 * 定时器状态归播放服务持有，页面销毁或退到后台后仍然有效；这里不把状态放进
 * Intent 或 Compose 协程，避免服务与界面各自倒计时导致提前/延后停止。
 */
internal object SleepTimerContract {
    const val COMMAND = "com.taotao.music.player.SLEEP_TIMER"
    const val OPERATION = "operation"
    const val DURATION_MS = "duration_ms"
    const val REMAINING_MS = "remaining_ms"
    const val ACTIVE = "active"

    /**
     * 「播完整首歌再停止播放」的开关。
     * 作为 [SET] 的入参一起下发，也可以单独用 [SET_FLAG] 在计时中途改，
     * 改开关不能重置已经在跑的倒计时。
     */
    const val WAIT_FOR_SONG_END = "wait_for_song_end"

    /** 到期后已进入「等当前这首歌播完」的状态，界面上据此显示本首结束即停。 */
    const val WAITING_SONG_END = "waiting_song_end"

    const val SET = "set"
    const val SET_FLAG = "set_flag"
    const val CANCEL = "cancel"
    const val QUERY = "query"

    /** Media3 连接时显式声明的自定义会话命令。 */
    val command = SessionCommand(COMMAND, Bundle.EMPTY)

    /** 防止异常客户端把定时器设成几百年，导致服务永久驻留。 */
    const val MAX_DURATION_MS = 24L * 60L * 60L * 1_000L
}
