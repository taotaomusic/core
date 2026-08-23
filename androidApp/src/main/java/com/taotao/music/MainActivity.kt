package com.taotao.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.taotao.music.ui.TaotaoMusicApp

/**
 * 唯一的 Activity：只负责生命周期和 Compose 入口，不承载布局或业务逻辑。
 *
 * 通知权限的申请放在 Compose 侧（见 [TaotaoMusicApp]），不在这里重复 ——
 * 早先两处都申请，Android 只会展示一次系统弹窗，另一处纯属冗余。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统栏交给主题决定明暗：颜色写死在 styles.xml 里的话，暗色下会白底白字。
        enableEdgeToEdge()
        setContent { TaotaoMusicApp() }
    }
}
