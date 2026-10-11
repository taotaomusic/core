package com.taotao.music.ui.app

import android.app.Activity
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.taotao.music.playerui.PlayerActions
import com.taotao.music.playerui.PlayerUiState
import com.taotao.music.playerui.SharedMiniPlayer
import com.taotao.music.playerui.layout.SharedMainLayout
import com.taotao.music.playerui.layout.SharedNavigationItem
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.TaotaoSnackbar
import com.taotao.music.ui.player.launchPlayerDetail
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoTheme
import com.taotao.music.ui.theme.riseIn
import com.taotao.music.ui.theme.sinkOut
import com.taotao.music.ui.update.OptionalUpdateDialog
import com.taotao.music.update.UpdateStage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 底部应用标签栏的四个标签：主界面与播放详情 Activity 共用同一套
 * （详情 Activity 也是「主骨架里的一页」，底部标签栏必须一直在）。
 */
internal val taotaoNavigationItems = listOf(
    SharedNavigationItem(0, "音乐", Icons.Default.MusicNote),
    SharedNavigationItem(1, "AI", Icons.Default.AutoAwesome, "AI 工作台"),
    SharedNavigationItem(2, "聊天", Icons.Default.ChatBubbleOutline),
    SharedNavigationItem(3, "我的", Icons.Default.Person),
)

/**
 * 登录后的主骨架：主题、可选更新提示、底部导航、迷你播放器、全局 Snackbar 与页面分发。
 * 页面级装配在 [TaotaoAppPageRouter]，弹窗与底部面板在 [TaotaoAppDialogs]。
 */
@Composable
internal fun TaotaoAppContent(state: TaotaoAppState, darkTheme: Boolean) {
    val imConnection by state.wukongImClient.connection.collectAsState()
    val updateStatus = state.updateManager.status

    // 迷你播放器 → 详情 Activity 的转场素材：封面在窗口里的落位与圆角（px）。
    // 主界面组合在详情打开期间冻结，封面坐标不漂移，返回动画可直接复用。
    val activityContext = LocalContext.current
    val composeView = LocalView.current
    val density = LocalDensity.current
    var miniCoverBounds by remember { mutableStateOf<Rect?>(null) }
    // AlbumArt 默认形状规则：尺寸不大于 artworkRow 用 TaotaoShapes.artwork 圆角（20dp）。
    val miniCoverRadiusPx = with(density) {
        TaotaoShapes.artwork.topStart.toPx(
            Size(TaotaoSizes.artworkRow.toPx(), TaotaoSizes.artworkRow.toPx()),
            this,
        )
    }

    // 配色统一走 TaotaoTheme，避免和登录页各写一份 colorScheme 导致进入首页时突然换色。
    TaotaoTheme(darkTheme = darkTheme) {
        // 底栏收起/展开动画需要感知系统「关闭动画」开关（SharedMainLayout 在 commonMain，
        // 拿不到平台设置源，由这里读出来传下去）。
        val stateReduceMotion = LocalReduceMotion.current
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
                navigationItems = taotaoNavigationItems,
                onNavigationSelected = { target -> state.switchTab(target) },
                // MV 播放页是全屏沉浸页，迷你播放器和底部导航一起让位。
                hideBottomBar = state.mvSong != null,
                reduceMotion = stateReduceMotion,
                miniPlayerContent = {
                    val reduceMotion = LocalReduceMotion.current
                    // 详情页拆成独立 Activity 后，迷你播放器不再随详情显隐：
                    // 详情打开期间主界面组合冻结、封面保持原位，正好作为一镜到底
                    // 的返回落位；旧sinkOut 仅在队列清空时仍有意义。
                    AnimatedVisibility(
                        visible = state.playbackSongs.isNotEmpty(),
                        enter = riseIn(reduceMotion),
                        exit = sinkOut(reduceMotion),
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
                                onClick = {
                                    (activityContext as? Activity)?.let { host ->
                                        launchPlayerDetail(
                                            activity = host,
                                            sourceView = composeView,
                                            coverBoundsInWindow = miniCoverBounds,
                                            coverRadiusPx = miniCoverRadiusPx,
                                        )
                                    }
                                },
                                modifier = Modifier.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xxs),
                                artworkContent = {
                                    Box(
                                        Modifier.onGloballyPositioned { coords ->
                                            val bounds = coords.boundsInWindow()
                                            miniCoverBounds = Rect(
                                                bounds.left.roundToInt(),
                                                bounds.top.roundToInt(),
                                                bounds.right.roundToInt(),
                                                bounds.bottom.roundToInt(),
                                            )
                                        },
                                    ) {
                                        AlbumArt(Color(current.color), TaotaoSizes.artworkRow, current.coverUri)
                                    }
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

/** 全局提示统一走胶囊样式；任意页面都能看到下载、收藏和播放的反馈。详情 Activity 复用同一实现。 */
@Composable
internal fun SnackbarHostWithTheme(state: TaotaoAppState, modifier: Modifier = Modifier) {
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
