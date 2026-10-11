package com.taotao.music.ui.player

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.itgsa.animation.sharedview.ViewAnimationConfig
import com.itgsa.animation.sharedview.ViewAnimationListener
import com.itgsa.animation.sharedview.ViewAnimationManager
import com.itgsa.animation.sharedview.ViewAnimationType
import com.taotao.music.TaotaoApplication
import com.taotao.music.data.AppearanceMode
import com.taotao.music.playerui.layout.SharedMainLayout
import com.taotao.music.ui.app.MvPlayerOverlay
import com.taotao.music.ui.app.SnackbarHostWithTheme
import com.taotao.music.ui.app.TaotaoAppDialogs
import com.taotao.music.ui.app.TaotaoAppState
import com.taotao.music.ui.app.taotaoNavigationItems
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoTheme
import kotlinx.coroutines.launch

/**
 * 播放详情页独立 Activity。
 *
 * 为什么要拆出来：金标联盟「一镜到底」（SharedViewKit）的转场是 **系统级** 的，
 * 只发生在两个 Activity 之间——迷你播放器（主界面）与详情页必须分属两个
 * Activity，荣耀/OPPO/vivo/小米的支持机型上 ROM 才能接管开合动画。
 * 不支持机型自动降级为 [com.taotao.music.R.style.Theme_Taotao_PlayerDetail]
 * 的底部滑入/滑出窗口动画，观感与原先 Compose 内的升起动效一致。
 *
 * 状态与主界面共享同一份 [TaotaoApplication.appState]：播放队列、收藏、音质、
 * 弹窗开关全部跨 Activity 直读直写，无需 intent 传参。
 *
 * 深链（`taotaomusic://open`）回归：MainActivity 是 singleTask，系统路由唤起
 * intent 时会自动清掉栈顶的本 Activity，无需专门处理；[onDestroy] 里兜底清理
 * MV 浮层状态，避免回到主界面时浮层意外接管。
 */
class PlayerDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = TaotaoApplication.appState
        state.playerDetailActive = true
        // 队列被清空后不允许停在详情页：直接回主界面（与旧路由「无歌不渲染」语义一致）。
        if (state.playbackSongs.isEmpty()) {
            state.playerDetailActive = false
            finish()
            return
        }
        // 状态层请求关详情（切标签 / 跳歌手专辑页）：走 Kit 回落动画 finish。
        lifecycleScope.launch {
            state.closeDetailEvents.collect { finishWithKitTransition() }
        }
        // 物理返回键：组合内还有更高优先级的 MV 返回处理（MV 开着先退 MV），
        // 这里兜底处理详情自身的返回。
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishWithKitTransition()
                }
            },
        )
        setContent {
            PlayerDetailActivityContent(state = state, onBack = ::finishWithKitTransition)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val state = TaotaoApplication.appState
        state.playerDetailActive = false
        // 深链清栈或系统回收可能带着开着的 MV 一起销毁：清掉浮层状态，
        // 主界面恢复时不会因为 mvSong 残留而意外弹出浮层。
        state.mvSong = null
    }

    /**
     * 关详情：Kit 支持时用 [ViewAnimationManager.overrideViewAnimationTransition]
     * 声明回落目标（打开时记录的迷你封面落位，见 [launchPlayerDetail]），
     * ROM 接管返回转场；不支持时什么都不加，交回降级主题的底部滑出窗口动画。
     *
     * [fadeOut] = 落位视图不可见（队列被清空等）时的兜底：不做封面回落，淡出收场。
     */
    fun finishWithKitTransition(fadeOut: Boolean = false) {
        val state = TaotaoApplication.appState
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ViewAnimationManager.isViewAnimationSupported() <= 0) {
                return@runCatching
            }
            val config = ViewAnimationConfig()
            val bounds = state.detailReturnBounds
            if (!fadeOut && bounds != null) {
                config.setRadius(state.detailReturnRadiusPx)
                    .setBounds(bounds)
                    .setBackAnimType(ViewAnimationType.VIEW_ANIM_DEFAULT)
            } else {
                config.setBackAnimType(ViewAnimationType.VIEW_ANIM_FADE_OUT)
            }
            ViewAnimationManager.overrideViewAnimationTransition(this, config.toBundle())
        }
        finish()
    }
}

/**
 * 主界面迷你播放器 → 详情页的唯一入口。
 *
 * Kit 支持且封面落位可得时：对整个主界面做软件快照、裁出封面矩形作为一镜到底
 * 的源图，[ViewAnimationManager.makeViewAnimationOptions] 产出 ActivityOptions
 * 随 intent 启动，ROM 接管升起动画；其余情况普通启动，交回降级主题动画。
 * 调用同时把封面落位与圆角写进状态，供详情页返回动画瞄定。
 *
 * Kit 的半径单位是 **px**（官方 README 明确警告 dp 必须乘 density），调用方负责换算。
 */
internal fun launchPlayerDetail(
    activity: Activity,
    sourceView: View,
    coverBoundsInWindow: Rect?,
    coverRadiusPx: Float,
) {
    val state = TaotaoApplication.appState
    state.detailReturnBounds = coverBoundsInWindow
    state.detailReturnRadiusPx = coverRadiusPx
    val intent = Intent(activity, PlayerDetailActivity::class.java)
    val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && coverBoundsInWindow != null) {
        runCatching {
            if (ViewAnimationManager.isViewAnimationSupported() <= 0) return@runCatching null
            if (sourceView.width <= 0 || sourceView.height <= 0) return@runCatching null
            // 快照裁剪：boundsInWindow 是窗口坐标，先平移到视图本地坐标再裁。
            // 手动软件画布绘制（ktx 的 drawToBitmap 在当前依赖图里不可见，不依赖它）。
            val full = Bitmap.createBitmap(sourceView.width, sourceView.height, Bitmap.Config.ARGB_8888)
            sourceView.draw(android.graphics.Canvas(full))
            val viewOrigin = IntArray(2)
            sourceView.getLocationInWindow(viewOrigin)
            val left = (coverBoundsInWindow.left - viewOrigin[0]).coerceIn(0, full.width - 1)
            val top = (coverBoundsInWindow.top - viewOrigin[1]).coerceIn(0, full.height - 1)
            val width = minOf(coverBoundsInWindow.width(), full.width - left)
            val height = minOf(coverBoundsInWindow.height(), full.height - top)
            if (width <= 0 || height <= 0) return@runCatching null
            val cover: Bitmap = Bitmap.createBitmap(full, left, top, width, height)
            ViewAnimationManager.makeViewAnimationOptions(
                cover,
                coverBoundsInWindow,
                coverRadiusPx,
                ViewAnimationConfig().toBundle(),
                object : ViewAnimationListener {
                    override fun onAnimationStart(mode: Int) {}
                    override fun onAnimationCancel(mode: Int) {}
                    override fun onAnimationEnd(mode: Int) {}
                },
            )
        }.getOrNull()
    } else {
        null
    }
    if (options != null) {
        activity.startActivity(intent, options.toBundle())
    } else {
        // 降级：Theme.Taotao.PlayerDetail 的底部滑入窗口动画。
        activity.startActivity(intent)
    }
}

/** 详情 Activity 的组合根：与主界面同一套主题、装配与弹窗体系。 */
@Composable
private fun PlayerDetailActivityContent(state: TaotaoAppState, onBack: (Boolean) -> Unit) {
    /** 外观：与主入口同一套解析（跟随系统 / 手动亮暗）。 */
    val darkTheme = when (state.appearance) {
        AppearanceMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    TaotaoTheme(darkTheme = darkTheme) {
        // MV 浮层开着时物理返回先退 MV：组合内注册的 BackHandler 比分发器里的
        // 详情兜底回调晚添加，优先级更高。
        BackHandler(enabled = state.mvSong != null) { state.mvSong = null }
        // 与主界面同一套主骨架：详情页是「主骨架里的一页」，底部应用标签栏
        // 必须一直在（旧组合内实现的观感）。标签点击走 switchTab，它会发
        // closeDetailEvents 让本 Activity 带一镜到底回落、主界面落到对应标签。
        SharedMainLayout(
            selectedNavigationId = state.bottomTab,
            navigationItems = taotaoNavigationItems,
            onNavigationSelected = { target -> state.switchTab(target) },
            // 详情页不渲染迷你播放器（旧观感：详情打开时迷你让位）；播放由服务继续。
            miniPlayerContent = { },
            // MV 浮层盖住底部时标签栏同步让位，与主界面同一规则。
            hideBottomBar = state.mvSong != null,
            reduceMotion = LocalReduceMotion.current,
        ) { innerPadding ->
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    // 页面内容沿用旧路由的边距规则：让出系统栏与底部标签栏。
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        PlayerDetailPageRoute(state = state, onBack = onBack)
                    }
                    SnackbarHostWithTheme(
                        state = state,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                    )
                    // MV 浮层盖住整个骨架（标签栏同步让位），与主界面同一挂法。
                    if (state.mvSong != null) {
                        MvPlayerOverlay(state)
                    }
                }
            }
        }
    }
    // 弹窗与底部面板：显式继承当前外观主题（与主界面同一做法，暗色不回退亮色）。
    TaotaoTheme(darkTheme = darkTheme) {
        TaotaoAppDialogs(state, inDetailActivity = true)
    }
}

/**
 * 播放详情页装配：队列、收藏、下载、音质与定时关闭全部来自全局状态。
 * 自 AppPageRouter 迁入——装配参数不变，仅 onBack 改为「finish 本 Activity」，
 * 并补充队列被清空时的自动回落（旧实现由路由 when 分支的判空渲染兜底）。
 */
@Composable
private fun PlayerDetailPageRoute(state: TaotaoAppState, onBack: (Boolean) -> Unit) {
    // 队列在详情页开着期间被清空（如逐个移除）：重组先于副作用求值，索引表达式
    // 会在空列表上越界，所以先判空早退（这一帧是背景色，下一帧走淡出回落），
    // 再由副作用触发 finish。旧实现由路由 when 分支的「无歌不渲染」兜底。
    LaunchedEffect(state.playbackSongs.isEmpty()) {
        if (state.playbackSongs.isEmpty()) onBack(true)
    }
    val song = state.playbackSongs.getOrNull(state.selectedIndex) ?: return
    PlayerDetailPage(
        song = song,
        audioPlayer = state.audioPlayer,
        isPlaying = state.isPlaying,
        onBack = { onBack(false) },
        onDownload = {
            // 先在主线程取定目标歌曲，避免后台任务期间 selectedIndex 变化导致下错歌或越界。
            val target = state.playbackSongs.getOrNull(state.selectedIndex)
            when {
                target == null -> state.message = "没有正在播放的歌曲"
                target.audioUri?.startsWith("file:") == true -> state.message = "这首歌已经下载过了"
                // 下载前先选音质：下载只花一次流量，值得让用户自己定，
                // 面板里会显示各档的实际体积。
                else -> state.downloadTarget = target to state.selectedIndex
            }
        },
        musicApi = state.musicApi,
        onTogglePlaying = { state.togglePlayback() },
        onPrevious = { state.playAdjacentSong(-1) },
        onNext = { state.playAdjacentSong(1) },
        repeatMode = state.audioPlayer.repeatMode,
        onToggleRepeat = {
            val nextMode = when (state.audioPlayer.repeatMode) {
                androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                else -> androidx.media3.common.Player.REPEAT_MODE_OFF
            }
            state.audioPlayer.updateRepeatMode(nextMode)
        },
        queue = state.playbackSongs,
        queueIndex = state.selectedIndex,
        onQueueItemClick = { index -> state.playSong(state.playbackSongs, index) },
        onRemoveQueueItem = { index -> state.audioPlayer.removeQueueItem(index) },
        onMoveQueueItem = { from, to -> state.moveQueueItem(from, to) },
        onKeepOnlyCurrent = { state.audioPlayer.keepOnlyCurrent() },
        history = state.playbackHistory,
        localSongs = state.downloadedSongs,
        onPlayHistory = { index -> state.playSong(state.playbackHistory.map { it.song }, index) },
        onPlayLocal = { index -> state.playSong(state.downloadedSongs, index) },
        onPlayNext = { song -> state.playNext(song) },
        onAddToPlaylist = { song -> state.playlist.requestAddSong(song) },
        onShare = { state.requestSongShare(song) },
        isFavorite = { song -> state.favoritesStore.contains(song) },
        onToggleSongFavorite = { song -> state.toggleFavorite(song) },
        favorited = remember(state.selectedIndex, state.favoriteRevision, state.playbackSongs) {
            state.playbackSongs.getOrNull(state.selectedIndex)?.let { state.favoritesStore.contains(it) } == true
        },
        onToggleFavorite = {
            state.playbackSongs.getOrNull(state.selectedIndex)?.let { state.toggleFavorite(it) }
        },
        playbackQuality = state.playbackQuality.value,
        onPickQuality = { state.qualitySheet = QualitySheetKind.CURRENT_SONG },
        sleepTimerRemainingMs = state.audioPlayer.sleepTimerRemainingMs,
        sleepTimerWaitingSongEnd = state.audioPlayer.sleepTimerWaitingSongEnd,
        onOpenSleepTimer = { state.showSleepTimerDialog = true },
        onRefrainResolved = { song, startMs, endMs -> state.applyResolvedRefrain(song, startMs, endMs) },
        onOpenMv = { state.mvSong = it },
        // 播放页头部「歌手 · 专辑」点击跳转：与搜索歌曲行菜单共用同一套
        // 「从歌曲构造跳转目标」逻辑（见状态层 openArtistPageFromSong / openAlbumPageFromSong）。
        // 详情 Activity 场景下这两个函数会先发关闭事件让本页回落，主界面恢复后展示目标页。
        onOpenArtist = { song -> state.openArtistPageFromSong(song) },
        onOpenAlbum = { song -> state.openAlbumPageFromSong(song) },
    )
}
