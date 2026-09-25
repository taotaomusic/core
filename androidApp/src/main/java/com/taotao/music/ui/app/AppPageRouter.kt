package com.taotao.music.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.account.AccountProfilePage
import com.taotao.music.ui.ai.AiStudioPage
import com.taotao.music.ui.common.MusicSearchBar
import com.taotao.music.ui.home.AnnouncementPreview
import com.taotao.music.ui.home.HomeHeader
import com.taotao.music.ui.home.RecommendationCard
import com.taotao.music.ui.library.DiaryRecordsPage
import com.taotao.music.ui.library.MineLibrarySection
import com.taotao.music.ui.library.MusicLibraryPage
import com.taotao.music.ui.library.PlaybackHistoryPage
import com.taotao.music.ui.library.SongDiaryPage
import com.taotao.music.ui.mine.MinePage
import com.taotao.music.ui.player.PlayerDetailPage
import com.taotao.music.ui.player.QualitySheetKind
import com.taotao.music.ui.playlist.PlaylistDetailPage
import com.taotao.music.ui.playlist.PlaylistEditorMode
import com.taotao.music.ui.playlist.PlaylistLibraryPage
import com.taotao.music.ui.search.SearchPage
import com.taotao.music.ui.settings.SettingsPage
import com.taotao.music.ui.chat.ChatPageHost
import com.taotao.music.data.im.ImConnectionInfo
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.pageTransition
import com.taotao.music.update.UpdateStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面分发：把 currentPage 映射到具体页面并把全局状态装配进去。
 *
 * 详情页是最顶层浮层：从日记页点迷你播放器要能盖在日记之上进入详情，
 * 所以它必须排在 song-diary 前面，否则 diarySong 非空时详情永远显示不出来。
 */
@Composable
internal fun TaotaoAppPageRouter(
    state: TaotaoAppState,
    connection: ImConnectionInfo,
    imUid: String?,
    innerPadding: PaddingValues,
) {
    val currentPage = when {
        state.showPlayerDetail -> "detail"
        state.diarySong != null && state.showDiaryRecords -> "diary-records"
        state.diarySong != null -> "song-diary"
        state.showProfilePage -> "profile"
        state.showSettingsPage -> "settings"
        state.showSearchPage -> "search"
        state.mineLibrarySection == MineLibrarySection.FAVORITES -> "mine-favorites"
        state.mineLibrarySection == MineLibrarySection.HISTORY -> "mine-history"
        state.mineLibrarySection == MineLibrarySection.LOCAL -> "mine-local"
        state.playlist.selected != null -> "playlist-detail"
        state.mineLibrarySection == MineLibrarySection.PLAYLISTS -> "mine-playlists"
        // 底部标签也参与：不带上它的话音乐 ⇄ 我的是硬切，
        // 而其它换页都有过渡，观感上很不一致。
        state.bottomTab == 1 -> "ai"
        state.bottomTab == 2 -> "chat"
        state.bottomTab == 3 -> "mine"
        else -> "home"
    }
    val pageReduceMotion = LocalReduceMotion.current
    if (currentPage == "chat") {
        // 聊天页直接挂载，切出时立即释放抽屉和消息列表，不与目标页并行布局。
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            ChatPageHost(
                connection = connection,
                savedPeers = state.imPeers,
                peerStore = state.imPeerStore,
                accountId = state.authSession.accountId,
                client = state.wukongImClient,
                onPeersChanged = { state.imPeers = it },
                onMessage = { state.message = it },
            )
        }
        return
    }
    AnimatedContent(
        targetState = currentPage,
        transitionSpec = { pageTransition(pageReduceMotion) },
        label = "页面切换",
    ) { page ->
        // 日记两页要沉浸式绘制顶部珊瑚渐变（画到状态栏底下），顶部内边距由页面自己用
        // statusBarsPadding 让开；其余页面维持 Scaffold 的统一顶部内边距。
        val pagePadding = if (page == "song-diary" || page == "diary-records") {
            PaddingValues(bottom = innerPadding.calculateBottomPadding())
        } else {
            innerPadding
        }
        Box(Modifier.fillMaxSize().padding(pagePadding)) {
            when (page) {
                "ai" -> AiStudioPage(
                    signedIn = state.signedIn,
                    submitting = state.imageGenerating,
                    task = state.imageTask,
                    onQueryTask = { taskId -> withContext(Dispatchers.IO) { state.musicApi.requestImageTask(taskId) } },
                    onGenerate = { model, prompt, ratio, imageSize, quality, _ ->
                        state.generateImage(model, prompt, ratio, imageSize, quality)
                    },
                )
                "detail" -> if (state.playbackSongs.isNotEmpty()) PlayerDetailPageRoute(state)
                "search" -> SearchPageRoute(state)
                "profile" -> state.userProfile?.let { profile ->
                    AccountProfilePage(
                        api = state.musicApi,
                        profile = profile,
                        onProfileChanged = { state.userProfile = it },
                        onMessage = { state.message = it },
                        onBack = { state.showProfilePage = false },
                    )
                }
                "settings" -> SettingsPageRoute(state)
                "mine-favorites" -> MusicLibraryPage(
                    title = "收藏夹",
                    subtitle = "${state.favoriteLibrarySongs.size} 首 · 本地优先，后台同步账号收藏",
                    songs = state.favoriteLibrarySongs,
                    emptyTitle = "收藏夹还是空的",
                    emptyDescription = "点击歌曲旁的心形后，会通过收藏接口同步到这里",
                    onBack = { state.mineLibrarySection = null },
                    onSongClick = { index -> state.playSong(state.favoriteLibrarySongs, index) },
                    isFavorite = { song -> state.favoritesStore.contains(song) },
                    onToggleFavorite = { song -> state.toggleFavorite(song) },
                    onPlayNext = { song -> state.playNext(song) },
                    onAddToPlaylist = { song -> state.playlist.requestAddSong(song) },
                    onOpenDiary = { song -> state.openSongDiary(song) },
                    error = state.favoriteLibraryError,
                    onRetry = { state.refreshFavoriteLibrary() },
                )
                "song-diary" -> state.diarySong?.let { song ->
                    SongDiaryPage(
                        song = song,
                        diary = state.songDiary,
                        loading = state.diaryLoading,
                        error = state.diaryError,
                        onBack = { state.diarySong = null },
                        onRetry = { state.openSongDiary(song) },
                        onShare = { state.requestSongShare(song) },
                        onOpenRecords = { state.showDiaryRecords = true },
                    )
                }
                "diary-records" -> state.diarySong?.let { song ->
                    // 日记的「播放记录」下级页：能点进来时日记画像必然已加载，直接读它的记录。
                    DiaryRecordsPage(
                        song = song,
                        records = state.songDiary?.records.orEmpty(),
                        onBack = { state.showDiaryRecords = false },
                    )
                }
                "mine-history" -> {
                    LaunchedEffect(page) { state.refreshPlaybackHistory() }
                    PlaybackHistoryPage(
                        history = state.playbackHistory,
                        onBack = { state.mineLibrarySection = null },
                        onSongClick = { index -> state.playSong(state.playbackHistory.map { it.song }, index) },
                        onPlayNext = { song -> state.playNext(song) },
                        isFavorite = { song -> state.favoritesStore.contains(song) },
                        onToggleFavorite = { song -> state.toggleFavorite(song) },
                        onOpenDiary = { song -> state.openSongDiary(song) },
                        onClear = { state.clearPlaybackHistory() },
                    )
                }
                "mine-local" -> MusicLibraryPage(
                    title = "本地歌曲",
                    subtitle = "已下载到当前设备 · ${state.downloadedSongs.size} 首",
                    songs = state.downloadedSongs,
                    emptyTitle = "还没有本地歌曲",
                    emptyDescription = "下载完成的音乐会集中显示在这里",
                    onBack = { state.mineLibrarySection = null },
                    onSongClick = { index -> state.playSong(state.downloadedSongs, index) },
                    isFavorite = { song -> state.favoritesStore.contains(song) },
                    onToggleFavorite = { song -> state.toggleFavorite(song) },
                    onPlayNext = { song -> state.playNext(song) },
                    onDelete = { song -> state.pendingDelete = song },
                )
                "mine-playlists" -> PlaylistLibraryPage(
                    playlists = state.playlist.items,
                    loading = state.playlist.loading,
                    error = state.playlist.error,
                    onBack = { state.mineLibrarySection = null },
                    onRefresh = { state.playlist.refresh() },
                    onCreate = { state.playlist.editorMode = PlaylistEditorMode.CREATE },
                    onOpen = { state.playlist.open(it) },
                    onRename = { state.playlist.editorMode = PlaylistEditorMode.EDIT(it) },
                    onDelete = { state.playlist.remove(it) },
                )
                "playlist-detail" -> state.playlist.selected?.let { playlist ->
                    PlaylistDetailPage(
                        playlist = playlist,
                        // 下载列表必须放在去重候选之前，确保歌单详情能直接关联本机 file: 音频。
                        knownSongs = state.downloadedSongs + state.playlistCandidates,
                        onBack = { state.playlist.selected = null },
                        onPlayAll = { songs -> state.playSong(songs, 0) },
                        onPlaySong = { songs, index -> state.playSong(songs, index) },
                        onRemoveSong = { state.playlist.removeSong(it) },
                        onMoveSong = { from, to -> state.playlist.moveSong(from, to) },
                        onAddSong = { state.playlist.openSongPickerFor(playlist) },
                        onRename = { state.playlist.editorMode = PlaylistEditorMode.EDIT(playlist) },
                        onDelete = { state.playlist.remove(playlist) },
                    )
                }
                "mine" -> MinePage(
                    onLogout = { state.signOut() },
                    onOpenSettings = { state.showSettingsPage = true },
                    versionName = state.updateManager.installedVersionName,
                    checking = state.updateManager.status.stage == UpdateStage.CHECKING,
                    onCheckUpdate = { state.scope.launch { state.updateManager.check(manual = true) } },
                    onMessage = { state.message = it },
                    favoriteCount = remember(state.favoriteRevision) { state.favoritesStore.ids().size },
                    historyCount = state.playbackHistory.size,
                    localCount = state.downloadedSongs.size,
                    onOpenFavorites = {
                        state.mineLibrarySection = MineLibrarySection.FAVORITES
                        state.refreshFavoriteLibrary()
                    },
                    onOpenHistory = { state.mineLibrarySection = MineLibrarySection.HISTORY },
                    onOpenLocal = { state.mineLibrarySection = MineLibrarySection.LOCAL },
                    onOpenPlaylists = { state.playlist.openLibrary() },
                    playlistCount = state.playlist.items.size,
                    profile = state.userProfile,
                    profileLoading = state.profileLoading,
                    imUid = imUid,
                    onOpenAccount = { state.showSettingsPage = true },
                )
                else -> HomePage(state)
            }
        }
    }
}

/** 首页：问候、搜索入口、公告预览与推荐卡。 */
@Composable
private fun HomePage(state: TaotaoAppState) {
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
        Spacer(Modifier.height(TaotaoSpacing.xl))
        HomeHeader(
            userName = state.userProfile?.nickname ?: state.userProfile?.username ?: "音乐爱好者",
            onOpenAnnouncements = { state.showAnnouncementDialog = true },
        )
        MusicSearchBar(
            state.search.keyword,
            onKeywordChanged = { state.search.onKeywordInput(it) },
            onSearch = {
                if (state.search.keyword.isNotBlank()) {
                    state.openSearchPage()
                    state.search.start()
                }
            },
            onFocus = { state.openSearchPage() },
        )
        Text(
            "在线搜索歌曲，下载后可在无网络时播放",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = TaotaoSpacing.xs),
        )
        if (state.announcements.isNotEmpty()) {
            AnnouncementPreview(state.announcements.first(), onClick = { state.showAnnouncementDialog = true })
        }
        SharedSectionHeader(title = "今日推荐", level = SharedSectionLevel.SECTION)
        RecommendationCard()
        Spacer(Modifier.height(TaotaoSpacing.xs))
    }
}

/** 播放详情页装配：队列、收藏、下载、音质与定时关闭全部来自全局状态。 */
@Composable
private fun PlayerDetailPageRoute(state: TaotaoAppState) {
    PlayerDetailPage(
        song = state.playbackSongs[state.selectedIndex.coerceIn(state.playbackSongs.indices)],
        audioPlayer = state.audioPlayer,
        isPlaying = state.isPlaying,
        onBack = { state.showPlayerDetail = false },
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
        onShare = { state.requestSongShare(state.playbackSongs[state.selectedIndex.coerceIn(state.playbackSongs.indices)]) },
        isFavorite = { song -> state.favoritesStore.contains(song) },
        onToggleSongFavorite = { song -> state.toggleFavorite(song) },
        onMessage = { state.message = it },
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
    )
}

/** 搜索页装配：关键词、结果流、分页与历史全部来自搜索状态。 */
@Composable
private fun SearchPageRoute(state: TaotaoAppState) {
    SearchPage(
        keyword = state.search.keyword,
        songs = state.search.results,
        isSearching = state.search.isSearching,
        hasSearched = state.search.hasSearched,
        errorMessage = state.search.error,
        onBack = { state.showSearchPage = false },
        onKeywordChanged = { state.search.onKeywordInput(it) },
        onSearch = { state.search.start() },
        history = state.search.history,
        suggestions = state.search.suggestions,
        hotSearches = state.search.hotSearches,
        onQuickSearch = { value -> state.search.quickSearch(value) },
        onHistoryClick = { value -> state.search.onHistoryClick(value) },
        onHistoryRemove = { value -> state.search.removeHistory(value) },
        onHistoryClear = { state.search.clearHistory() },
        historyEnabled = state.search.historyEnabled,
        onHistoryEnabledChanged = { enabled -> state.search.onHistoryEnabledChanged(enabled) },
        onHistoryReorder = { reordered -> state.search.replaceHistory(reordered) },
        onSongClick = { index, _ -> state.playSong(state.search.results, index) },
        // 读一下 revision 让收藏变化能触发重组：SharedPreferences 本身不可观察。
        favoriteRevision = state.favoriteRevision,
        isFavorite = { song -> state.favoritesStore.contains(song) },
        onToggleFavorite = { song -> state.toggleFavorite(song) },
        onPlayNext = { song -> state.playNext(song) },
        onAddToPlaylist = { song -> state.playlist.requestAddSong(song) },
        downloadedRevision = state.downloadedSongs.size,
        isDownloaded = { song -> song.remoteId != null && song.remoteId in state.downloadedIds },
        onLoadMore = { state.search.loadMore() },
        isLoadingMore = state.search.isLoadingMore,
        hasMore = state.search.hasMore,
        total = state.search.total,
        searchSession = state.search.generation,
    )
}

/** 设置页装配：音质、外观、资料与定时关闭。 */
@Composable
private fun SettingsPageRoute(state: TaotaoAppState) {
    SettingsPage(
        playbackQuality = state.playbackQuality,
        downloadQuality = state.downloadQuality,
        sleepTimerRemainingMs = state.audioPlayer.sleepTimerRemainingMs,
        sleepTimerWaitingSongEnd = state.audioPlayer.sleepTimerWaitingSongEnd,
        appearance = state.appearance,
        profile = state.userProfile,
        profileLoading = state.profileLoading,
        onOpenProfile = { state.openProfilePage() },
        onPickPlaybackQuality = { state.qualitySheet = QualitySheetKind.PLAYBACK_DEFAULT },
        onPickDownloadQuality = { state.qualitySheet = QualitySheetKind.DOWNLOAD_DEFAULT },
        onOpenSleepTimer = { state.showSleepTimerDialog = true },
        onPickAppearance = { mode -> state.applyAppearance(mode) },
        onBack = { state.showSettingsPage = false },
    )
}
