package com.taotao.music.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.taotao.music.playerui.PlayerActions
import com.taotao.music.playerui.PlayerUiState
import com.taotao.music.playerui.SharedMiniPlayer
import com.taotao.music.playerui.layout.SharedMainLayout
import com.taotao.music.playerui.layout.SharedNavigationItem
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.TaotaoSnackbar
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoTheme
import com.taotao.music.ui.theme.riseIn
import com.taotao.music.ui.theme.sinkOut
import com.taotao.music.ui.update.OptionalUpdateDialog
import com.taotao.music.update.UpdateStage
import kotlinx.coroutines.launch

/**
 * 登录后的主骨架：主题、可选更新提示、底部导航、迷你播放器、全局 Snackbar 与页面分发。
 * 页面级装配在 [TaotaoAppPageRouter]，弹窗与底部面板在 [TaotaoAppDialogs]。
 */
@Composable
internal fun TaotaoAppContent(state: TaotaoAppState, darkTheme: Boolean) {
    val imConnection by state.wukongImClient.connection.collectAsState()
    val updateStatus = state.updateManager.status

    // 配色统一走 TaotaoTheme，避免和登录页各写一份 colorScheme 导致进入首页时突然换色。
    TaotaoTheme(darkTheme = darkTheme) {
        // 可选更新提示：强制更新已在主入口拦截返回，这里只处理用户可以忽略的情况。
        if (updateStatus.stage != UpdateStage.IDLE && updateStatus.stage != UpdateStage.CHECKING &&
            updateStatus.stage != UpdateStage.UP_TO_DATE && !updateStatus.forced
        ) {
            OptionalUpdateDialog(
                status = updateStatus,
                onDownload = { state.scope.launch { state.updateManager.download() } },
                onInstall = { state.updateManager.install() },
                onRetry = { state.scope.launch { state.updateManager.retry() } },
                onDismiss = { state.updateManager.dismiss() },
            )
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            SharedMainLayout(
                selectedNavigationId = state.bottomTab,
                navigationItems = remember {
                    listOf(
                        SharedNavigationItem(0, "音乐", Icons.Default.MusicNote),
                        SharedNavigationItem(1, "AI", Icons.Default.AutoAwesome, "AI 工作台"),
                        SharedNavigationItem(2, "聊天", Icons.Default.ChatBubbleOutline),
                        SharedNavigationItem(3, "我的", Icons.Default.Person),
                    )
                },
                onNavigationSelected = { target -> state.switchTab(target) },
                miniPlayerContent = {
                    val reduceMotion = LocalReduceMotion.current
                    AnimatedVisibility(
                        visible = !state.showPlayerDetail && state.playbackSongs.isNotEmpty(),
                        enter = riseIn(reduceMotion),
                        exit = if (state.showPlayerDetail) ExitTransition.None else sinkOut(reduceMotion),
                    ) {
                        val current = remember(state.playbackSongs, state.selectedIndex) {
                            state.playbackSongs.getOrNull(
                                state.selectedIndex.coerceIn(0, (state.playbackSongs.size - 1).coerceAtLeast(0)),
                            )
                        }
                        if (current != null) {
                            SharedMiniPlayer(
                                state = PlayerUiState(song = current, isPlaying = state.isPlaying),
                                actions = PlayerActions(
                                    onTogglePlaying = { state.togglePlayback() },
                                    onPrevious = { state.playAdjacentSong(-1) },
                                    onNext = { state.playAdjacentSong(1) },
                                    onSeek = {},
                                    onToggleRepeat = {}
                                ),
                                onClick = { state.showPlayerDetail = true },
                                modifier = Modifier.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xxs),
                                artworkContent = {
                                    AlbumArt(Color(current.color), TaotaoSizes.artworkRow, current.coverUri)
                                }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(Modifier.fillMaxSize()) {
                    // SnackbarHost 在页面之下组合；页面不画背景，提示仍能透出显示。
                    SnackbarHostWithTheme(
                        state = state,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                    )

                    TaotaoAppPageRouter(
                        state = state,
                        connection = imConnection,
                        imUid = imConnection.uid,
                        innerPadding = innerPadding,
                    )
                }
            }
        }
    }

    // 弹窗和底部面板位于主 Surface 之后，必须显式继承当前外观主题；否则暗色模式会退回默认亮色。
    TaotaoTheme(darkTheme = darkTheme) {
        TaotaoAppDialogs(state)
    }
}

/** 全局提示统一走胶囊样式；任意页面都能看到下载、收藏和播放的反馈。 */
@Composable
private fun SnackbarHostWithTheme(state: TaotaoAppState, modifier: Modifier = Modifier) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            state.message = null
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    ) { snackbarData ->
        // 全局提示统一走胶囊样式；左右留边避免长文案顶到屏幕边缘。
        Box(
            Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            TaotaoSnackbar(snackbarData = snackbarData)
        }
    }
}
