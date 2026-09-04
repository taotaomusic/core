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

    const val SET = "set"
    const val CANCEL = "cancel"
    const val QUERY = "query"

    /** Media3 连接时显式声明的自定义会话命令。 */
    val command = SessionCommand(COMMAND, Bundle.EMPTY)

    /** 防止异常客户端把定时器设成几百年，导致服务永久驻留。 */
    const val MAX_DURATION_MS = 24L * 60L * 60L * 1_000L
}
