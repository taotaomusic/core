package com.taotao.music.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.taotao.music.model.Song
import com.taotao.music.data.AlbumSearchResult
import com.taotao.music.data.ArtistSearchResult
import com.taotao.music.data.OnlinePlaylistDetail
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.toMvSong
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.account.AccountProfilePage
import com.taotao.music.ui.ai.AiStudioPage
import com.taotao.music.ui.album.AlbumPage
import com.taotao.music.ui.artist.ArtistPage
import com.taotao.music.ui.common.MusicSearchBar
import com.taotao.music.ui.home.AnnouncementPreview
import com.taotao.music.ui.home.HomeHeader
import com.taotao.music.ui.home.RankingEntryRow
import com.taotao.music.ui.home.RecommendationCard
import com.taotao.music.ui.library.DiaryRecordsPage
import com.taotao.music.ui.library.MineLibrarySection
import com.taotao.music.ui.library.MusicLibraryPage
import com.taotao.music.ui.library.PlaybackHistoryPage
import com.taotao.music.ui.library.SongDiaryPage
import com.taotao.music.ui.mine.MinePage
import com.taotao.music.ui.player.MvPlayerPage
import com.taotao.music.ui.player.PlayerDetailPage
import com.taotao.music.ui.player.QualitySheetKind
import com.taotao.music.ui.playlist.OnlinePlaylistPage
import com.taotao.music.ui.playlist.PlaylistDetailPage
import com.taotao.music.ui.playlist.PlaylistEditorMode
import com.taotao.music.ui.playlist.PlaylistLibraryPage
import com.taotao.music.ui.ranking.RankingPage
import com.taotao.music.ui.search.SearchPage
import com.taotao.music.ui.settings.SettingsPage
import com.taotao.music.ui.chat.ChatPageHost
import com.taotao.music.data.im.ImConnectionInfo
import com.taotao.music.ui.theme.AnimationCurves
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.pageTransition
import com.taotao.music.ui.theme.taotaoTween
import com.taotao.music.update.UpdateStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面分发：把 currentPage 映射到具体页面并把全局状态装配进去。
 *
 * 详情页是最顶层页面：从日记页点迷你播放器要能盖在日记之上进入详情，
 * 所以它必须排在 song-diary 前面，否则 diarySong 非空时详情永远显示不出来。
 * 歌手 / 专辑 / 在线歌单主页是从搜索页或排行榜歌曲行菜单进入的下级页：必须排在
 * search 之前，否则搜索标志仍然为真时永远显示宿主页（互跳时同一时刻只会保留一个
 * 目标，见 [TaotaoAppState.openArtistPage] 与 [TaotaoAppState.openOnlinePlaylistPage]）。
 * 排行榜从首页进入，是与 search 同层的第 1 层下级页，排在所有底部标签之前。
 * MV 播放页不是页面分支，而是盖在详情之上的独立浮层（见 [MvPlayerOverlay]）：
 * 从详情进出 MV 只动浮层本身，详情页保持在组合中原位不动，返回时不重播升起动画。
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
        // 歌手 / 专辑 / 在线歌单主页排在 search 之前：三者都是从搜索页进入的下级页。
        state.artistPage.selected != null -> "artist"
        state.albumPage.selected != null -> "album"
        state.onlinePlaylistPage.selected != null -> "online-playlist"
        // 排行榜从首页（第 0 层）进入，是与 search 同层的第 1 层下级页：宿主是 when 链
        // 末尾的 home 分支，所以只需排在所有底部标签之前即可。榜单歌曲行还能把
        // 歌手 / 专辑页叠进来，后两者的分支排在更上面先被截获。
        state.showRankingPage -> "ranking"
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
                ownAvatarUrl = state.userProfile?.avatarUrl,
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
        // 日记两页与歌手 / 专辑、在线歌单、排行榜主页要沉浸式绘制顶部珊瑚渐变
        // （画到状态栏底下），顶部内边距由页面自己用 statusBarsPadding 让开；
        // 其余页面维持 Scaffold 的统一顶部内边距。MV 播放页已拆成独立浮层。
        val pagePadding = if (page == "song-diary" || page == "diary-records" ||
            page == "artist" || page == "album" || page == "online-playlist" ||
            page == "ranking"
        ) {
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
                "ranking" -> RankingPageRoute(state)
                "artist" -> state.artistPage.selected?.let { target ->
                    ArtistPageRoute(state, target)
                }
                "album" -> state.albumPage.selected?.let { target ->
                    AlbumPageRoute(state, target)
                }
                "online-playlist" -> state.onlinePlaylistPage.selected?.let { target ->
                    OnlinePlaylistPageRoute(state, target)
                }
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
    // MV 播放页浮层：组合顺序就是绘制层级，它画在 AnimatedContent 里的所有页面之上。
    MvPlayerOverlay(state)
}

/**
 * MV 播放页浮层。
 *
 * 不参与页面 AnimatedContent：进出 MV 只动浮层本身，详情页保持在组合中原位不动，
 * 返回时不会重播升起动画。退出动画期间 mvSong 已被置空，用 lastSong 快照撑住
 * 画面到动画结束，否则会闪一帧浅色背景。
 */
@Composable
private fun MvPlayerOverlay(state: TaotaoAppState) {
    val reduceMotion = LocalReduceMotion.current
    val lastSong = remember { mutableStateOf<Song?>(null) }
    state.mvSong?.let { lastSong.value = it }
    // 与详情页同一套手势语言：从底部升起，关闭时落回（降级动效只保留透明度）。
    val enter = if (reduceMotion) {
        fadeIn(taotaoTween(AnimationDurations.FADE))
    } else {
        slideInVertically(
            animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.emphasizedIn),
        ) { height -> height } + fadeIn(taotaoTween(AnimationDurations.MICRO))
    }
    val exit = if (reduceMotion) {
        fadeOut(taotaoTween(AnimationDurations.FADE))
    } else {
        slideOutVertically(
            animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.emphasizedOut),
        ) { height -> height } + fadeOut(
            animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.standardOut),
        )
    }
    AnimatedVisibility(visible = state.mvSong != null, enter = enter, exit = exit) {
        lastSong.value?.let { song -> MvPlayerPageRoute(state, song) }
    }
}

/** 首页：问候、搜索入口、排行榜入口、公告预览与推荐卡。 */
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
        // 排行榜入口：放在搜索提示与公告卡之间，点击进排行榜目录（第 1 层下级页）。
        RankingEntryRow(onOpenRankings = { state.openRankingPage() })
        if (state.announcements.isNotEmpty()) {
            AnnouncementPreview(state.announcements.first(), onClick = { state.showAnnouncementDialog = true })
        }
        SharedSectionHeader(title = "今日推荐", level = SharedSectionLevel.SECTION)
        RecommendationCard()
        Spacer(Modifier.height(TaotaoSpacing.xs))
    }
}

/**
 * MV 播放页装配。
 *
 * 进入页面时暂停歌曲并记住进入前是否在播，退出时接着原来的状态继续 ——
 * 不做互斥的话 MV 声音会和歌曲音频叠在一起。记忆值放在 remember 里，
 * 重组不会重读；页面盖住详情页，期间歌曲不可能被切走。
 */
@Composable
private fun MvPlayerPageRoute(state: TaotaoAppState, song: Song) {
    val wasPlaying = remember { state.audioPlayer.isPlaying }
    DisposableEffect(Unit) {
        state.audioPlayer.pause()
        onDispose {
            if (wasPlaying) state.audioPlayer.resume()
        }
    }
    MvPlayerPage(
        song = song,
        musicApi = state.musicApi,
        onBack = { state.mvSong = null },
    )
}

/** 播放详情页装配：队列、收藏、下载、音质与定时关闭全部来自全局状态。 */
@Composable
private fun PlayerDetailPageRoute(state: TaotaoAppState) {    PlayerDetailPage(
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
        onOpenArtist = { song -> state.openArtistPageFromSong(song) },
        onOpenAlbum = { song -> state.openAlbumPageFromSong(song) },
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
        artists = state.search.searchArtists,
        albums = state.search.searchAlbums,
        // 歌手 / 专辑区块点击进入对应主页（搜索域的下级页）；
        // 页面开关与互跳时收起对方的逻辑集中在状态层（openArtistPage / openAlbumPage）。
        onArtistClick = { state.openArtistPage(it) },
        onAlbumClick = { state.openAlbumPage(it) },
        // ---- 七标签（综合 / 单曲 / 歌手 / 专辑 / 歌单 / 视频 / 歌词）----
        searchTab = state.search.searchTab,
        onTabSelected = { state.search.selectTab(it) },
        tabArtists = state.search.tabArtists,
        tabArtistsLoading = state.search.tabArtistsLoading,
        tabArtistsLoadingMore = state.search.tabArtistsLoadingMore,
        tabArtistsHasMore = state.search.tabArtistsHasMore,
        tabArtistsError = state.search.tabArtistsError,
        tabArtistsTotal = state.search.tabArtistsTotal,
        tabAlbums = state.search.tabAlbums,
        tabAlbumsLoading = state.search.tabAlbumsLoading,
        tabAlbumsLoadingMore = state.search.tabAlbumsLoadingMore,
        tabAlbumsHasMore = state.search.tabAlbumsHasMore,
        tabAlbumsError = state.search.tabAlbumsError,
        tabAlbumsTotal = state.search.tabAlbumsTotal,
        tabPlaylists = state.search.tabPlaylists,
        tabPlaylistsLoading = state.search.tabPlaylistsLoading,
        tabPlaylistsLoadingMore = state.search.tabPlaylistsLoadingMore,
        tabPlaylistsHasMore = state.search.tabPlaylistsHasMore,
        tabPlaylistsError = state.search.tabPlaylistsError,
        tabPlaylistsTotal = state.search.tabPlaylistsTotal,
        tabVideos = state.search.tabVideos,
        tabVideosLoading = state.search.tabVideosLoading,
        tabVideosLoadingMore = state.search.tabVideosLoadingMore,
        tabVideosHasMore = state.search.tabVideosHasMore,
        tabVideosError = state.search.tabVideosError,
        tabVideosTotal = state.search.tabVideosTotal,
        tabLyrics = state.search.tabLyrics,
        tabLyricsLoading = state.search.tabLyricsLoading,
        tabLyricsLoadingMore = state.search.tabLyricsLoadingMore,
        tabLyricsHasMore = state.search.tabLyricsHasMore,
        tabLyricsError = state.search.tabLyricsError,
        tabLyricsTotal = state.search.tabLyricsTotal,
        onEnsureTabArtists = { state.search.ensureTabArtists() },
        onEnsureTabAlbums = { state.search.ensureTabAlbums() },
        onEnsureTabPlaylists = { state.search.ensureTabPlaylists() },
        onEnsureTabVideos = { state.search.ensureTabVideos() },
        onEnsureTabLyrics = { state.search.ensureTabLyrics() },
        onLoadMoreTabArtists = { state.search.loadMoreTabArtists() },
        onLoadMoreTabAlbums = { state.search.loadMoreTabAlbums() },
        onLoadMoreTabPlaylists = { state.search.loadMoreTabPlaylists() },
        onLoadMoreTabVideos = { state.search.loadMoreTabVideos() },
        onLoadMoreTabLyrics = { state.search.loadMoreTabLyrics() },
        // 「歌单」标签条目点击进在线歌单详情页（搜索行自带 sourceMarker 寻址钥匙，
        // 见 PlaylistState 之外的 OnlinePlaylistPageState；与账号云端歌单详情是两套路由）。
        onPlaylistClick = { state.openOnlinePlaylistPage(it) },
        // 点视频 = 播 MV：复用详情页 onOpenMv 的同一入口（mvSong 浮层），
        // 由 toMvSong 把视频条目折算成 MV 链路需要的歌曲句柄。
        onVideoClick = { video -> state.mvSong = video.toMvSong() },
        // 点歌词条目 = 播放该歌：整条歌词列表作为队列上下文入队（与歌曲标签点歌同机制）。
        onLyricClick = { index, _ -> state.playSong(state.search.tabLyrics, index) },
        // 歌曲行菜单「查看歌手 / 查看专辑」：从歌曲构造跳转目标，pic 等取值规则
        // 集中在状态层（openArtistPageFromSong / openAlbumPageFromSong），
        // 播放详情页头部的同名跳转共用这一套构造逻辑。
        onOpenArtist = { song -> state.openArtistPageFromSong(song) },
        onOpenAlbum = { song -> state.openAlbumPageFromSong(song) },
        searchSession = state.search.generation,
    )
}

/**
 * 排行榜页装配：目录与详情两个视图共用一个页面状态，视图切换由 rankingPage.selected 决定。
 * 点歌曲行以「整条榜单」为队列上下文播放（与搜索页点歌同一机制）；
 * 歌曲行菜单的「查看歌手 / 查看专辑」复用从歌曲构造跳转目标的状态层逻辑，
 * 打开时不收起排行榜 —— 歌手 / 专辑页层级更高，关闭后自然落回榜单详情。
 */
@Composable
private fun RankingPageRoute(state: TaotaoAppState) {
    RankingPage(
        groups = state.rankingPage.groups,
        catalogLoading = state.rankingPage.catalogLoading,
        catalogError = state.rankingPage.catalogError,
        selected = state.rankingPage.selected,
        ranking = state.rankingPage.detail,
        songs = state.rankingPage.songs,
        songsLoading = state.rankingPage.songsLoading,
        songsLoadingMore = state.rankingPage.songsLoadingMore,
        songsHasMore = state.rankingPage.songsHasMore,
        songsError = state.rankingPage.songsError,
        songsTotal = state.rankingPage.songsTotal,
        // 读一下 revision 让收藏变化能触发重组：SharedPreferences 本身不可观察。
        favoriteRevision = state.favoriteRevision,
        downloadedRevision = state.downloadedSongs.size,
        isFavorite = { song -> state.favoritesStore.contains(song) },
        isDownloaded = { song -> song.remoteId != null && song.remoteId in state.downloadedIds },
        onOpenDetail = { brief -> state.rankingPage.openRankingDetail(brief) },
        onCloseDetail = { state.rankingPage.closeDetail() },
        onClose = {
            state.showRankingPage = false
            state.rankingPage.close()
        },
        onRetryCatalog = { state.rankingPage.ensureCatalog() },
        onRetryDetail = { state.rankingPage.retry() },
        onLoadMoreSongs = { state.rankingPage.loadMoreSongs() },
        onSongClick = { index -> state.playSong(state.rankingPage.songs, index) },
        onToggleFavorite = { song -> state.toggleFavorite(song) },
        onPlayNext = { song -> state.playNext(song) },
        onAddToPlaylist = { song -> state.playlist.requestAddSong(song) },
        onOpenArtist = { song -> state.openArtistPageFromSong(song) },
        onOpenAlbum = { song -> state.openAlbumPageFromSong(song) },
    )
}

/**
 * 歌手主页装配：详情、三个标签的区块与分页全部来自歌手页状态；
 * 点击歌曲以「歌手名下歌曲列表」为整条队列上下文播放（与搜索页点歌同一机制）。
 */
@Composable
private fun ArtistPageRoute(state: TaotaoAppState, target: ArtistSearchResult) {
    ArtistPage(
        target = target,
        detail = state.artistPage.artist,
        detailLoading = state.artistPage.detailLoading,
        detailError = state.artistPage.detailError,
        songs = state.artistPage.songs,
        songsLoading = state.artistPage.songsLoading,
        songsLoadingMore = state.artistPage.songsLoadingMore,
        songsHasMore = state.artistPage.songsHasMore,
        songsError = state.artistPage.songsError,
        albums = state.artistPage.albums,
        albumsLoading = state.artistPage.albumsLoading,
        albumsLoadingMore = state.artistPage.albumsLoadingMore,
        albumsHasMore = state.artistPage.albumsHasMore,
        similar = state.artistPage.similar,
        similarLoading = state.artistPage.similarLoading,
        // 读一下 revision 让收藏变化能触发重组：SharedPreferences 本身不可观察。
        favoriteRevision = state.favoriteRevision,
        downloadedRevision = state.downloadedSongs.size,
        isFavorite = { song -> state.favoritesStore.contains(song) },
        isDownloaded = { song -> song.remoteId != null && song.remoteId in state.downloadedIds },
        onBack = { state.artistPage.close() },
        onRetry = { state.artistPage.retry() },
        onLoadMoreSongs = { state.artistPage.loadMoreSongs() },
        onLoadMoreAlbums = { state.artistPage.loadMoreAlbums() },
        onSongClick = { index -> state.playSong(state.artistPage.songs, index) },
        onToggleFavorite = { song -> state.toggleFavorite(song) },
        onPlayNext = { song -> state.playNext(song) },
        onAddToPlaylist = { song -> state.playlist.requestAddSong(song) },
        // 专辑卡片 → 专辑页；宿主会先收起歌手页，保持一次只有一层下级页。
        onAlbumClick = { album -> state.openAlbumPage(album) },
        // 相似歌手 → 原地替换当前歌手目标，页面内自行回到顶部。
        onSimilarArtistClick = { artist -> state.openArtistPage(artist) },
        onOpenAlbumsTab = { state.artistPage.ensureAlbums() },
        onOpenSimilarTab = { state.artistPage.ensureSimilar() },
    )
}

/** 专辑页装配：详情与歌曲分页来自专辑页状态；头部歌手名跳转歌手页，形成互跳。 */
@Composable
private fun AlbumPageRoute(state: TaotaoAppState, target: AlbumSearchResult) {
    AlbumPage(
        target = target,
        detail = state.albumPage.album,
        detailLoading = state.albumPage.detailLoading,
        detailError = state.albumPage.detailError,
        songs = state.albumPage.songs,
        songsLoading = state.albumPage.songsLoading,
        songsLoadingMore = state.albumPage.songsLoadingMore,
        songsHasMore = state.albumPage.songsHasMore,
        songsError = state.albumPage.songsError,
        favoriteRevision = state.favoriteRevision,
        downloadedRevision = state.downloadedSongs.size,
        isFavorite = { song -> state.favoritesStore.contains(song) },
        isDownloaded = { song -> song.remoteId != null && song.remoteId in state.downloadedIds },
        onBack = { state.albumPage.close() },
        onRetry = { state.albumPage.retry() },
        onLoadMoreSongs = { state.albumPage.loadMoreSongs() },
        onSongClick = { index -> state.playSong(state.albumPage.songs, index) },
        onToggleFavorite = { song -> state.toggleFavorite(song) },
        onPlayNext = { song -> state.playNext(song) },
        onAddToPlaylist = { song -> state.playlist.requestAddSong(song) },
        onArtistClick = { artistId, artistName ->
            // 专辑详情 / 搜索区块只有歌手名与 ID，没有头像：歌手详情加载前用名字兜底，
            // 资料到达后头像与统计自动补齐。
            state.openArtistPage(
                ArtistSearchResult(
                    source = TencentMusicApi.SEARCH_SOURCE_KUWO,
                    id = artistId,
                    name = artistName,
                    pic = null,
                    songCount = 0,
                    albumCount = 0,
                ),
            )
        },
    )
}

/** 在线歌单页装配：详情与歌曲分页来自在线歌单页状态；寻址参数（id + sourceMarker）已在状态层记录。 */
@Composable
private fun OnlinePlaylistPageRoute(state: TaotaoAppState, target: OnlinePlaylistDetail) {
    OnlinePlaylistPage(
        detail = target,
        detailLoading = state.onlinePlaylistPage.detailLoading,
        detailError = state.onlinePlaylistPage.detailError,
        songs = state.onlinePlaylistPage.songs,
        songsLoading = state.onlinePlaylistPage.songsLoading,
        songsLoadingMore = state.onlinePlaylistPage.songsLoadingMore,
        songsHasMore = state.onlinePlaylistPage.songsHasMore,
        songsError = state.onlinePlaylistPage.songsError,
        favoriteRevision = state.favoriteRevision,
        downloadedRevision = state.downloadedSongs.size,
        isFavorite = { song -> state.favoritesStore.contains(song) },
        isDownloaded = { song -> song.remoteId != null && song.remoteId in state.downloadedIds },
        onBack = { state.closeOnlinePlaylistPage() },
        onRetry = { state.onlinePlaylistPage.retry() },
        onLoadMoreSongs = { state.onlinePlaylistPage.loadMoreSongs() },
        onSongClick = { index -> state.playSong(state.onlinePlaylistPage.songs, index) },
        onToggleFavorite = { song -> state.toggleFavorite(song) },
        onPlayNext = { song -> state.playNext(song) },
        onAddToPlaylist = { song -> state.playlist.requestAddSong(song) },
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
