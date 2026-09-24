package com.taotao.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taotao.music.model.AudioQuality
import com.taotao.music.model.Lyric
import com.taotao.music.model.LyricWord
import com.taotao.music.model.Song
import com.taotao.music.playerui.PlayerActions
import com.taotao.music.playerui.PlayerRepeatMode
import com.taotao.music.playerui.PlayerUiState
import com.taotao.music.playerui.PlayerWideLayout
import com.taotao.music.playerui.SharedContentState
import com.taotao.music.playerui.SharedContentStateType
import com.taotao.music.playerui.SharedMiniPlayer
import com.taotao.music.playerui.SharedSongRow
import com.taotao.music.playerui.theme.TaotaoElevation
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.TaotaoTypeScale
import com.taotao.music.model.labelOfQuality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class NavItem(val page: DesktopPage, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

// ---- 本文件私有的页面 / 组件尺寸 ----
//
// 这些值描述「这一屏占多宽」「这一块多大」，换一屏或换一个组件就不成立，
// 所以**不进 player-ui 的 TaotaoSizes 刻度** —— 那个对象只收三端复用的元素固有尺寸。
// 判据与安卓端一致：能跨端复用的进 token，页面自己的布局常量在文件内命名。

/** 左侧导航栏宽度。 */
private val SidebarWidth = 232.dp

/** 侧边栏迷你播放器里音量条的宽度。 */
private val SidebarVolumeSliderWidth = 104.dp

/** 搜索按钮高度。比 Material 默认按钮更高，为了与左侧输入框齐平。 */
private val SearchButtonHeight = 56.dp

/** 「开始发现音乐」卡片里的圆形图标底衬。 */
private val DiscoverIconCircleSize = 52.dp

/** 搜索历史 chip 上删除按钮的触达区。 */
private val ChipRemoveButtonSize = 20.dp

/** 「加载更多」按钮里进度条的宽度。 */
private val LoadMoreProgressWidth = 60.dp

/**
 * 最近播放列表里，元信息文字相对行首的缩进。
 *
 * 必须与 [SharedSongRow] 的「行内边距 + 封面 + 文字列起点」对齐，否则这行小字
 * 不会落在歌曲标题下方。**故意写成推导式而不是 84** —— 封面尺寸一改就自动跟上。
 */
private val RecentMetaIndent =
    TaotaoSpacing.listItemHorizontal + TaotaoSizes.artworkRow + TaotaoSpacing.listItemHorizontal + TaotaoSpacing.sm

/** 播放页左栏的宽度范围。 */
private val NowPlayingColumnMinWidth = 330.dp
private val NowPlayingColumnMaxWidth = 430.dp

/** 音量百分比标签的固定宽度，避免数值位数变化时滑块左右抖动。 */
private val VolumePercentLabelWidth = 38.dp

/** 歌词面板上下留白：让首尾行也能滚到视口中央。 */
private val LyricPaneVerticalPadding = 90.dp

/**
 * 歌词行的行高，**所有行必须一致** —— 否则换句时 LazyColumn 整列会跳动。
 * 取 [TaotaoTypeScale.sectionTitle] 的 26sp，与安卓端 `LyricPanel` 同一口径。
 */
private val LyricLineHeight = TaotaoTypeScale.sectionTitle.lineHeight

/** 歌词当前行 / 非当前行的字号，同样取自 token 刻度。 */
private val LyricActiveFontSize = TaotaoTypeScale.sectionTitle.fontSize
private val LyricIdleFontSize = TaotaoTypeScale.body.fontSize

/** 播放队列抽屉宽度。 */
private val QueuePanelWidth = 380.dp

/** 队列序号列的固定宽度，避免序号到两位数时整行右移。 */
private val QueueIndexWidth = 24.dp

/** 队列行内上移 / 下移 / 移除按钮的触达区。 */
private val QueueActionButtonSize = 28.dp


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopShell(
    page: DesktopPage,
    onPageChange: (DesktopPage) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    source: String,
    onSourceChange: (String) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean,
    results: List<Song>,
    searchError: String?,
    totalResults: Int,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    searchHistory: List<String>,
    onHistorySearch: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    favorites: List<Song>,
    history: List<LocalHistory>,
    downloaded: List<Song>,
    playlists: List<DesktopMusicApi.Playlist>,
    selectedPlaylistId: Long?,
    selectedPlaylist: DesktopMusicApi.Playlist?,
    playlistLoading: Boolean,
    playlistError: String?,
    playlistAvailableSongs: List<Song>,
    apiEndpoint: String,
    downloadBusy: Set<String>,
    downloadProgress: Map<String, DesktopDownloadProgress>,
    favoriteBusy: Set<String>,
    currentSong: Song?,
    queue: List<Song>,
    currentIndex: Int,
    currentPositionMs: Int,
    durationMs: Int,
    playing: Boolean,
    playerBusy: Boolean,
    playerError: String?,
    lyric: Lyric,
    lyricLoading: Boolean,
    lyricError: String?,
    qualityOptions: List<DesktopMusicApi.QualityOption>,
    qualityOptionsLoading: Boolean,
    qualityOptionsError: String?,
    downloadTarget: Song?,
    downloadQualityOptions: List<DesktopMusicApi.QualityOption>,
    downloadQualityLoading: Boolean,
    downloadQualityError: String?,
    repeatMode: Int,
    volume: Float,
    showPlayer: Boolean,
    showQueue: Boolean,
    playbackQuality: AudioQuality,
    currentPlaybackQuality: AudioQuality,
    downloadQuality: AudioQuality,
    darkTheme: Boolean,
    sleepTimerRemainingMs: Long,
    message: String?,
    onPlay: (Song, List<Song>, Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    onTogglePlaying: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Int) -> Unit,
    onRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenPlayer: () -> Unit,
    onClosePlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    onCloseQueue: () -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onDownload: ((Song) -> Unit)?,
    onOpenDownloadQuality: (Song) -> Unit,
    onDownloadQualityPick: (Song, Int) -> Unit,
    onDismissDownloadQuality: () -> Unit,
    onRetryDownloadQuality: (Song) -> Unit,
    onQuality: (Int) -> Unit,
    onDeleteDownload: (Song) -> Unit,
    onClearHistory: () -> Unit,
    onRefreshPlaylists: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onAddPlaylistSong: (Long, Song) -> Unit,
    onRemovePlaylistSong: (Long, Song) -> Unit,
    onReorderPlaylist: (Long, List<Song>) -> Unit,
    onQueuePlay: (Int) -> Unit,
    onQueueRemove: (Int) -> Unit,
    onQueueMove: (Int, Int) -> Unit,
    onKeepCurrent: () -> Unit,
    onSetPlaybackQuality: (AudioQuality) -> Unit,
    onSetDownloadQuality: (AudioQuality) -> Unit,
    onToggleTheme: () -> Unit,
    onConfigureSleepTimer: (Long) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onSignOut: () -> Unit,
    onExit: () -> Unit,
) {
    val navItems = listOf(
        NavItem(DesktopPage.MUSIC, "音乐", Icons.Default.Home),
        NavItem(DesktopPage.PLAYLISTS, "云端歌单", Icons.Default.LibraryMusic),
        NavItem(DesktopPage.FAVORITES, "我的收藏", Icons.Default.Favorite),
        NavItem(DesktopPage.HISTORY, "最近播放", Icons.Default.History),
        NavItem(DesktopPage.DOWNLOADS, "本地下载", Icons.Default.Download),
        NavItem(DesktopPage.SETTINGS, "设置", Icons.Default.Settings),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showPlayer && currentSong != null) {
                SharedMiniPlayer(
                    state = PlayerUiState(
                        song = currentSong,
                        isPlaying = playing,
                        isBuffering = playerBusy,
                        positionMs = currentPositionMs.toLong(),
                        durationMs = durationMs.toLong(),
                        errorMessage = playerError,
                    ),
                    actions = PlayerActions(
                        onTogglePlaying = onTogglePlaying,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onSeek = {},
                        onToggleRepeat = {},
                    ),
                    onClick = onOpenPlayer,
                    artworkContent = {
                        AlbumCover(currentSong, size = 48)
                    },
                    supportingContent = {
                        if (sleepTimerRemainingMs > 0L) {
                            Text(
                                text = "定时停止 · ${formatSleepTimerRemaining(sleepTimerRemainingMs)}${if (playing) "" else " · 暂停计时"}",
                                color = Coral,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { onVolumeChange(if (volume > 0f) 0f else 1f) }) {
                            Icon(
                                imageVector = if (volume <= 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = if (volume <= 0f) "取消静音" else "静音",
                            )
                        }
                        Slider(
                            value = volume.coerceIn(0f, 1f),
                            onValueChange = onVolumeChange,
                            modifier = Modifier.width(SidebarVolumeSliderWidth),
                        )
                        IconButton(onClick = onOpenQueue) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放队列")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Surface(Modifier.width(SidebarWidth).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.md, vertical = TaotaoSpacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = TaotaoSpacing.xs)) {
                        Box(Modifier.size(BrandMarkSize).clip(TaotaoShapes.medium).background(Coral), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Color.White) }
                        Column(Modifier.padding(start = TaotaoSpacing.sm)) {
                            Text("桃桃音乐", fontWeight = FontWeight.Bold, style = TaotaoTypeScale.sectionTitle)
                            Text("Windows", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(TaotaoSpacing.xxl))
                    navItems.forEach { item ->
                        NavigationItem(item, selected = page == item.page, onClick = { onPageChange(item.page) })
                    }
                    Spacer(Modifier.weight(1f))
                    if (currentSong != null) {
                        Text("正在播放", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = TaotaoSpacing.xs).clip(TaotaoShapes.medium).clickable(onClick = onOpenPlayer).padding(TaotaoSpacing.xs)) {
                            AlbumCover(currentSong, size = 38)
                            Column(Modifier.padding(start = TaotaoSpacing.xs).weight(1f)) {
                                Text(currentSong.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(currentSong.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(TaotaoSpacing.sm))
                    TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null, modifier = Modifier.size(TaotaoSizes.iconXs)); Spacer(Modifier.width(TaotaoSpacing.xs)); Text("退出账号") }
                    TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("退出应用") }
                }
            }
            Divider(Modifier.fillMaxHeight().width(TaotaoStroke.thin))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (showPlayer && currentSong != null) {
                    PlayerDetail(
                        song = currentSong,
                        playing = playing,
                        playerBusy = playerBusy,
                        playerError = playerError,
                        positionMs = currentPositionMs,
                        durationMs = durationMs,
                        lyric = lyric,
                        lyricLoading = lyricLoading,
                        lyricError = lyricError,
                        qualityOptions = qualityOptions,
                        qualityOptionsLoading = qualityOptionsLoading,
                        qualityOptionsError = qualityOptionsError,
                        playbackQuality = currentPlaybackQuality,
                        downloaded = downloaded.any { DesktopStorage.songKey(it) == DesktopStorage.songKey(currentSong) },
                        repeatMode = repeatMode,
                        volume = volume,
                        sleepTimerRemainingMs = sleepTimerRemainingMs,
                        favorited = favorites.any { it.sameRemoteSong(currentSong) },
                        favoriteBusy = favoriteBusy.contains(DesktopStorage.songKey(currentSong)),
                        onBack = onClosePlayer,
                        onPrevious = onPrevious,
                        onNext = onNext,
                        onToggle = onTogglePlaying,
                        onRepeat = onRepeat,
                        onVolumeChange = onVolumeChange,
                        onSeek = onSeek,
                        onLyricSeek = onSeek,
                        onFavorite = { onToggleFavorite(currentSong) },
                        onShare = { onShare(currentSong) },
                        onDownload = { onOpenDownloadQuality(currentSong) },
                        onQueue = onOpenQueue,
                        onQuality = onQuality,
                        onCancelSleepTimer = onCancelSleepTimer,
                    )
                } else {
                    when (page) {
                        DesktopPage.MUSIC -> MusicPage(
                            query = query,
                            onQueryChange = onQueryChange,
                            source = source,
                            onSourceChange = onSourceChange,
                            onSearch = onSearch,
                            searching = searching,
                            results = results,
                            error = searchError,
                            total = totalResults,
                            hasMore = hasMore,
                            onLoadMore = onLoadMore,
                            searchHistory = searchHistory,
                            onHistorySearch = onHistorySearch,
                            onHistoryRemove = onHistoryRemove,
                            onHistoryClear = onHistoryClear,
                            favorites = favorites,
                            downloaded = downloaded,
                            favoriteBusy = favoriteBusy,
                            downloadBusy = downloadBusy,
                            downloadProgress = downloadProgress,
                            currentSong = currentSong,
                            onPlay = onPlay,
                            onFavorite = onToggleFavorite,
                            onDownload = onOpenDownloadQuality,
                            onNext = onPlayNext,
                        )
                        DesktopPage.PLAYLISTS -> DesktopPlaylistsPage(
                            playlists = playlists,
                            selectedPlaylistId = selectedPlaylistId,
                            selectedPlaylist = selectedPlaylist,
                            availableSongs = playlistAvailableSongs,
                            endpoint = apiEndpoint,
                            quality = playbackQuality,
                            loading = playlistLoading,
                            error = playlistError,
                            onRefresh = onRefreshPlaylists,
                            onSelect = onSelectPlaylist,
                            onCreate = onCreatePlaylist,
                            onRename = onRenamePlaylist,
                            onDelete = onDeletePlaylist,
                            onAddSong = onAddPlaylistSong,
                            onRemoveSong = onRemovePlaylistSong,
                            onMoveSong = onReorderPlaylist,
                            onPlay = onPlay,
                        )
                        DesktopPage.FAVORITES -> LibraryPage(
                            title = "我的收藏",
                            subtitle = "${favorites.size} 首歌曲 · 云端同步",
                            songs = favorites,
                            emptyTitle = "还没有收藏",
                            emptyDescription = "在搜索结果或播放页点击心形收藏歌曲",
                            currentSong = currentSong,
                            favorites = favorites,
                            downloaded = downloaded,
                            favoriteBusy = favoriteBusy,
                            downloadBusy = downloadBusy,
                            downloadProgress = downloadProgress,
                            onPlay = onPlay,
                            onFavorite = onToggleFavorite,
                            onDownload = onOpenDownloadQuality,
                            onNext = onPlayNext,
                        )
                        DesktopPage.HISTORY -> HistoryPage(
                            entries = history,
                            currentSong = currentSong,
                            favorites = favorites,
                            downloaded = downloaded,
                            favoriteBusy = favoriteBusy,
                            downloadBusy = downloadBusy,
                            downloadProgress = downloadProgress,
                            onPlay = { song, list, index -> onPlay(song, list, index) },
                            onFavorite = onToggleFavorite,
                            onDownload = onOpenDownloadQuality,
                            onNext = onPlayNext,
                            onClear = onClearHistory,
                        )
                        DesktopPage.DOWNLOADS -> LibraryPage(
                            title = "本地下载",
                            subtitle = "${downloaded.size} 首歌曲 · 无网络也能播放",
                            songs = downloaded,
                            emptyTitle = "还没有离线歌曲",
                            emptyDescription = "在歌曲行的下载按钮保存音频、封面和歌词",
                            currentSong = currentSong,
                            favorites = favorites,
                            downloaded = downloaded,
                            favoriteBusy = favoriteBusy,
                            downloadBusy = downloadBusy,
                            downloadProgress = downloadProgress,
                            onPlay = onPlay,
                            onFavorite = onToggleFavorite,
                            onDownload = null,
                            onNext = onPlayNext,
                            onDelete = onDeleteDownload,
                        )
                        DesktopPage.SETTINGS -> SettingsPage(
                            playbackQuality = playbackQuality,
                            downloadQuality = downloadQuality,
                            darkTheme = darkTheme,
                            playing = playing,
                            sleepTimerRemainingMs = sleepTimerRemainingMs,
                            onPlaybackQuality = onSetPlaybackQuality,
                            onDownloadQuality = onSetDownloadQuality,
                            onToggleTheme = onToggleTheme,
                            onConfigureSleepTimer = onConfigureSleepTimer,
                            onCancelSleepTimer = onCancelSleepTimer,
                        )
                    }
                }
                if (showQueue && queue.isNotEmpty()) {
                    QueuePanel(
                        queue = queue,
                        currentIndex = currentIndex,
                        onClose = onCloseQueue,
                        onPlay = onQueuePlay,
                        onRemove = onQueueRemove,
                        onMove = onQueueMove,
                        onKeepCurrent = onKeepCurrent,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                message?.let { text ->
                    Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = TaotaoSpacing.md), color = MaterialTheme.colorScheme.inverseSurface, shape = TaotaoShapes.small) {
                        Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = TaotaoSpacing.md, vertical = TaotaoSpacing.sm))
                    }
                }
                downloadTarget?.let { song ->
                    val choices = downloadQualityOptions
                    AlertDialog(
                        onDismissRequest = onDismissDownloadQuality,
                        title = { Text("下载音质") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxs)) {
                                Text(song.title, fontWeight = FontWeight.Bold)
                                if (downloadQualityLoading) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(TaotaoSizes.progressInline), color = Coral)
                                        Text("正在查询可用音质…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = TaotaoSpacing.xs))
                                    }
                                } else {
                                    downloadQualityError?.let { error ->
                                        Text(error, color = Coral, style = MaterialTheme.typography.bodySmall)
                                        TextButton(
                                            onClick = { onRetryDownloadQuality(song) },
                                            enabled = !downloadQualityLoading,
                                        ) { Text("重试查询") }
                                    }
                                    if (choices.isEmpty() && downloadQualityError == null) {
                                        Text("暂无可用音质", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    }
                                    choices.forEach { option ->
                                        TextButton(
                                            onClick = { onDownloadQualityPick(song, option.quality) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(option.label.ifBlank { labelOfQuality(option.quality) })
                                                if (option.size > 0L) Text(formatBytes(option.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = onDismissDownloadQuality) { Text("取消") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val foreground = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(TaotaoShapes.button).background(background).clickable(onClick = onClick).padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(item.icon, item.label, tint = foreground, modifier = Modifier.size(TaotaoSizes.iconSm))
        Text(item.label, color = foreground, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(start = TaotaoSpacing.sm))
    }
    Spacer(Modifier.height(TaotaoSpacing.xxs))
}

@Composable
private fun MusicPage(
    query: String,
    onQueryChange: (String) -> Unit,
    source: String,
    onSourceChange: (String) -> Unit,
    onSearch: () -> Unit,
    searching: Boolean,
    results: List<Song>,
    error: String?,
    total: Int,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    searchHistory: List<String>,
    onHistorySearch: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    favorites: List<Song>,
    downloaded: List<Song>,
    favoriteBusy: Set<String>,
    downloadBusy: Set<String>,
    downloadProgress: Map<String, DesktopDownloadProgress>,
    currentSong: Song?,
    onPlay: (Song, List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onNext: (Song) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = TaotaoSpacing.xxl, vertical = TaotaoSpacing.xl),
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("音乐", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("搜索、播放和管理你的音乐库", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = TaotaoSpacing.xxs))
                }
                if (currentSong != null) Text("${results.size} 首搜索结果", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(TaotaoSpacing.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("搜索歌曲、歌手或专辑") },
                    leadingIcon = { Icon(Icons.Default.Search, "搜索") },
                    trailingIcon = { if (query.isNotBlank()) IconButton(onClick = onSearch) { Icon(Icons.Default.ChevronRight, "开始搜索") } },
                )
                Spacer(Modifier.width(TaotaoSpacing.sm))
                Button(onClick = onSearch, enabled = query.isNotBlank() && !searching, modifier = Modifier.height(SearchButtonHeight)) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(TaotaoSizes.iconSm)); Spacer(Modifier.width(TaotaoSpacing.xs)); Text("搜索")
                }
            }
            Row(Modifier.padding(top = TaotaoSpacing.sm), horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                SourceChip("全部音源", "all", source, onSourceChange)
                SourceChip("QQ 音乐", "tencent", source, onSourceChange)
                SourceChip("网易云", "netease", source, onSourceChange)
            }
        }
        if (results.isEmpty() && !searching && error == null) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xl), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(TaotaoSpacing.xl), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(DiscoverIconCircleSize).clip(CircleShape).background(Coral), contentAlignment = Alignment.Center) { Icon(Icons.Default.LibraryMusic, null, tint = Color.White, modifier = Modifier.size(TaotaoSizes.iconLg)) }
                        Column(Modifier.padding(start = TaotaoSpacing.md)) {
                            Text("开始发现音乐", style = TaotaoTypeScale.sectionTitle, fontWeight = FontWeight.Bold)
                            Text("搜索结果支持在线播放、收藏、下载和同步歌词", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = TaotaoSpacing.xxs))
                        }
                    }
                }
                if (searchHistory.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xl, bottom = TaotaoSpacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近搜索", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onHistoryClear) { Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(TaotaoSizes.iconXs)); Spacer(Modifier.width(TaotaoSpacing.xxs)); Text("清空") }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                        searchHistory.forEach { value ->
                            InputChip(
                                selected = false,
                                onClick = { onHistorySearch(value) },
                                label = { Text(value) },
                                trailingIcon = {
                                    IconButton(onClick = { onHistoryRemove(value) }, modifier = Modifier.size(ChipRemoveButtonSize)) { Icon(Icons.Default.Close, "删除 $value", modifier = Modifier.size(TaotaoSizes.iconXs)) }
                                },
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(if (searching && results.isEmpty()) "正在搜索…" else if (total > 0) "搜索结果 · 共 $total 首" else "搜索结果", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = TaotaoSpacing.xl, bottom = TaotaoSpacing.xs))
                if (searching && results.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Coral)
                if (error != null && results.isEmpty()) {
                    SharedContentState(
                        type = SharedContentStateType.ERROR,
                        title = "搜索失败",
                        description = error,
                    )
                } else {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = TaotaoSpacing.sm)) }
                }
            }
            itemsIndexed(results, key = { _, song -> DesktopStorage.songKey(song) }) { index, song ->
                DesktopSongRow(
                    song = song,
                    active = currentSong?.let { DesktopStorage.songKey(it) == DesktopStorage.songKey(song) } == true,
                    favorited = favorites.any { it.sameRemoteSong(song) },
                    downloaded = downloaded.any { DesktopStorage.songKey(it) == DesktopStorage.songKey(song) },
                    favoriteBusy = favoriteBusy.contains(DesktopStorage.songKey(song)),
                    downloadBusy = downloadBusy.contains(DesktopStorage.songKey(song)),
                    downloadProgress = downloadProgress[DesktopStorage.songKey(song)],
                    onClick = { onPlay(song, results, index) },
                    onFavorite = { onFavorite(song) },
                    onDownload = onDownload?.let { callback -> { callback(song) } },
                    onNext = { onNext(song) },
                )
            }
            if (hasMore) {
                item {
                    OutlinedButton(onClick = onLoadMore, enabled = !searching, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(vertical = TaotaoSpacing.md)) {
                        if (searching) LinearProgressIndicator(Modifier.width(LoadMoreProgressWidth), color = Coral) else Text("加载更多")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, value: String, selected: String, onClick: (String) -> Unit) {
    FilterChip(selected = selected == value, onClick = { onClick(value) }, label = { Text(label) })
}

@Composable
private fun DesktopSongRow(
    song: Song,
    active: Boolean,
    favorited: Boolean,
    downloaded: Boolean,
    favoriteBusy: Boolean,
    downloadBusy: Boolean,
    downloadProgress: DesktopDownloadProgress? = null,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onDownload: (() -> Unit)?,
    onNext: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    SharedSongRow(
        song = song,
        active = active,
        downloaded = downloaded,
        subtitle = "${song.artist} · ${song.source.sourceLabel()}",
        artworkContent = { AlbumCover(song, size = 52) },
        onClick = onClick,
        supportingContent = {
            if (downloadBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xxs)) {
                    downloadProgress?.fraction?.let { fraction ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f).height(TaotaoStroke.thick), color = Coral)
                    } ?: LinearProgressIndicator(modifier = Modifier.weight(1f).height(TaotaoStroke.thick), color = Coral)
                    Text(
                        downloadProgress?.let { progress ->
                            if (progress.totalBytes > 0L) "${((progress.fraction ?: 0f) * 100).toInt()}%" else formatBytes(progress.completedBytes)
                        } ?: "下载中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = TaotaoSpacing.xs),
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onFavorite, enabled = !favoriteBusy) { Icon(if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favorited) "取消收藏" else "收藏", tint = if (favorited) Coral else MaterialTheme.colorScheme.onSurfaceVariant) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.List, "更多操作") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("下一首播放") }, leadingIcon = { Icon(Icons.Default.QueuePlayNext, null) }, onClick = { menu = false; onNext() })
                    if (!downloaded && onDownload != null) {
                        DropdownMenuItem(
                            text = { Text(if (downloadBusy) "正在下载…" else "下载到本地") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            enabled = !downloadBusy,
                            onClick = { menu = false; onDownload() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = modifier.clip(TaotaoShapes.badge).background(color.copy(alpha = 0.13f)).padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.tightVertical))
}

@Composable
private fun LibraryPage(
    title: String,
    subtitle: String,
    songs: List<Song>,
    emptyTitle: String,
    emptyDescription: String,
    currentSong: Song?,
    favorites: List<Song>,
    downloaded: List<Song>,
    favoriteBusy: Set<String>,
    downloadBusy: Set<String> = emptySet(),
    downloadProgress: Map<String, DesktopDownloadProgress> = emptyMap(),
    onPlay: (Song, List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onDownload: ((Song) -> Unit)?,
    onNext: (Song) -> Unit,
    onDelete: ((Song) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.xxl, vertical = TaotaoSpacing.xl)) {
        PageHeading(title, subtitle)
        if (songs.isEmpty()) {
            EmptyState(emptyTitle, emptyDescription, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                itemsIndexed(songs, key = { _, song -> DesktopStorage.songKey(song) }) { index, song ->
                    DesktopSongRow(
                        song = song,
                        active = currentSong?.let { DesktopStorage.songKey(it) == DesktopStorage.songKey(song) } == true,
                        favorited = favorites.any { it.sameRemoteSong(song) },
                        downloaded = downloaded.any { DesktopStorage.songKey(it) == DesktopStorage.songKey(song) },
                        favoriteBusy = favoriteBusy.contains(DesktopStorage.songKey(song)),
                        downloadBusy = downloadBusy.contains(DesktopStorage.songKey(song)),
                        downloadProgress = downloadProgress[DesktopStorage.songKey(song)],
                        onClick = { onPlay(song, songs, index) },
                        onFavorite = { onFavorite(song) },
                        onDownload = onDownload?.let { callback -> { callback(song) } },
                        onNext = { onNext(song) },
                    )
                    if (onDelete != null) TextButton(onClick = { onDelete(song) }, modifier = Modifier.align(Alignment.End)) { Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(TaotaoSizes.iconXs)); Spacer(Modifier.width(TaotaoSpacing.xxs)); Text("删除本地文件") }
                }
            }
        }
    }
}

@Composable
private fun HistoryPage(
    entries: List<LocalHistory>,
    currentSong: Song?,
    favorites: List<Song>,
    downloaded: List<Song>,
    favoriteBusy: Set<String>,
    downloadBusy: Set<String> = emptySet(),
    downloadProgress: Map<String, DesktopDownloadProgress> = emptyMap(),
    onPlay: (Song, List<Song>, Int) -> Unit,
    onFavorite: (Song) -> Unit,
    onDownload: ((Song) -> Unit)?,
    onNext: (Song) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.xxl, vertical = TaotaoSpacing.xl)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { PageHeading("最近播放", "${entries.size} 首歌曲 · 本地即时记录，登录后同步云端") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(TaotaoSizes.iconXs))
                Spacer(Modifier.width(TaotaoSpacing.xxs))
                Text("清空记录")
            }
        }
        if (entries.isEmpty()) EmptyState("还没有播放记录", "播放过的歌曲会显示在这里", Modifier.weight(1f))
        else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
            itemsIndexed(entries, key = { _, entry -> DesktopStorage.songKey(entry.song) }) { index, entry ->
                DesktopSongRow(
                    song = entry.song,
                    active = currentSong?.let { DesktopStorage.songKey(it) == DesktopStorage.songKey(entry.song) } == true,
                    favorited = favorites.any { it.sameRemoteSong(entry.song) },
                    downloaded = downloaded.any { DesktopStorage.songKey(it) == DesktopStorage.songKey(entry.song) },
                    favoriteBusy = favoriteBusy.contains(DesktopStorage.songKey(entry.song)),
                    downloadBusy = downloadBusy.contains(DesktopStorage.songKey(entry.song)),
                    downloadProgress = downloadProgress[DesktopStorage.songKey(entry.song)],
                    onClick = { onPlay(entry.song, entries.map { it.song }, index) },
                    onFavorite = { onFavorite(entry.song) },
                    onDownload = onDownload?.let { callback -> { callback(entry.song) } },
                    onNext = { onNext(entry.song) },
                )
                Text("${formatDate(entry.playedAt)} · 播放 ${entry.playCount} 次 · 听过 ${formatListenMs(entry.totalListenedMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = RecentMetaIndent, bottom = TaotaoSpacing.xxs))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空最近播放？") },
            text = { Text("该操作会同步到同一账号的其他设备。") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = TaotaoSpacing.lg)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = TaotaoSpacing.xxs))
    }
}

@Composable
private fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    SharedContentState(
        type = SharedContentStateType.EMPTY,
        title = title,
        description = description,
        modifier = modifier,
    )
}

@Composable
private fun PlayerDetail(
    song: Song,
    playing: Boolean,
    playerBusy: Boolean,
    playerError: String?,
    positionMs: Int,
    durationMs: Int,
    lyric: Lyric,
    lyricLoading: Boolean,
    lyricError: String?,
    qualityOptions: List<DesktopMusicApi.QualityOption>,
    qualityOptionsLoading: Boolean,
    qualityOptionsError: String?,
    playbackQuality: AudioQuality,
    downloaded: Boolean,
    repeatMode: Int,
    volume: Float,
    sleepTimerRemainingMs: Long,
    favorited: Boolean,
    favoriteBusy: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
    onRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Int) -> Unit,
    onLyricSeek: (Int) -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onQueue: () -> Unit,
    onQuality: (Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
) {
    var qualityMenu by remember { mutableStateOf(false) }
    val selectableQualityValues = AudioQuality.entries.map(AudioQuality::value).toSet()
    // 音质查询成功但只返回 Windows 不支持的容器时保持为空，避免把必然失败的档位伪装成可选项。
    val selectableQualities = qualityOptions.filter { it.quality in selectableQualityValues }
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.xxl, vertical = TaotaoSpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            Text("正在播放", style = TaotaoTypeScale.sectionTitle, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列") }
        }
        Row(Modifier.fillMaxSize().padding(top = TaotaoSpacing.sm), horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxxl)) {
            Column(Modifier.widthIn(min = NowPlayingColumnMinWidth, max = NowPlayingColumnMaxWidth).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(TaotaoSpacing.xl))
                AlbumCover(song, size = 300)
                PlayerWideLayout(
                    state = PlayerUiState(
                        song = song,
                        isPlaying = playing,
                        isBuffering = playerBusy,
                        positionMs = positionMs.toLong(),
                        durationMs = durationMs.toLong(),
                        repeatMode = when (repeatMode) {
                            2 -> PlayerRepeatMode.ONE
                            1 -> PlayerRepeatMode.ALL
                            else -> PlayerRepeatMode.OFF
                        },
                        errorMessage = playerError,
                    ),
                    actions = PlayerActions(
                        onTogglePlaying = onToggle,
                        onSeek = { onSeek(it.toInt()) },
                        onToggleRepeat = onRepeat,
                        onPrevious = onPrevious,
                        onNext = onNext,
                    ),
                    positionLabel = formatTime(positionMs),
                    durationLabel = formatTime(durationMs).takeIf { durationMs > 0 } ?: "--:--",
                    titleTrailingContent = {
                        if (song.vip) Badge("VIP", Coral, Modifier.padding(start = TaotaoSpacing.xs))
                    },
                    headerActions = {
                        IconButton(onClick = onShare) {
                            Icon(Icons.Default.Share, "分享歌曲", tint = Coral)
                        }
                        IconButton(onClick = onFavorite, enabled = !favoriteBusy) {
                            Icon(if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favorited) "取消收藏" else "收藏", tint = if (favorited) Coral else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier.padding(top = TaotaoSpacing.xl),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.xxl, vertical = TaotaoSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onVolumeChange(if (volume > 0f) 0f else 1f) }) {
                        Icon(if (volume <= 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp, if (volume <= 0f) "取消静音" else "静音")
                    }
                    Slider(
                        value = volume.coerceIn(0f, 1f),
                        onValueChange = onVolumeChange,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(VolumePercentLabelWidth), textAlign = TextAlign.End)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                    OutlinedButton(onClick = onDownload, enabled = song.hasRemoteIdentity() && !downloaded) {
                        Icon(if (downloaded) Icons.Default.Check else Icons.Default.Download, if (downloaded) "已下载" else "下载", modifier = Modifier.size(TaotaoSizes.iconXs))
                        Spacer(Modifier.width(TaotaoSpacing.xxs))
                        Text(if (downloaded) "已下载" else "下载")
                    }
                    Box {
                        OutlinedButton(onClick = { qualityMenu = true }, enabled = !downloaded && !qualityOptionsLoading && selectableQualities.isNotEmpty()) { Text(playbackQuality.label) }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
                            selectableQualities.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.label.ifBlank { labelOfQuality(option.quality) })
                                            if (option.size > 0) Text(formatBytes(option.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    leadingIcon = { if (option.quality == playbackQuality.value) Icon(Icons.Default.Check, "当前音质") },
                                    onClick = { qualityMenu = false; onQuality(option.quality) },
                                )
                            }
                        }
                    }
                }
                if (sleepTimerRemainingMs > 0L) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = TaotaoSpacing.sm),
                    ) {
                        Icon(Icons.Default.Timer, "定时播放", tint = Coral, modifier = Modifier.size(TaotaoSizes.iconSm))
                        Text(
                            "定时停止 · ${formatSleepTimerRemaining(sleepTimerRemainingMs)}${if (playing) "" else " · 暂停计时"}",
                            color = Coral,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = TaotaoSpacing.xs),
                        )
                        TextButton(onClick = onCancelSleepTimer) {
                            Icon(Icons.Default.TimerOff, "取消定时播放", modifier = Modifier.size(TaotaoSizes.iconXs))
                            Spacer(Modifier.width(TaotaoSpacing.xxs))
                            Text("取消")
                        }
                    }
                }
                qualityOptionsError?.let { Text(it, color = Coral, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = TaotaoSpacing.xxs)) }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = TaotaoSpacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        Text("歌词", style = TaotaoTypeScale.sectionTitle, fontWeight = FontWeight.Bold)
                        Text(if (lyric.hasWords) "逐字同步" else if (lyric.synced) "逐行同步" else "纯文本", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = TaotaoSpacing.xxs))
                    }
                    if (lyricLoading) Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    lyricError?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodySmall) }
                }
                Surface(Modifier.fillMaxSize().clip(TaotaoShapes.card), color = MaterialTheme.colorScheme.surface) {
                    DesktopLyricPane(lyric, positionMs, onLyricSeek, Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.xxl))
                }
            }
        }
    }
}

@Composable
private fun DesktopLyricPane(lyric: Lyric, positionMs: Int, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (lyric.isEmpty) {
        EmptyState("暂无歌词", "这首歌没有可用的歌词数据", modifier)
        return
    }
    val current = lyric.indexAt(positionMs)
    val listState = rememberLazyListState()
    // 逐帧播放位置只由当前逐字行读取，避免普通歌词行跟着每一帧重组。
    val positionMsState = rememberUpdatedState(positionMs)
    LaunchedEffect(current, lyric.lines.size) {
        if (current >= 0 && current < lyric.lines.size) {
            listState.animateScrollToItem(current, scrollOffset = -80)
        }
    }
    LazyColumn(state = listState, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = LyricPaneVerticalPadding), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.md)) {
        itemsIndexed(lyric.lines, key = { index, line -> "${line.timeMs}-$index" }) { index, line ->
            val active = lyric.synced && index == current
            val rowModifier = Modifier
                .fillMaxWidth()
                .clip(TaotaoShapes.small)
                .clickable(enabled = lyric.synced) { onSeek(line.timeMs) }
                .padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.xxs)
            if (active && line.words.isNotEmpty()) {
                DesktopKaraokeLine(
                    words = line.words,
                    positionMsState = positionMsState,
                    modifier = rowModifier,
                )
            } else {
                Text(
                    text = line.text,
                    fontSize = if (active) LyricActiveFontSize else LyricIdleFontSize,
                    lineHeight = LyricLineHeight,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = rowModifier,
                )
            }
        }
    }
}

/** 桌面端逐字歌词与 Android 保持相同：每个字独立按自身时间轴渐变。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopKaraokeLine(
    words: List<LyricWord>,
    positionMsState: State<Int>,
    modifier: Modifier = Modifier,
) {
    val sung = MaterialTheme.colorScheme.primary
    val unsung = MaterialTheme.colorScheme.onSurfaceVariant
    val positionMs by positionMsState
    // 18sp / 26sp / Bold 正好是 TaotaoTypeScale.sectionTitle，只覆盖对齐方式：
    // 字号、行高、字重都跟着 token 走，不再各写一遍。
    val style = TaotaoTypeScale.sectionTitle.copy(textAlign = TextAlign.Center)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        words.forEach { word ->
            Text(
                text = word.text,
                style = style.copy(brush = desktopLyricBrush(fractionOf(word, positionMs), sung, unsung)),
            )
        }
    }
}

private fun fractionOf(word: LyricWord, positionMs: Int): Float = when {
    positionMs >= word.endMs -> 1f
    positionMs <= word.timeMs -> 0f
    word.durationMs <= 0 -> 1f
    else -> ((positionMs - word.timeMs).toFloat() / word.durationMs).coerceIn(0f, 1f)
}

private fun desktopLyricBrush(fraction: Float, sung: Color, unsung: Color): Brush {
    val edge = fraction.coerceIn(0f, 1f)
    if (edge <= DesktopLyricEdgeEpsilon) return SolidColor(unsung)
    if (edge >= 1f - DesktopLyricEdgeEpsilon) return SolidColor(sung)
    return Brush.horizontalGradient(
        0f to sung,
        edge to sung,
        (edge + DesktopLyricEdgeEpsilon) to unsung,
        1f to unsung,
    )
}

private const val DesktopLyricEdgeEpsilon = 0.002f

@Composable
private fun QueuePanel(
    queue: List<Song>,
    currentIndex: Int,
    onClose: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onKeepCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxHeight().width(QueuePanelWidth), color = MaterialTheme.colorScheme.surface, shadowElevation = TaotaoElevation.overlay) {
        Column(Modifier.fillMaxSize().padding(TaotaoSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("播放队列", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${queue.size} 首歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onKeepCurrent, enabled = queue.size > 1) { Text("只留当前") }
                IconButton(onClick = onClose) { Icon(Icons.Default.ChevronRight, "关闭队列") }
            }
            Divider(Modifier.padding(vertical = TaotaoSpacing.xs))
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                itemsIndexed(queue, key = { index, song -> "${DesktopStorage.songKey(song)}-$index" }) { index, song ->
                    Row(Modifier.fillMaxWidth().clip(TaotaoShapes.button).background(if (index == currentIndex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { onPlay(index) }.padding(TaotaoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(QueueIndexWidth), textAlign = TextAlign.Center)
                        AlbumCover(song, size = 38)
                        Column(Modifier.weight(1f).padding(start = TaotaoSpacing.xs)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal); Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (index > 0) IconButton(onClick = { onMove(index, index - 1) }, modifier = Modifier.size(QueueActionButtonSize)) { Icon(Icons.Default.ArrowUpward, "上移", modifier = Modifier.size(TaotaoSizes.iconXs)) }
                        if (index < queue.lastIndex) IconButton(onClick = { onMove(index, index + 1) }, modifier = Modifier.size(QueueActionButtonSize)) { Icon(Icons.Default.ArrowDownward, "下移", modifier = Modifier.size(TaotaoSizes.iconXs)) }
                        IconButton(onClick = { onRemove(index) }, enabled = index != currentIndex, modifier = Modifier.size(QueueActionButtonSize)) { Icon(Icons.Default.DeleteOutline, "移除", modifier = Modifier.size(TaotaoSizes.iconSm)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    playbackQuality: AudioQuality,
    downloadQuality: AudioQuality,
    darkTheme: Boolean,
    playing: Boolean,
    sleepTimerRemainingMs: Long,
    onPlaybackQuality: (AudioQuality) -> Unit,
    onDownloadQuality: (AudioQuality) -> Unit,
    onToggleTheme: () -> Unit,
    onConfigureSleepTimer: (Long) -> Unit,
    onCancelSleepTimer: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TaotaoSpacing.xxl, vertical = TaotaoSpacing.xl)) {
        PageHeading("设置", "Windows 端音乐体验偏好")
        SettingSection("播放") {
            QualitySetting("默认播放音质", "支持标准、HQ、无损、Hi-Res 与臻品母带", playbackQuality, onPlaybackQuality)
            SettingDivider()
            QualitySetting("默认下载音质", "下载有损或无损文件并支持离线播放", downloadQuality, onDownloadQuality)
            SettingDivider()
            SleepTimerSetting(
                playing = playing,
                remainingMs = sleepTimerRemainingMs,
                onConfigure = onConfigureSleepTimer,
                onCancel = onCancelSleepTimer,
            )
        }
        Spacer(Modifier.height(TaotaoSpacing.lg))
        SettingSection("外观") {
            Row(Modifier.fillMaxWidth().padding(TaotaoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (darkTheme) Icons.Default.DarkMode else Icons.Default.LightMode, null, tint = Coral)
                Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) { Text("深色主题", fontWeight = FontWeight.Bold); Text(if (darkTheme) "当前使用深色主题" else "当前使用浅色主题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FilterChip(selected = darkTheme, onClick = onToggleTheme, label = { Text(if (darkTheme) "深色" else "浅色") })
            }
        }
        Spacer(Modifier.height(TaotaoSpacing.lg))
        SettingSection("关于") {
            Row(Modifier.fillMaxWidth().padding(TaotaoSpacing.md), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, tint = Coral); Column(Modifier.padding(start = TaotaoSpacing.sm)) { Text("桃桃音乐 Windows", fontWeight = FontWeight.Bold); Text("音乐功能优先版本 · AI 与 IM 暂未接入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Coral, modifier = Modifier.padding(bottom = TaotaoSpacing.xs))
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = TaotaoShapes.card) { Column { content() } }
}

@Composable
private fun SettingDivider() = Divider(Modifier.padding(horizontal = TaotaoSpacing.md))

@Composable
private fun QualitySetting(title: String, subtitle: String, selected: AudioQuality, onSelected: (AudioQuality) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(TaotaoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box {
            FilterChip(selected = false, onClick = { expanded = true }, label = { Text(selected.label) }, leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(TaotaoSizes.iconXs)) })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AudioQuality.entries.forEach { quality -> DropdownMenuItem(text = { Text(quality.label) }, onClick = { expanded = false; onSelected(quality) }) }
            }
        }
    }
}

@Composable
private fun SleepTimerSetting(
    playing: Boolean,
    remainingMs: Long,
    onConfigure: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    var customDialog by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }
    val active = remainingMs > 0L
    Column(Modifier.fillMaxWidth().padding(TaotaoSpacing.md), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Default.Timer else Icons.Default.TimerOff, "定时播放", tint = Coral)
            Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) {
                Text("定时停止", fontWeight = FontWeight.Bold)
                Text(
                    if (active) "${if (playing) "播放中" else "已暂停计时"} · ${formatSleepTimerRemaining(remainingMs)} 后停止当前歌曲，不会自动播放下一首"
                    else "播放一段时间后自动停止当前歌曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) {
                TextButton(onClick = onCancel) {
                    Icon(Icons.Default.TimerOff, "取消定时播放", modifier = Modifier.size(TaotaoSizes.iconXs))
                    Spacer(Modifier.width(TaotaoSpacing.xxs))
                    Text("取消")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
        ) {
            SLEEP_TIMER_PRESET_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = false,
                    onClick = { onConfigure(sleepTimerDurationMs(minutes) ?: return@FilterChip) },
                    label = { Text("${minutes} 分钟") },
                )
            }
            OutlinedButton(onClick = { customDialog = true }) { Text("自定义") }
        }
    }
    if (customDialog) {
        AlertDialog(
            onDismissRequest = { customDialog = false },
            title = { Text("自定义定时停止") },
            text = {
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { value -> customMinutes = value.filter(Char::isDigit).take(4) },
                    label = { Text("分钟") },
                    singleLine = true,
                    supportingText = { Text("请输入 1-${SLEEP_TIMER_MAX_MINUTES} 分钟") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minutes = customMinutes.toIntOrNull()
                        val duration = minutes?.let(::sleepTimerDurationMs)
                        if (duration != null) {
                            customDialog = false
                            customMinutes = ""
                            onConfigure(duration)
                        }
                    },
                    enabled = customMinutes.toIntOrNull()?.let { it in 1..SLEEP_TIMER_MAX_MINUTES } == true,
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { customDialog = false }) { Text("取消") } },
        )
    }
}

private fun String.cleanDisplay(): String = replace(Regex("\\s+"), " ").trim()
private fun String.sourceLabel(): String = when (lowercase()) { "netease" -> "网易云"; else -> "QQ 音乐" }
private fun formatTime(ms: Int): String = "%02d:%02d".format(Locale.ROOT, ms.coerceAtLeast(0) / 60_000, (ms.coerceAtLeast(0) / 1_000) % 60)
private fun formatListenMs(ms: Long): String = if (ms < 60_000) "${ms / 1_000} 秒" else "${ms / 60_000} 分钟"
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}
private fun formatDate(ms: Long): String = if (ms <= 0) "最近" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(ms))
private fun repeatLabel(mode: Int): String = when (mode) { 1 -> "列表循环"; 2 -> "单曲循环"; else -> "顺序播放" }
