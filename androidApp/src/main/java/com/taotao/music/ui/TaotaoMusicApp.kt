package com.taotao.music.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.taotao.music.data.AppearanceMode
import com.taotao.music.ui.app.TaotaoAppContent
import com.taotao.music.ui.app.TaotaoAppPreAuthEffects
import com.taotao.music.ui.app.TaotaoAppSessionGuard
import com.taotao.music.ui.app.TaotaoAppSignedInEffects
import com.taotao.music.ui.app.rememberTaotaoAppState
import com.taotao.music.ui.auth.AuthPage
import com.taotao.music.ui.theme.TaotaoTheme
import com.taotao.music.ui.update.ForceUpdatePage
import kotlinx.coroutines.launch

/**
 * 应用主入口。
 *
 * 页面此前全部内联在一个两千行的 Composable 里；现在职责分三层：
 * - [com.taotao.music.ui.app.TaotaoAppState]：全局状态与操作（播放、收藏、歌单、搜索、统计）。
 * - 本文件：组合入口 —— 状态容器、登录与强制更新门禁、副作用挂载。
 * - [TaotaoAppContent]：主骨架、页面分发与弹窗装配。
 */
@Composable
fun TaotaoMusicApp() {
    val state = rememberTaotaoAppState()
    /** 外观：跟随系统时读系统设置，手动选择则覆盖它。 */
    val darkTheme = when (state.appearance) {
        AppearanceMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    val updateStatus = state.updateManager.status

    // 热更新检查、通知权限与 IM 前后台感知必须早于登录门禁组合，坏版本才走得出更新页。
    TaotaoAppPreAuthEffects(state)

    if (updateStatus.blocking) {
        // 必须自己套一层主题：这里在主内容之前就 return 了，
        // 不套的话页面会拿到 Material 的默认配色，暗色下更是白底白字。
        TaotaoTheme(darkTheme = darkTheme) {
            ForceUpdatePage(
                status = updateStatus,
                onDownload = { state.scope.launch { state.updateManager.download() } },
                onInstall = { state.updateManager.install() },
                onRetry = { state.scope.launch { state.updateManager.retry() } },
            )
        }
        return
    }

    TaotaoAppSessionGuard(state)

    if (!state.signedIn) {
        AuthPage(state.musicApi, darkTheme) { tokens -> state.onSignedIn(tokens) }
        return
    }

    TaotaoAppSignedInEffects(state)
    TaotaoAppContent(state, darkTheme)
}
