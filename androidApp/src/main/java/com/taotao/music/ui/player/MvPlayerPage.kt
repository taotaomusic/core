package com.taotao.music.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoColors
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.TaotaoWash
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoCoral
import com.taotao.music.ui.theme.contentFadeIn
import com.taotao.music.ui.theme.contentFadeOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 独立的 MV 播放页。
 *
 * 全屏黑底沉浸式：竖屏时视频区垂直居中（封面模糊打底、歌曲信息跟在视频下方），
 * 底部导航与迷你播放器由 SharedMainLayout 的 hideBottomBar 收起；控制层支持
 * 播放/暂停、进度拖动、高清/标清切换与横屏全屏。竖屏时控制块整体放在视频
 * 下方的黑色空白区、不遮挡画面；全屏时控制层盖在画面上，无操作自动收起。
 *
 * 歌曲音频的暂停与恢复由路由层（AppPageRouter 的 [MvPlayerPageRoute]）负责，
 * 这里只管 MV 自己的播放，两边互不掺和。
 */
@Composable
internal fun MvPlayerPage(
    song: Song,
    musicApi: TencentMusicApi,
    onBack: () -> Unit,
) {
    // ---- MV 信息：进入页面才拉取，失败给整页错误态与重试 ----
    var mvInfo by remember(song) { mutableStateOf<TencentMusicApi.MvInfo?>(null) }
    var loadError by remember(song) { mutableStateOf<String?>(null) }
    var loadGeneration by remember(song) { mutableIntStateOf(0) }
    LaunchedEffect(song, loadGeneration) {
        loadError = null
        runCatching { withContext(Dispatchers.IO) { musicApi.requestMv(song) } }
            .onSuccess { mvInfo = it }
            .onFailure { loadError = it.message ?: "获取 MV 失败，请稍后重试" }
    }

    // ---- 全屏：横屏铺满并隐藏系统栏；返回键先退全屏再退页面 ----
    var isFullscreen by remember { mutableStateOf(false) }
    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    val view = LocalView.current
    // 黑底页面要把系统栏图标翻成浅色，退出时交还主题原本的明暗（Theme.kt 统一设置过）。
    // 方向与系统栏收起跟随 isFullscreen 单一状态源；页面销毁时全部恢复，
    // 否则从全屏返回后应用会一直横屏、其他页面摸不到系统栏。
    DisposableEffect(Unit) {
        val activity = view.context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).run {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
            previousLight?.let { light ->
                controller?.isAppearanceLightStatusBars = light
                controller?.isAppearanceLightNavigationBars = light
            }
        }
    }
    LaunchedEffect(isFullscreen) {
        val activity = view.context as? Activity ?: return@LaunchedEffect
        activity.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        WindowCompat.getInsetsController(activity.window, view).run {
            // 收起的系统栏用「边缘轻扫临时呼出」的沉浸模式，呼出几秒后自动回落。
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 页面打底纯黑：视频浮层的黑不属于任何主题色，保持字面量（MV 页豁免）。
    // 本页是盖在搜索页/详情页之上的不透明浮层，但纯黑背景没有可点目标，Compose
    // 里只有背景、没有指针处理的节点不拦截触摸 —— 不在这里吞事件，点按会落透到
    // 下层页面：隔着 MV 页点到搜索结果里的另一张视频卡，正在看的 MV 会被静默
    // 切走。视频区与控制层的子节点在命中测试里优先于本层，原有交互不受影响。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // 空白区域一律吞掉指针事件（点击/滑动/长按都无响应）；协程随页面
                // 离开组合被取消，不需要额外的退出条件。
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        // 模糊封面打底，给竖屏的纯黑页面一点来自歌曲本身的氛围；全屏时画面
        // 本身铺满，不再铺背景。低版本系统不支持 blur 时退化为「低透明度清晰
        // 封面 + 深色遮罩」，观感依然成立。
        if (!isFullscreen) {
            mvInfo?.coverUrl?.let { cover ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.4f,
                    modifier = Modifier.fillMaxSize().blur(48.dp),
                )
                // 豁免：0.55 比共享遮罩档 TaotaoWash.scrim(0.35) 更深，这里是「模糊封面的暗化打底」，
                // 需要比常规遮罩更沉才能托住上层信息，保留原值。
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            }
        }

        // 顶栏、视频与信息区都挂在同一个 Column 里，竖横切换只改尺寸与显隐，
        // 不换组合分支 —— 否则播放器的 remember 状态会被丢弃，旋转就重新缓冲。
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            AnimatedVisibility(visible = !isFullscreen, enter = contentFadeIn(), exit = contentFadeOut()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = TaotaoColors.videoOn)
                    }
                    Text(
                        "MV",
                        color = TaotaoColors.videoOn,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    // 与左侧返回按钮等宽占位，让标题真正居中。
                    Spacer(Modifier.size(TaotaoSizes.iconMd + TaotaoSpacing.md))
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MvPlaybackArea(
                        mvInfo = mvInfo,
                        loadError = loadError,
                        onRetryLoad = { loadGeneration++ },
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = { isFullscreen = !isFullscreen },
                    )
                    // 视频下方是 MV 对应的歌曲信息；信息不依赖拉取结果，加载中也能先显示。
                    AnimatedVisibility(visible = !isFullscreen, enter = contentFadeIn(), exit = contentFadeOut()) {
                        Column(
                            Modifier.padding(horizontal = TaotaoSpacing.lg, vertical = TaotaoSpacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    mvInfo?.name?.takeIf { it.isNotBlank() } ?: song.title,
                                    color = TaotaoColors.videoOn,
                                    // 歌名从 cardTitle(16sp) 收到 bodyCompact(14sp)：控制块搬进
                                    // 黑区后整页信息密度收紧一档，让位给画面。
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(TaotaoSpacing.sm))
                                Surface(
                                    shape = TaotaoShapes.extraSmall,
                                    color = Color.Transparent,
                                    // 豁免：徽标描边用 40% 白，比次要文字更淡以退到装饰层，
                                    // 提亮会喧宾夺主，不进共享文字档位。
                                    border = BorderStroke(TaotaoStroke.thin, Color.White.copy(alpha = 0.4f)),
                                ) {
                                    Text(
                                        "MV",
                                        color = TaotaoColors.videoOnMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                        // vertical 1.dp 是像素级微调，不进 4dp 栅格；水平间距对齐共享 xs 档。
                                        modifier = Modifier.padding(horizontal = TaotaoSpacing.xs, vertical = 1.dp),
                                    )
                                }
                            }
                            Text(
                                song.artist,
                                color = TaotaoColors.videoOnMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 播放区：拉到 MV 信息后进入播放器装配，加载中/失败落在 16:9 视频位上兜底。 */
@Composable
private fun MvPlaybackArea(
    mvInfo: TencentMusicApi.MvInfo?,
    loadError: String?,
    onRetryLoad: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    when {
        mvInfo != null -> MvPlayingSection(
            mvInfo = mvInfo,
            isFullscreen = isFullscreen,
            onToggleFullscreen = onToggleFullscreen,
        )
        else -> Box(
            (if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center,
        ) {
            if (loadError != null) {
                MvStatusFallback(message = loadError, onRetry = onRetryLoad)
            } else {
                CircularProgressIndicator(color = TaotaoCoral)
            }
        }
    }
}

/**
 * 播放器装配：视频面 + 控制块。
 *
 * 竖屏时控制块（时间/进度/清晰度/全屏）整体移出视频面，放到视频下方的黑色
 * 空白区，不再遮挡画面；全屏时回到「盖在画面上 + 3.5 秒无操作自动收起」的
 * 常规播放器形态。两种形态共用同一套 [MvControlRows]。
 *
 * 点视频的语义随之分家：竖屏控制块常驻，点视频 = 播放/暂停；全屏点视频 =
 * 呼出/收起控制层。
 */
@Composable
private fun MvPlayingSection(
    mvInfo: TencentMusicApi.MvInfo,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val controller = rememberMvPlayerController(mvInfo)

    // 全屏时播放中 3.5 秒无操作自动收起；暂停、拖动进度与刚切全屏时保持常显，
    // 否则用户找不到退出全屏的按钮。竖屏控制块常驻，不参与收起。
    LaunchedEffect(controller.controlsVisible, controller.isPlaying, controller.dragging, isFullscreen) {
        if (isFullscreen && controller.controlsVisible && controller.isPlaying && !controller.dragging) {
            delay(3500L)
            controller.controlsVisible = false
        }
    }

    Box(
        (if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            .background(Color(0xFF101010)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = controller.player
                    // 自带控制器风格与应用脱节，全部换成自绘控制层。
                    useController = false
                }
            },
            update = { it.player = controller.player },
            modifier = Modifier.fillMaxSize(),
        )
        // 控制层之下垫一层点击面板：竖屏点视频切播放/暂停，全屏呼出/收起控制层。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (isFullscreen) {
                        controller.controlsVisible = !controller.controlsVisible
                    } else {
                        controller.togglePlayback()
                    }
                },
        )

        if (controller.isBuffering && controller.playbackError == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TaotaoCoral)
            }
        }
        controller.playbackError?.let { message ->
            // 地址缺失时重试没有意义，只有网络型错误才给重试入口。
            MvStatusFallback(
                message = message,
                onRetry = controller.activeUrl?.let { url ->
                    { controller.loadSource(url, controller.positionMs) }
                },
            )
        }

        if (isFullscreen) {
            AnimatedVisibility(
                visible = controller.controlsVisible,
                enter = contentFadeIn(),
                exit = contentFadeOut(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    MvCenterButton(controller, Modifier.align(Alignment.Center))
                    // 底部控制条：压一层向上的黑色渐变保证白色文字可读。
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    // 豁免：底部控制条渐变的终点比共享遮罩档更深（0.75），
                                    // 是「向上渐隐压暗」的终点值，保证时间文字可读，不归 scrim 档。
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                                ),
                            )
                            .padding(horizontal = TaotaoSpacing.md, vertical = TaotaoSpacing.xs),
                    ) {
                        MvControlRows(controller, isFullscreen, onToggleFullscreen)
                    }
                }
            }
        } else if (!controller.isPlaying && !controller.isBuffering && controller.playbackError == null) {
            // 竖屏暂停/播完时在画面中央给一枚明确的「可继续播放」按钮，播放中不打扰画面。
            MvCenterButton(controller)
        }
    }

    if (!isFullscreen) {
        // 竖屏控制块：落在视频下方的黑色空白区，与画面脱开，不再遮挡视频。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = TaotaoSpacing.sm)
                .padding(horizontal = TaotaoSpacing.lg),
        ) {
            MvControlRows(controller, isFullscreen, onToggleFullscreen)
        }
    }
}

/**
 * MV 播放器状态持有者：Media3 播放器实例 + 镜像给 Compose 的状态 + 动作方法。
 * 状态一律用 `mutableStateOf` 挂在类身上，UI 组合只读；写操作收敛为方法，
 * 供视频面（竖屏/全屏）与视频下方的控制块两边共享同一份播放状态。
 */
private class MvPlayerController(
    context: Context,
    val mvInfo: TencentMusicApi.MvInfo,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { playWhenReady = true }

    // 默认高清；上游只给到标清流时直接用标清。
    var preferHigh by mutableStateOf(mvInfo.highUrl != null)
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(true)
    var hasEnded by mutableStateOf(false)
    var playbackError by mutableStateOf<String?>(null)
    var dragging by mutableStateOf(false)
    var draggedPositionMs by mutableLongStateOf(0L)
    var positionMs by mutableLongStateOf(0L)
    // 拉取接口给了时长，缓冲未就绪时先用它撑起进度条，避免 0/0 的尴尬。
    var durationMs by mutableLongStateOf(mvInfo.durationMs.coerceAtLeast(0L))
    var controlsVisible by mutableStateOf(true)

    val activeUrl: String?
        get() = (if (preferHigh) mvInfo.highUrl else mvInfo.lowUrl) ?: mvInfo.lowUrl ?: mvInfo.highUrl
    val playingHigh: Boolean
        get() = activeUrl == mvInfo.highUrl

    /** 装载或换源：带进度换 MediaItem，清晰度切换不打断观看位置。 */
    fun loadSource(url: String, resumePositionMs: Long) {
        playbackError = null
        player.setMediaItem(MediaItem.fromUri(url), resumePositionMs.coerceAtLeast(0L))
        player.prepare()
        player.play()
    }

    fun togglePlayback() {
        when {
            playbackError != null -> activeUrl?.let { loadSource(it, positionMs) }
            hasEnded -> {
                hasEnded = false
                player.seekTo(0L)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun switchQuality(high: Boolean) {
        val target = if (high) mvInfo.highUrl else mvInfo.lowUrl
        if (!target.isNullOrBlank() && target != activeUrl) {
            preferHigh = high
            loadSource(target, positionMs)
        }
    }
}

/** 拉取成功后装配播放器：监听器、位置轮询与首次装载都在这里接好，页面只消费 [MvPlayerController]。 */
@Composable
private fun rememberMvPlayerController(mvInfo: TencentMusicApi.MvInfo): MvPlayerController {
    val context = LocalContext.current
    val controller = remember(mvInfo) { MvPlayerController(context, mvInfo) }

    LaunchedEffect(controller.mvInfo) {
        // 上游可能两条流都为空：这时不装载播放器，直接给错误态。
        val url = controller.activeUrl
        if (url == null) controller.playbackError = "没有可用的 MV 播放地址" else controller.loadSource(url, 0L)
    }

    DisposableEffect(controller.player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                controller.isPlaying = playing
                if (playing) controller.hasEnded = false
            }

            override fun onPlaybackStateChanged(state: Int) {
                controller.isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) controller.hasEnded = true
            }

            override fun onPlayerError(error: PlaybackException) {
                controller.playbackError = "MV 播放失败，请检查网络后重试"
            }
        }
        controller.player.addListener(listener)
        onDispose { controller.player.removeListener(listener) }
    }
    DisposableEffect(controller.player) {
        onDispose { controller.player.release() }
    }

    // 位置轮询：Media3 的当前位置只有主线程可读，LaunchedEffect 默认就在主线程调度。
    LaunchedEffect(controller.player) {
        while (isActive) {
            if (!controller.dragging) {
                controller.positionMs = controller.player.currentPosition.coerceAtLeast(0L)
                val duration = controller.player.duration
                if (duration > 0L) controller.durationMs = duration
            }
            delay(500L)
        }
    }

    return controller
}

/** 中央播放/暂停/重播按钮：半透明黑圆压在画面中心，只接管按钮自己的点击。 */
@Composable
private fun MvCenterButton(controller: MvPlayerController, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(TaotaoSizes.playButton)
            .background(Color.Black.copy(alpha = TaotaoWash.scrim), CircleShape)
            .clickable { controller.togglePlayback() },
        contentAlignment = Alignment.Center,
    ) {
        val centerIcon = when {
            controller.playbackError != null -> Icons.Default.Replay
            controller.hasEnded -> Icons.Default.Replay
            controller.isPlaying -> Icons.Default.Pause
            else -> Icons.Default.PlayArrow
        }
        Icon(centerIcon, null, tint = TaotaoColors.videoOn, modifier = Modifier.size(TaotaoSizes.iconButton))
    }
}

/**
 * 控制行：时间 + 进度条一行，清晰度 + 全屏一行。
 *
 * 竖屏垫在视频下方的黑色空白区、全屏垫在画面内的渐变浮条上，由外层负责
 * 底衬与内边距，这里只管两行内容本身。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MvControlRows(
    controller: MvPlayerController,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    // 时间标签收紧到 10sp 并开等宽数字：比共享 micro 档再小一号（豁免），
    // 播放中位数变化时宽度不抖，时间戳读感更稳。
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFeatureSettings = "tnum")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(controller.positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()),
                color = TaotaoColors.videoOn,
                style = timeStyle,
            )
            Slider(
                value = (if (controller.dragging) controller.draggedPositionMs else controller.positionMs)
                    .toFloat()
                    .coerceIn(0f, controller.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = {
                    controller.dragging = true
                    controller.draggedPositionMs = it.toLong()
                },
                onValueChangeFinished = {
                    controller.player.seekTo(controller.draggedPositionMs)
                    controller.positionMs = controller.draggedPositionMs
                    controller.dragging = false
                },
                valueRange = 0f..controller.durationMs.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = TaotaoCoral,
                    activeTrackColor = TaotaoCoral,
                    // 豁免：进度条未播放轨道用 35% 白，在黑底上够辨识又不抢画面，不进共享档位。
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                ),
                // 显式给一枚小圆拇指：默认拇指在这条进度条上观感突兀（见用户截图里的竖条）。
                thumb = {
                    Box(Modifier.size(12.dp).background(TaotaoCoral, CircleShape))
                },
                modifier = Modifier.weight(1f).padding(horizontal = TaotaoSpacing.xs),
            )
            Text(
                formatTime(controller.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()),
                color = TaotaoColors.videoOn,
                style = timeStyle,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MvQualityChip(
                label = "高清",
                bitrate = controller.mvInfo.highBitrate,
                selected = controller.playingHigh,
                enabled = !controller.mvInfo.highUrl.isNullOrBlank(),
                onClick = { controller.switchQuality(true) },
            )
            Spacer(Modifier.width(TaotaoSpacing.sm))
            MvQualityChip(
                label = "标清",
                bitrate = controller.mvInfo.lowBitrate,
                selected = !controller.playingHigh,
                enabled = !controller.mvInfo.lowUrl.isNullOrBlank(),
                onClick = { controller.switchQuality(false) },
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    onToggleFullscreen()
                    // 竖横切换后重置收起计时，保证退出全屏按钮至少停留一轮可见。
                    controller.controlsVisible = true
                },
            ) {
                Icon(
                    if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    if (isFullscreen) "退出全屏" else "全屏",
                    tint = TaotaoColors.videoOn,
                )
            }
        }
    }
}

/** 清晰度档位按钮：当前档珊瑚色高亮，上游缺流时置灰。 */
@Composable
private fun MvQualityChip(
    label: String,
    bitrate: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 浮层白色文字只保留两档：主信息 videoOn、次要信息 videoOnMuted（禁用态归次要档）。
    val contentColor = when {
        !enabled -> TaotaoColors.videoOnMuted
        selected -> TaotaoCoral
        else -> TaotaoColors.videoOn
    }
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            if (bitrate > 0) "$label ${bitrate}K" else label,
            color = contentColor,
            // 档位字号从 label(12sp) 收到 micro(11sp)，与收紧后的控制块密度一致。
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 视频/加载的兜底态：图标 + 文案 + 可选的重试。 */
@Composable
private fun MvStatusFallback(message: String, onRetry: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(TaotaoSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.VideoLibrary,
            contentDescription = null,
            tint = TaotaoColors.videoOnMuted,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(TaotaoSpacing.sm))
        Text(
            message,
            color = TaotaoColors.videoOnMuted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text("重试", color = TaotaoCoral)
            }
        }
    }
}
