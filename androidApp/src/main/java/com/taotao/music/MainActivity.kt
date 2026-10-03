package com.taotao.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.ui.TaotaoMusicApp

/**
 * 唯一的 Activity：只负责生命周期和 Compose 入口，不承载布局或业务逻辑。
 *
 * 通知权限的申请放在 Compose 侧（见 [TaotaoMusicApp]），不在这里重复 ——
 * 早先两处都申请，Android 只会展示一次系统弹窗，另一处纯属冗余。
 *
 * launchMode 是 singleTask：分享页「在桃桃音乐中打开」唤起时若已有实例，
 * 必须复用它并把链接经 [onNewIntent] 送进既有界面，而不是再叠一个全新的应用状态。
 */
class MainActivity : ComponentActivity() {
    /** 最近一次收到的 `taotaomusic://open` 链接原文；null 表示没有待处理的唤起。 */
    private var pendingOpenLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统栏交给主题决定明暗：颜色写死在 styles.xml 里的话，暗色下会白底白字。
        enableEdgeToEdge()
        pendingOpenLink = intent?.data?.toString()
        setContent {
            TaotaoMusicApp(
                pendingOpenLink = pendingOpenLink,
                onOpenLinkConsumed = { pendingOpenLink = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { pendingOpenLink = it }
    }
}
