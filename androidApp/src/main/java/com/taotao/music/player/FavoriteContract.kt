package com.taotao.music.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** 媒体通知自定义「收藏」按钮在控制器与播放服务之间的协议。 */
internal object FavoriteContract {
    const val COMMAND = "com.taotao.music.player.FAVORITE_TOGGLE"

    /** Media3 连接时显式声明的自定义会话命令。 */
    val command = SessionCommand(COMMAND, Bundle.EMPTY)
}
