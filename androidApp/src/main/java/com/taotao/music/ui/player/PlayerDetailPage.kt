package com.taotao.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.taotao.music.data.PlaybackHistoryEntry
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.LyricParser
import com.taotao.music.model.Song
import com.taotao.music.player.AudioPlayer
import com.taotao.music.playerui.PlayerActions
import com.taotao.music.playerui.PlayerCompactLayout
import com.taotao.music.playerui.PlayerRepeatMode
import com.taotao.music.playerui.PlayerUiState
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.FavoriteButton
import com.taotao.music.ui.common.PagerDots
import com.taotao.music.ui.common.VipBadge
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoCoral
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlayerDetailPage(
    song: Song,
    audioPlayer: AudioPlayer,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onTogglePlaying: () -> Unit,
    musicApi: TencentMusicApi,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    repeatMode: Int,
    onToggleRepeat: () -> Unit,
    queue: List<Song>,
    queueIndex: Int,
    onQueueItemClick: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onKeepOnlyCurrent: () -> Unit,
    history: List<PlaybackHistoryEntry>,
    localSongs: List<Song>,
    onPlayHistory: (Int) -> Unit,
    onPlayLocal: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToPlaylist: ((Song) -> Unit)? = null,
    isFavorite: (Song) -> Boolean,
    onToggleSongFavorite: (Song) -> Unit,
    onMessage: (String) -> Unit,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    playbackQuality: Int,
    onPickQuality: () -> Unit,
    sleepTimerRemainingMs: Long,
    sleepTimerWaitingSongEnd: Boolean,
    onOpenSleepTimer: () -> Unit,
) {
    // 播放器维护唯一进度源；拖动期间才暂存本地位置，松手立即交回播放器同步。
    var draggedPositionMs by remember(song) { mutableIntStateOf(audioPlayer.positionMs) }
    var dragging by remember(song) { mutableStateOf(false) }
    val positionMs = if (dragging) draggedPositionMs else audioPlayer.positionMs
    // 这两组状态的 remember key 必须与下面对应 LaunchedEffect 的 key 一致：
    // 解析播放地址后队列里的 Song 会被换成新副本，song 变了但 remoteId / lyricUri 没变，
    // key 不一致就会出现「状态被清空、拉取逻辑却不重跑」的空白歌词和收藏状态丢失。
    var lyricText by remember(song.lyricUri, song.remoteId, song.mid) { mutableStateOf<String?>(null) }
    var lyricWords by remember(song.lyricWordsUri, song.remoteId, song.mid) { mutableStateOf<String?>(null) }
    // 解析结果按原文缓存，避免每帧进度变化都重新解析整段歌词。
    val lyric = remember(lyricText, lyricWords) { LyricParser.parse(lyricText, lyricWords) }
    var showQueue by remember { mutableStateOf(false) }
    // 时长和播放态直接读播放器暴露的状态，不再各自轮询。
    val durationMs = audioPlayer.durationMs
    val actualPlaying = audioPlayer.isPlaying
    val detailScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // key 必须是稳定的标识而不是整个 song：换音质或解析地址后队列里的 Song 会被换成新副本，
    // 用 song 做 key 会重建 Animatable，封面转到一半突然弹回 0°。
    val coverRotation = remember(song.remoteId, song.audioUri) { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(song.lyricUri, song.remoteId, song.mid, song.source) {
        // 离线歌曲的行级与逐字时间轴分别存成两个文件，两个都要读 ——
        // 早先这里只读一个文本文件，离线播放于是永远没有逐字高亮。
        val loaded = runCatching {
            withContext(Dispatchers.IO) {
                val lyricUri = song.lyricUri
                if (lyricUri?.startsWith("file:") == true) {
                    val text = Uri.parse(lyricUri).path?.let(::File)?.takeIf(File::isFile)?.readText()
                    val words = song.lyricWordsUri
                        ?.let { Uri.parse(it).path }
                        ?.let(::File)?.takeIf(File::isFile)?.readText()
                    text to words
                } else if (song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank()) {
                    val rich = musicApi.requestRichLyric(song)
                    rich.lrc to rich.yrc
                } else {
                    null to null
                }
            }
        }.getOrElse { null to null }
        lyricText = loaded.first
        lyricWords = loaded.second
    }
    // positionMs 是本次组合从播放器 State 读取出的普通值，不能再放进无 key 的
    // remember/derivedStateOf：那会把首次进入页面时的数值闭包起来，后续进度不再刷新。
    // 时间文字只按秒显示，直接计算即可；Compose 只会在播放器位置更新时重新组合。
    val positionSeconds = positionMs / 1000

    /** 本地文件与云端流的处理处处不同，取一次给下面复用。 */
    val isLocalFile = song.audioUri?.startsWith("file:") == true

    /**
     * 界面上显示的音质。
     *
     * 本地文件取下载时记下的实际档位；云端流从占位地址里解析出请求的档位。
     * 两者都拿不到时退回全局默认值 —— 只发生在旧版本下载的、没记音质的歌上。
     */
    val displayedQuality = when {
        isLocalFile -> song.localQuality ?: playbackQuality
        else -> song.audioUri?.let { TencentMusicApi.parsePlaceholder(it)?.second } ?: playbackQuality
    }
    // 详情页与歌词页做成左右两页，但只有中间区域参与滑动：
    // 顶栏、歌名、进度条和播放控制留在外层，切到歌词页时仍然可见可操作。
    val pagerState = rememberPagerState(pageCount = { 2 })

    /**
     * 封面旋转。
     *
     * 三个停止条件都必须有：暂停时停、页面不在前台时停、**滑到歌词页时也要停**。
     * 少了最后一个，用户看歌词的整段时间里这个动画仍在每 16 毫秒请求一帧，
     * 而封面那一页已经被 pager 销毁 —— 驱动的是一个没人读的值，纯耗电。
     */
    LaunchedEffect(song.remoteId, song.mid, song.audioUri, isPlaying, lifecycleOwner, reduceMotion) {
        if (reduceMotion) {
            coverRotation.snapTo(0f)
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!isPlaying) return@repeatOnLifecycle
            snapshotFlow { pagerState.settledPage == 0 }.collectLatest { onCoverPage ->
                if (!onCoverPage) return@collectLatest
                while (isActive) {
                    // 角度对 360 取模，避免长时间播放后累加成很大的数值。
                    coverRotation.snapTo(coverRotation.value % 360f)
                    coverRotation.animateTo(
                        targetValue = coverRotation.value + 360f,
                        animationSpec = tween(AnimationDurations.COVER_SPIN, easing = LinearEasing),
                    )
                }
            }
        }
    }
    // 在歌词页按返回先回到封面页，而不是直接关掉整个详情页。
    // 这个 BackHandler 比主页面里那个更深，启用时优先生效。
    BackHandler(enabled = pagerState.currentPage > 0) {
        detailScope.launch { pagerState.animateScrollToPage(0) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "收起") }
            Text(
                if (pagerState.currentPage == 1) "歌词" else "正在播放",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(
                onClick = onOpenSleepTimer,
                contentPadding = PaddingValues(horizontal = TaotaoSpacing.xxs),
            ) {
                Icon(Icons.Default.Timer, "定时关闭", modifier = Modifier.size(TaotaoSizes.iconSm))
                Spacer(Modifier.width(TaotaoSpacing.xxs))
                Text(
                    when {
                        // 到期后等当前歌播完的阶段：倒计时已归零，改说清楚还不会立刻停。
                        sleepTimerWaitingSongEnd -> "本首结束后停止"
                        sleepTimerRemainingMs > 0L -> "剩余 ${formatSleepTimerRemaining(sleepTimerRemainingMs)}"
                        else -> "定时关闭"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            if (page == 1) {
                LyricPane(
                    lyric = lyric,
                    positionMs = positionMs,
                    onSeek = { target ->
                        draggedPositionMs = target
                        audioPlayer.seekTo(target)
                    },
                )
            } else {
                // 封面按可用空间取尺寸，固定 292dp 在小屏上会把下方控制区挤出屏幕。
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val coverSize = minOf(maxWidth, maxHeight) * 0.86f
                    if (!song.coverUri.isNullOrBlank()) {
                        AsyncImage(
                            model = song.coverUri,
                            contentDescription = "专辑封面",
                            modifier = Modifier
                                .size(coverSize)
                                .graphicsLayer { rotationZ = coverRotation.value }
                                .clip(CircleShape),
                        )
                    } else {
                        AlbumArt(Color(song.color), coverSize)
                    }
                }
            }
        }
        PagerDots(
            current = pagerState.currentPage,
            total = 2,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = TaotaoSpacing.sm),
        )
        PlayerCompactLayout(
            state = PlayerUiState(
                song = song,
                isPlaying = actualPlaying,
                positionMs = positionMs.toLong(),
                durationMs = durationMs.toLong(),
                repeatMode = when (repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> PlayerRepeatMode.ONE
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> PlayerRepeatMode.ALL
                    else -> PlayerRepeatMode.OFF
                },
            ),
            actions = PlayerActions(
                onTogglePlaying = onTogglePlaying,
                onSeek = { target -> dragging = true; draggedPositionMs = target.toInt() },
                onSeekFinished = {
                    audioPlayer.seekTo(draggedPositionMs)
                    dragging = false
                },
                onToggleRepeat = onToggleRepeat,
                onPrevious = onPrevious,
                onNext = onNext,
            ),
            positionLabel = formatTime(positionSeconds * 1000),
            durationLabel = formatTime(durationMs).takeIf { durationMs > 0 } ?: "--:--",
            titleTrailingContent = {
                if (song.vip) VipBadge(Modifier.padding(start = TaotaoSpacing.xs))
            },
            metadataTrailingContent = {
                if (song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank()) {
                    // 本地文件也要显示音质；本地换档要重新下载，因此只让在线歌曲可点。
                    QualityChip(
                        quality = displayedQuality,
                        modifier = Modifier.padding(start = TaotaoSpacing.sm),
                        local = isLocalFile,
                        onClick = onPickQuality.takeIf { !isLocalFile },
                    )
                }
            },
            // 顶栏只保留收藏：分享、加入歌单、下载都挪到进度条上方的快捷操作行，
            // 否则三个按钮加上 VIP 角标和音质标签，长歌名会被压到只显示一两个字。
            headerActions = {
                FavoriteButton(
                    favorited = favorited,
                    onClick = onToggleFavorite,
                    enabled = song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank(),
                )
            },
            quickActions = {
                // 已下载的歌不提供可点却无效果的下载入口，只留一个对勾说明在放本地文件。
                IconButton(onClick = onDownload, enabled = !isLocalFile) {
                    Icon(
                        if (isLocalFile) Icons.Default.CheckCircle else Icons.Default.Download,
                        if (isLocalFile) "已下载" else "下载歌曲",
                        tint = if (isLocalFile) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, "分享歌曲", tint = TaotaoCoral)
                }
                // 本地文件仍保留远端身份，可以和在线播放歌曲一样加入云端歌单。
                if (
                    onAddToPlaylist != null &&
                    (song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank())
                ) {
                    IconButton(onClick = { onAddToPlaylist(song) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "加入歌单", tint = TaotaoCoral)
                    }
                }
            },
            controlTrailingContent = {
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = if (queue.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral)
                }
            },
        )
        Spacer(Modifier.height(TaotaoSpacing.md))
    }
    if (showQueue) {
        PlaybackQueueSheet(
            queue = queue,
            currentIndex = queueIndex,
            onDismiss = { showQueue = false },
            onItemClick = { index ->
                showQueue = false
                onQueueItemClick(index)
            },
            repeatMode = repeatMode,
            onCycleRepeat = onToggleRepeat,
            onRemoveItem = onRemoveQueueItem,
            onMoveItem = onMoveQueueItem,
            onKeepOnlyCurrent = onKeepOnlyCurrent,
            history = history,
            localSongs = localSongs,
            onPlayHistory = onPlayHistory,
            onPlayLocal = onPlayLocal,
            onPlayNext = onPlayNext,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleSongFavorite,
        )
    }
}
