package com.taotao.music.ui.player

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.theme.TaotaoCoral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 独立的 MV 播放页。
 *
 * 取代旧的「Dialog + 裸 PlayerView」弹窗：全屏沉浸黑底、模糊封面氛围、
 * 自定义控制层（播放/暂停、进度拖动、高清/标清切换）、缓冲与错误兜底。
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

    // 黑底沉浸页要把系统栏图标翻成浅色，否则亮色主题下图标压在黑底上看不见；
    // 退出页面时恢复主题原本的明暗（Theme.kt 按应用主题统一设置过）。
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(window, view) }
        val previousLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousLight?.let { light ->
                controller?.isAppearanceLightStatusBars = light
                controller?.isAppearanceLightNavigationBars = light
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 模糊封面打底，给纯黑页面一点来自歌曲本身的氛围。低版本系统不支持 blur
        // 时退化为「低透明度清晰封面 + 深色遮罩」，观感依然成立。
        mvInfo?.coverUrl?.let { cover ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(cover).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.4f,
                modifier = Modifier.fillMaxSize().blur(48.dp),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }
                Text(
                    "MV",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                // 与左侧返回按钮等宽占位，让标题真正居中。
                Spacer(Modifier.size(TaotaoSizes.iconMd + TaotaoSpacing.md))
            }
            MvVideoArea(
                mvInfo = mvInfo,
                loadError = loadError,
                onRetryLoad = { loadGeneration++ },
            )
            // 视频下方是 MV 对应的歌曲信息，信息不依赖拉取结果，加载中也能先显示。
            Column(Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.lg, vertical = TaotaoSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mvInfo?.name?.takeIf { it.isNotBlank() } ?: song.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(TaotaoSpacing.sm))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            "MV",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    song.artist,
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                )
            }
        }
    }
}

/** 视频区域：16:9 固定比例，承载加载中、拉取失败与播放器三种互斥状态。 */
@Composable
private fun MvVideoArea(
    mvInfo: TencentMusicApi.MvInfo?,
    loadError: String?,
    onRetryLoad: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color(0xFF101010)),
    ) {
        when {
            mvInfo != null -> MvPlayerSurface(mvInfo)
            loadError != null -> MvStatusFallback(message = loadError, onRetry = onRetryLoad)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TaotaoCoral)
            }
        }
    }
}

/**
 * MV 播放面：ExoPlayer + 自定义控制层。
 *
 * 控制层（中央播放按钮、底部进度与清晰度条）在播放中 3.5 秒无操作后自动收起，
 * 点击视频任意位置呼出；暂停、拖动进度时保持常显。
 */
@Composable
private fun MvPlayerSurface(mvInfo: TencentMusicApi.MvInfo) {
    val context = LocalContext.current
    // 默认高清；上游只给到标清流时直接用标清。
    var preferHigh by remember(mvInfo) { mutableStateOf(mvInfo.highUrl != null) }
    val activeUrl = (if (preferHigh) mvInfo.highUrl else mvInfo.lowUrl)
        ?: mvInfo.lowUrl
        ?: mvInfo.highUrl
    val playingHigh = activeUrl == mvInfo.highUrl

    val player = remember(mvInfo) {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    // ---- 播放器状态镜像为 Compose 状态，UI 一律只读镜像 ----
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember(mvInfo) { mutableStateOf(true) }
    var hasEnded by remember(mvInfo) { mutableStateOf(false) }
    var playbackError by remember(mvInfo) { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var draggedPositionMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember(mvInfo) { mutableLongStateOf(0L) }
    // 拉取接口给了时长，缓冲未就绪时先用它撑起进度条，避免 0/0 的尴尬。
    var durationMs by remember(mvInfo) { mutableLongStateOf(mvInfo.durationMs.coerceAtLeast(0L)) }

    /** 装载或换源：带进度换 MediaItem，清晰度切换不打断观看位置。 */
    fun loadSource(url: String, resumePositionMs: Long) {
        playbackError = null
        player.setMediaItem(MediaItem.fromUri(url), resumePositionMs.coerceAtLeast(0L))
        player.prepare()
        player.play()
    }
    LaunchedEffect(mvInfo) { loadSource(activeUrl, 0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) hasEnded = false
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) hasEnded = true
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = "MV 播放失败，请检查网络后重试"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // 位置轮询：Media3 的当前位置只有主线程可读，LaunchedEffect 默认就在主线程调度。
    LaunchedEffect(player) {
        while (isActive) {
            if (!dragging) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val duration = player.duration
                if (duration > 0L) durationMs = duration
            }
            delay(500L)
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    // 播放中 3.5 秒无操作自动收起；暂停与拖动进度时保持常显，方便继续操作。
    LaunchedEffect(controlsVisible, isPlaying, dragging) {
        if (controlsVisible && isPlaying && !dragging) {
            delay(3500L)
            controlsVisible = false
        }
    }

    val togglePlayback = {
        when {
            playbackError != null -> loadSource(activeUrl, positionMs)
            hasEnded -> {
                hasEnded = false
                player.seekTo(0L)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }
    val switchQuality = { high: Boolean ->
        val target = if (high) mvInfo.highUrl else mvInfo.lowUrl
        if (!target.isNullOrBlank() && target != activeUrl) {
            preferHigh = high
            loadSource(target, positionMs)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    // 自带控制器风格与应用脱节，全部换成自绘控制层。
                    useController = false
                    setShowBufferingWhenPlaying(false)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        // 控制层之下垫一层点击面板：点视频任意位置呼出/收起控制层。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible },
        )

        if (isBuffering && playbackError == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TaotaoCoral)
            }
        }
        playbackError?.let { message ->
            MvStatusFallback(message = message, onRetry = { loadSource(activeUrl, positionMs) })
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 中央播放/暂停/重播按钮；整块点击面板已存在，这里只接管按钮自己的点击。
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable { togglePlayback() },
                    contentAlignment = Alignment.Center,
                ) {
                    val centerIcon = when {
                        playbackError != null -> Icons.Default.Replay
                        hasEnded -> Icons.Default.Replay
                        isPlaying -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    }
                    Icon(centerIcon, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                // 底部控制条：压一层向上的黑色渐变保证白色文字可读。
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            ),
                        )
                        .padding(horizontal = TaotaoSpacing.md, vertical = TaotaoSpacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTime(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Slider(
                            value = (if (dragging) draggedPositionMs else positionMs)
                                .toFloat()
                                .coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                            onValueChange = {
                                dragging = true
                                draggedPositionMs = it.toLong()
                            },
                            onValueChangeFinished = {
                                player.seekTo(draggedPositionMs)
                                positionMs = draggedPositionMs
                                dragging = false
                            },
                            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = TaotaoCoral,
                                activeTrackColor = TaotaoCoral,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = TaotaoSpacing.xs),
                        )
                        Text(
                            formatTime(durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()),
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xxs),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MvQualityChip(
                            label = "高清",
                            bitrate = mvInfo.highBitrate,
                            selected = playingHigh,
                            enabled = !mvInfo.highUrl.isNullOrBlank(),
                            onClick = { switchQuality(true) },
                        )
                        Spacer(Modifier.width(TaotaoSpacing.sm))
                        MvQualityChip(
                            label = "标清",
                            bitrate = mvInfo.lowBitrate,
                            selected = !playingHigh,
                            enabled = !mvInfo.lowUrl.isNullOrBlank(),
                            onClick = { switchQuality(false) },
                        )
                    }
                }
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
    val contentColor = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        selected -> TaotaoCoral
        else -> Color.White.copy(alpha = 0.8f)
    }
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            if (bitrate > 0) "$label ${bitrate}K" else label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
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
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(TaotaoSpacing.sm))
        Text(
            message,
            color = Color.White.copy(alpha = 0.75f),
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
