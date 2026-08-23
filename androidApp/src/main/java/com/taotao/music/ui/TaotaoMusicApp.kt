package com.taotao.music.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.taotao.music.model.AudioQuality
import com.taotao.music.model.LyricParser
import com.taotao.music.model.Song
import com.taotao.music.data.AppearanceMode
import com.taotao.music.data.AppearanceStore
import com.taotao.music.data.CrashLog
import com.taotao.music.data.CrashReporter
import com.taotao.music.data.DownloadNotifier
import com.taotao.music.data.FavoritesStore
import com.taotao.music.data.OfflineDownloadManager
import com.taotao.music.data.QualityStore
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.SearchHistoryStore
import com.taotao.music.data.AuthSession
import com.taotao.music.data.PlaybackStateStore
import com.taotao.music.data.SavedPlaybackState
import com.taotao.music.player.AudioPlayer
import com.taotao.music.update.UpdateManager
import com.taotao.music.update.UpdateStage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.lifecycle.LifecycleEventObserver
import android.net.Uri
import java.io.File

@Composable
fun TaotaoMusicApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val authSession = remember { AuthSession(context) }
    val audioPlayer = remember { AudioPlayer(context) }
    val musicApi = remember { TencentMusicApi(authSession) }
    val downloadManager = remember { OfflineDownloadManager(context, authSession) }
    val playbackStateStore = remember { PlaybackStateStore(context) }
    val searchHistoryStore = remember { SearchHistoryStore(context) }
    val favoritesStore = remember { FavoritesStore(context) }
    val qualityStore = remember { QualityStore(context) }
    val downloadNotifier = remember { DownloadNotifier(context) }
    val appearanceStore = remember { AppearanceStore(context) }
    var appearance by remember { mutableStateOf(appearanceStore.mode()) }
    val scope = rememberCoroutineScope()
    var playbackSongs by remember { mutableStateOf(emptyList<Song>()) }
    var searchResults by remember { mutableStateOf(emptyList<Song>()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchKeyword by remember { mutableStateOf("") }
    var showPlayerDetail by remember { mutableStateOf(false) }
    var showSearchPage by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var repeatMode by remember { mutableIntStateOf(androidx.media3.common.Player.REPEAT_MODE_OFF) }
    var downloadedSongs by remember { mutableStateOf(emptyList<Song>()) }
    var bottomTab by remember { mutableIntStateOf(0) }
    var searchHistory by remember { mutableStateOf(searchHistoryStore.read()) }
    var signedIn by remember { mutableStateOf(authSession.isSignedIn) }
    var restoredPlayback by remember { mutableStateOf<SavedPlaybackState?>(null) }
    var pendingResumePositionMs by remember { mutableIntStateOf(0) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    /** 分页状态。服务端一直在返回 hasMore / total，客户端以前直接丢掉。 */
    var searchPage by remember { mutableIntStateOf(1) }
    var searchHasMore by remember { mutableStateOf(false) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var isLoadingMore by remember { mutableStateOf(false) }
    /** 收藏缓存被改动后自增，让读了它的界面重新组合 —— SharedPreferences 本身不是可观察的。 */
    var favoriteRevision by remember { mutableIntStateOf(0) }
    var showSettingsPage by remember { mutableStateOf(false) }
    var playbackQuality by remember { mutableStateOf(qualityStore.playbackQuality()) }
    var downloadQuality by remember { mutableStateOf(qualityStore.downloadQuality()) }
    /** 待删除确认的已下载歌曲。删除是不可逆的，不做二次确认容易误触。 */
    var pendingDelete by remember { mutableStateOf<Song?>(null) }
    /** 待下载的歌与它在队列里的位置。非空即弹出音质面板。 */
    var downloadTarget by remember { mutableStateOf<Pair<Song, Int>?>(null) }
    /** 音质面板的用途。 */
    var qualitySheet by remember { mutableStateOf<QualitySheetKind?>(null) }
    var songQualities by remember { mutableStateOf<List<QualityChoice>>(emptyList()) }
    var qualitiesLoading by remember { mutableStateOf(false) }
    val latestPlaybackSongs = rememberUpdatedState(playbackSongs)
    val latestSelectedIndex = rememberUpdatedState(selectedIndex)

    /** 播放状态由播放器事件驱动，不再轮询播放服务。 */
    val isPlaying = audioPlayer.isPlaying

    /** 外观：跟随系统时读系统设置，手动选择则覆盖它。 */
    val darkTheme = when (appearance) {
        AppearanceMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }

    /** 已下载歌曲的 ID 集合，用于在搜索结果里标出「已下载」。 */
    val downloadedIds = remember(downloadedSongs) { downloadedSongs.mapNotNull { it.remoteId }.toSet() }

    /**
     * 临时切换当前这首歌的音质，不改默认设置。
     *
     * 做法是改写占位地址里的 quality 再从当前进度重新装载：真正的地址在取流时才解析，
     * 所以换掉占位地址就等于换了音质。停在原进度上，用户不会被打回开头。
     */
    fun switchCurrentQuality(quality: Int) {
        val index = selectedIndex
        val song = playbackSongs.getOrNull(index) ?: return
        val remoteId = song.remoteId
        if (remoteId == null || song.audioUri?.startsWith("file:") == true) {
            message = "本地歌曲的音质由文件本身决定"
            return
        }
        val position = audioPlayer.currentPositionMs()
        val updated = song.copy(audioUri = TencentMusicApi.placeholderUri(remoteId, quality))
        val queue = playbackSongs.toMutableList().also { it[index] = updated }
        playbackSongs = queue
        audioPlayer.play(updated, queue, index, position)
        scope.launch { withContext(Dispatchers.IO) { playbackStateStore.save(queue, index, position) } }
    }

    /**
     * 申请通知权限。
     *
     * Android 13 起 POST_NOTIFICATIONS 要运行时授权，而清单里早就声明了却从没申请过 ——
     * 也就是说系统媒体通知（锁屏控制）在 13+ 上一直没显示。下载进度通知同样依赖它，
     * 顺手一起补上。只申请一次，拒绝了也不再骚扰。
     */
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !downloadNotifier.canNotify()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 按指定音质下载。
     *
     * 必须先解析出上游直链再交给下载器：队列里存的是不带扩展名的占位地址，
     * 拿它下载会一律落成 `.mp3`，而无损其实是 flac，扩展名错了播放器会认错容器。
     */
    fun startDownload(song: Song, index: Int, quality: Int) {
        scope.launch {
            message = "正在下载…"
            downloadNotifier.progress(song.title, 0, 0)
            runCatching {
                withContext(Dispatchers.IO) {
                    val link = musicApi.resolveLink(song, quality)
                    // 歌词取带逐字时间轴的那份，两条轴都存下去，离线播放才和在线一致。
                    // 取不到不算失败：歌词是附加内容。
                    val lyric = runCatching { musicApi.requestRichLyric(song) }.getOrNull()
                    // 记实际拿到的档位而不是请求的档位：某首歌没有所选档时服务端会降级，
                    // 记错了离线播放显示的音质就是假的。
                    downloadManager.download(song, link.url, link.quality, lyric?.lrc, lyric?.yrc) { downloaded, total ->
                        downloadNotifier.progress(song.title, downloaded, total)
                    }
                }
            }.onSuccess { offline ->
                if (playbackSongs.getOrNull(index)?.remoteId == offline.remoteId) {
                    playbackSongs = playbackSongs.toMutableList().also { it[index] = offline }
                }
                downloadedSongs = withContext(Dispatchers.IO) { downloadManager.listDownloaded() }
                message = "已下载，可离线播放"
                downloadNotifier.completed(song.title)
            }.onFailure {
                message = it.message ?: "下载失败"
                downloadNotifier.failed(song.title, it.message ?: "请稍后重试")
            }
        }
    }

    /** 删除一首已下载的歌。播放中的那首要先停下来，否则文件删了播放器还握着句柄。 */
    fun deleteDownloaded(song: Song) {
        scope.launch {
            val playingThis = playbackSongs.getOrNull(selectedIndex)?.remoteId == song.remoteId &&
                playbackSongs.getOrNull(selectedIndex)?.audioUri?.startsWith("file:") == true
            if (playingThis) audioPlayer.stop()
            val removed = withContext(Dispatchers.IO) { downloadManager.delete(song) }
            downloadedSongs = withContext(Dispatchers.IO) { downloadManager.listDownloaded() }
            message = if (removed) "已删除「${song.title}」" else "删除失败"
        }
    }

    /**
     * 切换收藏。
     *
     * 先改本地缓存让心形立刻响应，再发请求；失败就回滚。以前是「等服务端返回再改 UI」，
     * 弱网下点一下要等半秒才有反应。
     */
    fun toggleFavorite(song: Song) {
        val remoteId = song.remoteId ?: return
        val target = !favoritesStore.contains(remoteId)
        favoritesStore.set(remoteId, target)
        favoriteRevision++
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { musicApi.setFavorite(song, target) } }
                .onFailure {
                    favoritesStore.set(remoteId, !target)
                    favoriteRevision++
                    message = it.message ?: "收藏操作失败，请稍后重试"
                }
        }
    }

    /**
     * 物理返回键：详情页先收起详情，搜索页先退出搜索，都不在时禁用拦截，
     * 交回系统默认行为（退出应用）—— 这样不必自己去拿 onBackPressedDispatcher。
     */
    BackHandler(enabled = showPlayerDetail || showSearchPage || showSettingsPage) {
        when {
            showPlayerDetail -> showPlayerDetail = false
            showSettingsPage -> showSettingsPage = false
            showSearchPage -> showSearchPage = false
        }
    }

    /**
     * 热更新检查。刻意放在登录门禁之前：最需要强制更新的场景恰恰是上一个版本把登录搞坏了，
     * 若要求先登录才能看到更新页，坏版本的用户就永远走不出来。
     */
    val updateManager = remember { UpdateManager(context, authSession) }
    LaunchedEffect(updateManager) { updateManager.check() }

    /**
     * 会话中途发现新版本。
     *
     * 服务端在每个响应上带回当前全量可用的最高版本号，比本机高就走一次正常检查。
     * 回调来自请求线程，切回主线程再动状态；连接由 DisposableEffect 负责断开。
     */
    DisposableEffect(musicApi, updateManager) {
        musicApi.onLatestVersion = { latest ->
            scope.launch { updateManager.onLatestVersionHint(latest) }
        }
        onDispose { musicApi.onLatestVersion = null }
    }

    /** 手动检查更新的结果单独提示，后台检查保持安静。 */
    LaunchedEffect(updateManager.manualResult) {
        updateManager.consumeManualResult()?.let { message = it }
    }

    val updateStatus = updateManager.status
    if (updateStatus.blocking) {
        // 必须自己套一层主题：这里在下面那个 TaotaoTheme 之前就 return 了，
        // 不套的话页面会拿到 Material 的默认配色，暗色下更是白底白字。
        TaotaoTheme(darkTheme = darkTheme) {
            ForceUpdatePage(
                status = updateStatus,
                onDownload = { scope.launch { updateManager.download() } },
                onInstall = { updateManager.install() },
                onRetry = { scope.launch { updateManager.retry() } },
            )
        }
        return
    }

    /** 会话彻底失效（刷新令牌也被拒绝）时停止播放并回到登录页。 */
    DisposableEffect(authSession) {
        authSession.onSessionExpired = {
            // 回调来自取流线程，切回主线程再改播放器和界面状态；连接由 DisposableEffect 负责断开。
            scope.launch {
                audioPlayer.stop()
                signedIn = false
            }
        }
        onDispose { authSession.onSessionExpired = null }
    }

    if (!signedIn) {
        AuthPage(musicApi, darkTheme) { tokens ->
            authSession.save(tokens)
            signedIn = true
        }
        return
    }

    LaunchedEffect(Unit) {
        // 队列 JSON 可能不小，别在主线程读盘拖慢启动。
        restoredPlayback = withContext(Dispatchers.IO) { playbackStateStore.read() }
    }

    /**
     * 播种收藏缓存。
     *
     * 拉一次完整收藏列表就够了 —— 之后靠搜索结果里的 `favorited` 校正、靠本地乐观更新维持。
     * 失败不提示：收藏状态不是主流程，缓存里还有上次的值可用。
     */
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { favoritesStore.replaceAll(musicApi.favoriteIds()) } }
            .onSuccess { favoriteRevision++ }
    }

    LaunchedEffect(restoredPlayback) {
        restoredPlayback?.let { savedState ->
            // 播放器已经有队列时以播放器为准，这里只负责冷启动后的恢复。
            if (playbackSongs.isEmpty() && audioPlayer.queue.isEmpty()) {
                playbackSongs = savedState.queue
                selectedIndex = savedState.index.coerceIn(savedState.queue.indices)
                pendingResumePositionMs = savedState.positionMs
                // 直接把队列装载进播放器：不出声，但时长、进度就位，点播放即刻续播。
                audioPlayer.prepareQueueIfIdle(savedState.queue, savedState.index, savedState.positionMs)
            }
        }
    }

    LaunchedEffect(Unit) {
        downloadedSongs = withContext(Dispatchers.IO) { downloadManager.listDownloaded() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                val queue = latestPlaybackSongs.value
                if (audioPlayer.hasMedia && queue.isNotEmpty()) {
                    playbackStateStore.save(queue, latestSelectedIndex.value, audioPlayer.currentPositionMs())
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 连接播放服务；界面销毁时只断开连接，后台播放不受影响。 */
    DisposableEffect(audioPlayer) {
        audioPlayer.connect()
        onDispose { audioPlayer.release() }
    }

    /**
     * 播放服务仍在后台播放时，队列以播放器为准 —— 界面被销毁重建后不必等用户点播放，
     * 也不会出现「还在播但列表只剩一首」。播放器为空才用磁盘上恢复的队列兜底。
     */
    LaunchedEffect(audioPlayer.queue) {
        val livingQueue = audioPlayer.queue
        if (livingQueue.isNotEmpty()) {
            playbackSongs = livingQueue
            selectedIndex = audioPlayer.currentIndex.coerceIn(livingQueue.indices)
        }
    }

    /** 播放器切到下一首时让界面跟随，下标越界说明队列还没同步，忽略即可。 */
    LaunchedEffect(audioPlayer.currentIndex, playbackSongs) {
        val playerIndex = audioPlayer.currentIndex
        if (playerIndex in playbackSongs.indices) selectedIndex = playerIndex
    }

    /** 播放进度按曲目变化保存整条队列，替代原先每两秒一次的主线程写盘。 */
    LaunchedEffect(selectedIndex, isPlaying, playbackSongs) {
        if (!audioPlayer.hasMedia || playbackSongs.isEmpty()) return@LaunchedEffect
        // 进度必须在主线程读（MediaController 有线程亲和），只把结果交给 IO 线程写盘。
        val positionMs = audioPlayer.currentPositionMs()
        // 冷启动装载队列的瞬间进度可能还没生效，此时别用 0 覆盖掉刚从磁盘读出的进度。
        if (positionMs <= 0 && !isPlaying) return@LaunchedEffect
        val queueSnapshot = playbackSongs
        val indexSnapshot = selectedIndex
        withContext(Dispatchers.IO) { playbackStateStore.save(queueSnapshot, indexSnapshot, positionMs) }
    }

    /** 播放服务在取流线程记录失败原因，这里取出后清空，避免同一条错误反复提示。 */
    LaunchedEffect(audioPlayer.playError) {
        audioPlayer.consumePlayError()?.let { message = it }
    }

    /** 提示信息通过 Snackbar 展示，任意页面都能看到下载、收藏和播放的反馈。 */
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            message = null
        }
    }

    fun openSearchPage() {
        searchGeneration += 1
        showSearchPage = true
        searchResults = emptyList()
        hasSearched = false
        isSearching = false
        searchError = null
        searchPage = 1
        searchHasMore = false
        searchTotal = 0
        isLoadingMore = false
    }

    /** 把一页结果里的收藏状态同步进本地缓存。服务端给的是权威值。 */
    suspend fun mergeFavorites(songs: List<Song>) {
        val seen = songs.mapNotNull { it.remoteId?.toString() }.toSet()
        val favorited = songs.filter { it.favorited }.mapNotNull { it.remoteId?.toString() }.toSet()
        withContext(Dispatchers.IO) { favoritesStore.merge(favorited, seen) }
        favoriteRevision++
    }

    fun startSearch() {
        val query = searchKeyword.trim()
        if (query.isBlank()) return
        searchHistoryStore.add(query)
        searchHistory = searchHistoryStore.read()
        val generation = searchGeneration + 1
        searchGeneration = generation
        scope.launch {
            hasSearched = true
            isSearching = true
            searchError = null
            searchResults = emptyList()
            searchPage = 1
            searchHasMore = false
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.search(query, quality = qualityStore.playbackQuality().value) { partial ->
                        // 服务端逐行下发，这里收到一首就渲染一首。切回主线程赋值，
                        // 并再次校验代次：期间用户可能已经发起了新搜索。
                        scope.launch { if (generation == searchGeneration) searchResults = dedupeSongs(partial) }
                    }
                }
            }
            // 请求返回后再次校验代次：期间用户可能已经发起新搜索或退出搜索页，旧结果不应覆盖新状态。
            if (generation != searchGeneration) return@launch
            result
                .onSuccess { found ->
                    searchResults = dedupeSongs(found.songs)
                    searchHasMore = found.hasMore
                    searchTotal = found.total
                    mergeFavorites(found.songs)
                }
                .onFailure { searchError = it.message ?: "搜索失败，请稍后重试" }
            isSearching = false
        }
    }

    /**
     * 滚到底时拉下一页。
     *
     * 累加时的基线 [base] 在请求前就取定：`authorized()` 遇到 401 会重放整个请求、
     * 把这一页的 NDJSON 从头再读一遍，回调给的是**本页**的累积快照，
     * 所以必须每次都用 `base + partial` 而不是往当前列表上追加，否则重放会产出重复条目。
     */
    fun loadMoreSearch() {
        val query = searchKeyword.trim()
        if (query.isBlank() || isSearching || isLoadingMore || !searchHasMore) return
        val generation = searchGeneration
        val nextPage = searchPage + 1
        val base = searchResults
        isLoadingMore = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.search(query, page = nextPage, quality = qualityStore.playbackQuality().value) { partial ->
                        scope.launch { if (generation == searchGeneration) searchResults = dedupeSongs(base + partial) }
                    }
                }
            }
            if (generation != searchGeneration) return@launch
            result
                .onSuccess { found ->
                    searchResults = dedupeSongs(base + found.songs)
                    searchPage = nextPage
                    // 上游可能给出空页却仍然说 hasMore，那样会无限拉；空页直接收口。
                    searchHasMore = found.hasMore && found.songs.isNotEmpty()
                    searchTotal = found.total
                    mergeFavorites(found.songs)
                }
                .onFailure { message = it.message ?: "加载更多失败" }
            isLoadingMore = false
        }
    }

    fun playSong(queue: List<Song>, index: Int, positionMs: Int = 0) {
        val requestedSong = queue.getOrNull(index) ?: return
        scope.launch {
            val isLocalFile = requestedSong.audioUri?.startsWith("file:") == true
            // remoteId 是别的模块的 public 属性，Kotlin 不做智能转换，先取成局部变量。
            val remoteId = requestedSong.remoteId
            // 下载过就直接放本地文件。搜索结果里的歌与已下载的是同一个 remoteId，
            // 不查这一步的话，明明下载过还是从云端拉流 —— 白费流量，离线也放不了。
            val offline = if (!isLocalFile && remoteId != null) {
                withContext(Dispatchers.IO) { downloadManager.findDownloaded(remoteId) }
            } else {
                null
            }
            // 网络歌曲统一用占位地址入队，真正的上游直链在取流那一刻才解析 ——
            // 直链是限时的，存进队列后冷启动恢复时就失效了。
            val playable = when {
                offline != null -> offline.copy(favorited = requestedSong.favorited)
                !isLocalFile && remoteId != null -> requestedSong.copy(
                    audioUri = TencentMusicApi.placeholderUri(remoteId, qualityStore.playbackQuality().value),
                    lyricUri = requestedSong.lyricUri
                        ?: "${TencentMusicApi.ENDPOINT}/api/v1/songs/$remoteId/lyrics",
                )
                else -> requestedSong
            }
            if (playable.audioUri.isNullOrBlank()) {
                message = "歌曲暂时没有可用播放链接"
                return@launch
            }
            message = null
            val updatedQueue = queue.toMutableList().also { it[index] = playable }
            playbackSongs = updatedQueue
            selectedIndex = index
            audioPlayer.play(playable, updatedQueue, index, positionMs)
            withContext(Dispatchers.IO) { playbackStateStore.save(updatedQueue, index, positionMs) }
            pendingResumePositionMs = 0
        }
    }

    fun togglePlayback() {
        if (playbackSongs.isEmpty()) return
        if (audioPlayer.isPlaying) {
            audioPlayer.pause()
            // 同上：先在主线程取进度，再交给 IO 线程写盘。
            val positionMs = audioPlayer.currentPositionMs()
            val queueSnapshot = playbackSongs
            val indexSnapshot = selectedIndex
            scope.launch(Dispatchers.IO) { playbackStateStore.save(queueSnapshot, indexSnapshot, positionMs) }
        } else if (audioPlayer.hasMedia) {
            audioPlayer.resume()
        } else {
            playSong(playbackSongs, selectedIndex, pendingResumePositionMs)
        }
    }

    // 配色统一走 TaotaoTheme，避免和登录页各写一份 colorScheme 导致进入首页时突然换色。
    TaotaoTheme(darkTheme = darkTheme) {
        // 可选更新提示：强制更新已在上面拦截返回，这里只处理用户可以忽略的情况。
        if (updateStatus.stage != UpdateStage.IDLE && updateStatus.stage != UpdateStage.CHECKING &&
            updateStatus.stage != UpdateStage.UP_TO_DATE && !updateStatus.forced
        ) {
            OptionalUpdateDialog(
                status = updateStatus,
                onDownload = { scope.launch { updateManager.download() } },
                onInstall = { updateManager.install() },
                onRetry = { scope.launch { updateManager.retry() } },
                onDismiss = { updateManager.dismiss() },
            )
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column {
                    // 迷你播放器升起/落下要有过渡：原来是直接出现和消失，
                    // 底部一整条突然多出来一块，视觉上很跳。
                    AnimatedVisibility(
                        visible = !showPlayerDetail && playbackSongs.isNotEmpty(),
                        enter = riseIn(),
                        exit = sinkOut(),
                    ) {
                        // 退出动画期间队列可能已被清空，用最后一次的快照撑到动画走完。
                        val current = remember(playbackSongs, selectedIndex) {
                            playbackSongs.getOrNull(selectedIndex.coerceIn(0, (playbackSongs.size - 1).coerceAtLeast(0)))
                        }
                        if (current != null) {
                            MiniPlayer(
                                current,
                                isPlaying,
                                onOpen = { showPlayerDetail = true },
                                onPrevious = { audioPlayer.previous() },
                                onNext = { audioPlayer.next() },
                            ) {
                                togglePlayback()
                            }
                        }
                    }
                    NavigationBar {
                        // 切换底部标签时要收起详情页、搜索页和设置页，否则 AnimatedContent 仍停在
                        // 原来的分支，用户点了「音乐」却还留在设置里。新增页面时必须同步这里、
                        // AnimatedContent 的 targetState 和 BackHandler 三处。
                        val switchTab = { target: Int ->
                            bottomTab = target
                            showPlayerDetail = false
                            showSearchPage = false
                            showSettingsPage = false
                        }
                        NavigationBarItem(bottomTab == 0, { switchTab(0) }, icon = { Icon(Icons.Default.MusicNote, "音乐") }, label = { Text("音乐") })
                        NavigationBarItem(bottomTab == 1, { switchTab(1) }, icon = { Icon(Icons.Default.Person, "我的") }, label = { Text("我的") })
                    }
                    }
                },
            ) { innerPadding ->
            AnimatedContent(
                targetState = when {
                    showPlayerDetail -> "detail"
                    showSettingsPage -> "settings"
                    showSearchPage -> "search"
                    // 底部标签也参与：不带上它的话音乐 ⇄ 我的是硬切，
                    // 而其它换页都有过渡，观感上很不一致。
                    bottomTab == 1 -> "mine"
                    else -> "home"
                },
                transitionSpec = { pageTransition() },
                label = "页面切换",
            ) { page ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (page == "detail") {
                if (playbackSongs.isNotEmpty()) PlayerDetailPage(
                    song = playbackSongs[selectedIndex.coerceIn(playbackSongs.indices)],
                    audioPlayer = audioPlayer,
                    isPlaying = isPlaying,
                    onBack = { showPlayerDetail = false },
                    onDownload = {
                        // 先在主线程取定目标歌曲，避免后台任务期间 selectedIndex 变化导致下错歌或越界。
                        val target = playbackSongs.getOrNull(selectedIndex)
                        when {
                            target == null -> message = "没有正在播放的歌曲"
                            target.audioUri?.startsWith("file:") == true -> message = "这首歌已经下载过了"
                            // 下载前先选音质：下载只花一次流量，值得让用户自己定，
                            // 面板里会显示各档的实际体积。
                            else -> downloadTarget = target to selectedIndex
                        }
                    },
                    musicApi = musicApi,
                    onTogglePlaying = { togglePlayback() },
                    onPrevious = { audioPlayer.previous() },
                    onNext = { audioPlayer.next() },
                    repeatMode = repeatMode,
                    onToggleRepeat = {
                        repeatMode = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) {
                            androidx.media3.common.Player.REPEAT_MODE_OFF
                        } else {
                            androidx.media3.common.Player.REPEAT_MODE_ONE
                        }
                        audioPlayer.setRepeatMode(repeatMode)
                    },
                    queue = playbackSongs,
                    queueIndex = selectedIndex,
                    onQueueItemClick = { index ->
                        // 播放器里已经装载了同一条队列，直接跳转即可，不必重新解析地址。
                        if (audioPlayer.hasMedia) audioPlayer.playAt(index)
                        else playSong(playbackSongs, index)
                    },
                    onMessage = { message = it },
                    favorited = remember(selectedIndex, favoriteRevision, playbackSongs) {
                        favoritesStore.contains(playbackSongs.getOrNull(selectedIndex)?.remoteId)
                    },
                    onToggleFavorite = {
                        playbackSongs.getOrNull(selectedIndex)?.let { toggleFavorite(it) }
                    },
                    playbackQuality = playbackQuality.value,
                    onPickQuality = { qualitySheet = QualitySheetKind.CURRENT_SONG },
                )
            } else if (page == "search") {
                SearchPage(
                    keyword = searchKeyword,
                    songs = searchResults,
                    isSearching = isSearching,
                    hasSearched = hasSearched,
                    errorMessage = searchError,
                    onBack = { showSearchPage = false },
                    onKeywordChanged = { searchKeyword = it },
                    onSearch = { startSearch() },
                    history = searchHistory,
                    onHistoryClick = { value -> searchKeyword = value; searchHistoryStore.add(value); searchHistory = searchHistoryStore.read() },
                    onHistoryRemove = { value -> searchHistoryStore.remove(value); searchHistory = searchHistoryStore.read() },
                    onHistoryClear = { searchHistoryStore.clear(); searchHistory = emptyList() },
                    onSongClick = { index, song ->
                        playSong(searchResults, index)
                    },
                    // 读一下 revision 让收藏变化能触发重组：SharedPreferences 本身不可观察。
                    favoriteRevision = favoriteRevision,
                    isFavorite = { song -> favoritesStore.contains(song.remoteId) },
                    onToggleFavorite = { song -> toggleFavorite(song) },
                    downloadedRevision = downloadedSongs.size,
                    isDownloaded = { song -> song.remoteId != null && song.remoteId in downloadedIds },
                    onLoadMore = { loadMoreSearch() },
                    isLoadingMore = isLoadingMore,
                    hasMore = searchHasMore,
                    total = searchTotal,
                    searchSession = searchGeneration,
                )
            } else if (page == "settings") {
                SettingsPage(
                    playbackQuality = playbackQuality,
                    downloadQuality = downloadQuality,
                    appearance = appearance,
                    onPickPlaybackQuality = { qualitySheet = QualitySheetKind.PLAYBACK_DEFAULT },
                    onPickDownloadQuality = { qualitySheet = QualitySheetKind.DOWNLOAD_DEFAULT },
                    onPickAppearance = { mode ->
                        appearance = mode
                        appearanceStore.setMode(mode)
                    },
                    onBack = { showSettingsPage = false },
                )
            } else if (page == "mine") {
                MinePage(
                    onLogout = {
                        audioPlayer.stop()
                        playbackStateStore.clear()
                        // 收藏是账号状态，换账号不能沿用上一个人的。
                        favoritesStore.clear()
                        // 撤销刷新令牌需要访问网络，放到 IO 线程；本地会话已在 signOut 内同步清空。
                        scope.launch(Dispatchers.IO) { authSession.signOut() }
                        signedIn = false
                    },
                    onOpenSettings = { showSettingsPage = true },
                    versionName = updateManager.installedVersionName,
                    checking = updateStatus.stage == UpdateStage.CHECKING,
                    onCheckUpdate = { scope.launch { updateManager.check(manual = true) } },
                )
            } else Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(24.dp))
                HomeHeader()
                MusicSearchBar(searchKeyword, onKeywordChanged = { searchKeyword = it }, onSearch = {
                    if (searchKeyword.isNotBlank()) {
                        openSearchPage()
                        startSearch()
                    }
                }, onFocus = { openSearchPage() })
                CategoryTabs(selectedTab) { selectedTab = it }
                if (selectedTab == 2) {
                    SectionTitle("本地音乐")
                    if (downloadedSongs.isEmpty()) {
                        Text("还没有下载歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        downloadedSongs.forEachIndexed { index, song ->
                            SongListItem(
                                song = song,
                                active = false,
                                favorited = remember(song.remoteId, favoriteRevision) {
                                    favoritesStore.contains(song.remoteId)
                                },
                                onToggleFavorite = song.remoteId?.let { { toggleFavorite(song) } },
                                onDelete = { pendingDelete = song },
                            ) { playSong(downloadedSongs, index) }
                        }
                    }
                }
                Text("在线搜索歌曲，下载后可在无网络时播放", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                SectionTitle("今日推荐")
                RecommendationCard()
                Spacer(Modifier.height(10.dp))
            }
            }
            }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载") },
            text = { Text("将删除「${target.title}」的音频、封面和歌词，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteDownloaded(target)
                }) { Text("删除", color = TaotaoCoral) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    // 下载前的音质面板：查一次 /song/info 拿到这首歌真实存在的档位与体积。
    val pendingDownload = downloadTarget
    if (pendingDownload != null) {
        LaunchedEffect(pendingDownload.first.remoteId) {
            qualitiesLoading = true
            songQualities = runCatching {
                withContext(Dispatchers.IO) { musicApi.requestQualities(pendingDownload.first) }
            }.map { options ->
                options.map { QualityChoice(it.quality, it.label, it.size) }
            }.getOrDefault(staticQualityChoices())
            qualitiesLoading = false
        }
        QualitySheet(
            title = "下载音质",
            choices = songQualities,
            selected = downloadQuality.value,
            loading = qualitiesLoading,
            note = pendingDownload.first.title,
            onPick = { picked ->
                val (song, index) = pendingDownload
                downloadTarget = null
                startDownload(song, index, picked)
            },
            onDismiss = { downloadTarget = null },
        )
    }

    when (qualitySheet) {
        QualitySheetKind.CURRENT_SONG -> {
            val current = playbackSongs.getOrNull(selectedIndex)
            LaunchedEffect(current?.remoteId) {
                if (current == null) return@LaunchedEffect
                qualitiesLoading = true
                songQualities = runCatching {
                    withContext(Dispatchers.IO) { musicApi.requestQualities(current) }
                }.map { options -> options.map { QualityChoice(it.quality, it.label, it.size) } }
                    .getOrDefault(staticQualityChoices())
                qualitiesLoading = false
            }
            QualitySheet(
                title = "音质",
                choices = songQualities,
                selected = current?.audioUri?.let { TencentMusicApi.parsePlaceholder(it)?.second }
                    ?: playbackQuality.value,
                loading = qualitiesLoading,
                note = "只对这一首生效，不改默认设置。",
                onPick = { picked ->
                    qualitySheet = null
                    switchCurrentQuality(picked)
                },
                onDismiss = { qualitySheet = null },
            )
        }
        QualitySheetKind.PLAYBACK_DEFAULT -> QualitySheet(
            title = "默认播放音质",
            choices = staticQualityChoices(),
            selected = playbackQuality.value,
            note = "越高越费流量。某首歌没有所选档位时会自动降到最接近的可用档。",
            onPick = { picked ->
                playbackQuality = AudioQuality.of(picked)
                qualityStore.setPlaybackQuality(playbackQuality)
                qualitySheet = null
            },
            onDismiss = { qualitySheet = null },
        )
        QualitySheetKind.DOWNLOAD_DEFAULT -> QualitySheet(
            title = "默认下载音质",
            choices = staticQualityChoices(),
            selected = downloadQuality.value,
            note = "下载只花一次流量，可以选得比播放更高。",
            onPick = { picked ->
                downloadQuality = AudioQuality.of(picked)
                qualityStore.setDownloadQuality(downloadQuality)
                qualitySheet = null
            },
            onDismiss = { qualitySheet = null },
        )
        null -> Unit
    }
}

/** 音质面板的三种用途。 */
private enum class QualitySheetKind { CURRENT_SONG, PLAYBACK_DEFAULT, DOWNLOAD_DEFAULT }

/**
 * 去掉重复条目，保留首次出现的顺序。
 *
 * 判重用「ID + 标题 + 歌手」而不是只看 ID：上游偶尔会把同一行返回两遍（线上崩过一次，
 * `LazyColumn` 对重复 key 直接抛 IllegalArgumentException），但同一个 ID 配不同标题
 * 也是可能的，只看 ID 会把两首真正不同的歌合并成一条。
 *
 * 列表的 key 用的是同一个组合，所以只要过了这一层，key 一定唯一。
 */
private fun dedupeSongs(songs: List<Song>): List<Song> {
    val seen = HashSet<String>(songs.size)
    return songs.filter { seen.add(songKeyOf(it)) }
}

/** 列表项的稳定唯一键。必须与 [dedupeSongs] 的判重口径一致。 */
fun songKeyOf(song: Song): String = "${song.remoteId ?: 0}#${song.title}#${song.artist}"

@Composable private fun HomeHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("早上好，桃桃", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Text("听点喜欢的", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = {}) { Icon(Icons.Default.NotificationsNone, "通知") }
                Spacer(Modifier.width(48.dp))
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun MinePage(
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit,
    versionName: String,
    checking: Boolean,
    onCheckUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val crashReporter = remember { CrashReporter(context) }
    var crashLogs by remember { mutableStateOf(emptyList<CrashLog>()) }
    var showCrashLogs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        crashLogs = withContext(Dispatchers.IO) { crashReporter.logs() }
    }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("我的", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onOpenSettings).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Person, null, tint = TaotaoCoral)
            Text("设置", modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("音质与播放", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        // 测试机无法连接 adb，崩溃堆栈只能在应用内查看和复制。
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = crashLogs.isNotEmpty()) { showCrashLogs = true }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.BugReport, "崩溃日志", tint = if (crashLogs.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral)
            Text(
                if (crashLogs.isEmpty()) "崩溃日志（暂无记录）" else "崩溃日志（${crashLogs.size} 条）",
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                color = if (crashLogs.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = !checking, onClick = onCheckUpdate).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SystemUpdate, "检查更新", tint = TaotaoCoral)
            Text("检查更新", modifier = Modifier.weight(1f).padding(start = 12.dp))
            if (checking) {
                CircularProgressIndicator(Modifier.size(16.dp), color = TaotaoCoral, strokeWidth = 2.dp)
            } else {
                Text("当前 $versionName", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onLogout).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, "退出登录", tint = TaotaoCoral)
            Text("退出登录", modifier = Modifier.padding(start = 12.dp))
        }
    }
    if (showCrashLogs) {
        val allLogs = crashLogs.joinToString("\n\n" + "=".repeat(40) + "\n\n") { "${it.name}\n${it.content}" }
        AlertDialog(
            onDismissRequest = { showCrashLogs = false },
            title = { Text("崩溃日志") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(allLogs, fontSize = 11.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(allLogs)) }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = {
                    crashReporter.clear()
                    crashLogs = emptyList()
                    showCrashLogs = false
                }) { Text("清空") }
            },
        )
    }
}

@Composable private fun CategoryTabs(selectedIndex: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("为你推荐", "每日推荐", "歌单").forEachIndexed { index, label ->
            val selected = selectedIndex == index
            // 选中态渐变而不是硬切；字重没法插值，用缩放补上一点"被按下去"的层次。
            val color by animateColorAsState(
                targetValue = if (selected) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = taotaoTween(AnimationDurations.MICRO),
                label = "标签着色",
            )
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.06f else 1f,
                animationSpec = taotaoSpring(),
                label = "标签缩放",
            )
            Text(
                label,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = color,
                modifier = Modifier
                    .clickable { onSelected(index) }
                    .padding(vertical = 8.dp)
                    .scale(scale),
            )
        }
    }
}

@Composable private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 14.dp, bottom = 12.dp))
}

@Composable private fun RecommendationCard() {
    Row(Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFFFD8D0)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("专属歌单", color = TaotaoCoral, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("给今天的你\n一点好心情", fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
            Text("20 首 · 精选推荐", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        AlbumArt(Color(0xFFFFB4A2), 112.dp, 64.sp)
    }
}

@Composable
private fun PlayerDetailPage(
    song: Song,
    audioPlayer: AudioPlayer,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onTogglePlaying: () -> Unit,
    musicApi: TencentMusicApi,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    repeatMode: Int,
    onToggleRepeat: () -> Unit,
    queue: List<Song>,
    queueIndex: Int,
    onQueueItemClick: (Int) -> Unit,
    onMessage: (String) -> Unit,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    playbackQuality: Int,
    onPickQuality: () -> Unit,
) {
    // 初值直接取播放器的当前进度，而不是 0：进详情页时若从 0 起再被 ticker 拉到真实位置，
    // 进度条会明显地从头飞过去一次。
    var positionMs by remember(song) { mutableIntStateOf(audioPlayer.currentPositionMs()) }
    var dragging by remember(song) { mutableStateOf(false) }
    // 这两组状态的 remember key 必须与下面对应 LaunchedEffect 的 key 一致：
    // 解析播放地址后队列里的 Song 会被换成新副本，song 变了但 remoteId / lyricUri 没变，
    // key 不一致就会出现「状态被清空、拉取逻辑却不重跑」的空白歌词和收藏状态丢失。
    var lyricText by remember(song.lyricUri, song.remoteId) { mutableStateOf<String?>(null) }
    var lyricWords by remember(song.lyricUri, song.remoteId) { mutableStateOf<String?>(null) }
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
    LaunchedEffect(song.lyricUri, song.remoteId) {
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
                } else if (song.remoteId != null) {
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
    // 收藏状态由上层的本地缓存提供，不再每次进详情页就拉一遍完整收藏列表。
    // key 必须包含 song：positionMs / dragging 是 remember(song)，切歌后会换成新的 state 对象，
    // 若 ticker 不跟着重启，就会一直往已被丢弃的旧对象里写进度，界面上停在 0:00。
    //
    // 播放中按帧取进度而不是每 500 毫秒轮询一次：逐字高亮对延迟很敏感，
    // 500 毫秒的采样周期平均会慢上 250 毫秒，肉眼能明显看出歌词跟不上。
    // MediaController 的 currentPosition 是本地推算的，不走跨进程调用，逐帧读取代价很低。
    LaunchedEffect(song, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                if (audioPlayer.isPlaying) {
                    withFrameMillis { }
                } else {
                    // 暂停时没有推进，降频到 300 毫秒，只为跟上外部（通知栏、耳机键）的跳转。
                    kotlinx.coroutines.delay(300)
                }
                if (!dragging) positionMs = audioPlayer.currentPositionMs()
            }
        }
    }
    // 时间文字只需要秒级精度。用派生状态挡住逐帧变化，避免每帧重新格式化字符串。
    val positionSeconds by remember { derivedStateOf { positionMs / 1000 } }

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
    LaunchedEffect(song.remoteId, song.audioUri, isPlaying, lifecycleOwner) {
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
    // 这个 BackHandler 比 TaotaoMusicApp 里那个更深，启用时优先生效。
    BackHandler(enabled = pagerState.currentPage > 0) {
        detailScope.launch { pagerState.animateScrollToPage(0) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "收起") }
            Text(
                if (pagerState.currentPage == 1) "歌词" else "正在播放",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "更多") }
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
                        positionMs = target
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
                        AlbumArt(Color(song.color), coverSize, 132.sp)
                    }
                }
            }
        }
        PagerDots(
            current = pagerState.currentPage,
            total = 2,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        song.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (song.vip) VipBadge(Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 5.dp)) {
                    Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
                    if (song.remoteId != null) {
                        // 本地文件也要显示音质：它是下载时记下来的实际档位。
                        // 不显示的话用户没法知道手里这份是无损还是标准。
                        QualityChip(
                            quality = displayedQuality,
                            modifier = Modifier.padding(start = 10.dp),
                            local = isLocalFile,
                            // 本地文件换档要重新下载，不能就地切，所以不给点。
                            onClick = onPickQuality.takeIf { !isLocalFile },
                        )
                    }
                }
            }
            FavoriteButton(
                favorited = favorited,
                onClick = onToggleFavorite,
                // 收藏依赖服务端歌曲 ID，纯本地歌曲不提供该操作。
                enabled = song.remoteId != null,
            )
        }
        Spacer(Modifier.height(12.dp))
        // progress 用派生状态包起来：直接在 body 里读 positionMs 会让整个详情页
        // 每帧全部失效 —— Slider、控制按钮、下载卡、歌词页都要重组一遍。
        val progress by remember { derivedStateOf { if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f } }
        Slider(
            value = progress,
            onValueChange = { dragging = true; positionMs = (it * durationMs).toInt() },
            onValueChangeFinished = { dragging = false; audioPlayer.seekTo(positionMs) },
            colors = SliderDefaults.colors(thumbColor = TaotaoCoral, activeTrackColor = TaotaoCoral),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionSeconds * 1000), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                formatTime(durationMs).takeIf { durationMs > 0 } ?: "--:--",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = onToggleRepeat) {
                val single = repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE
                val tint by animateColorAsState(
                    targetValue = if (single) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = taotaoTween(AnimationDurations.MICRO),
                    label = "循环着色",
                )
                Icon(Icons.Default.Repeat, if (single) "单曲循环" else "循环", tint = tint)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(34.dp)) }
            FilledIconButton(onClick = onTogglePlaying, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = TaotaoCoral)) {
                PlayPauseIcon(actualPlaying, Modifier.size(34.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(34.dp)) }
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = if (queue.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 已下载的歌不再显示下载入口：可点却只会提示"已经下载过了"是白给的一次失望。
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .then(if (isLocalFile) Modifier else Modifier.clickable(onClick = onDownload))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isLocalFile) Icons.Default.CheckCircle else Icons.Default.MusicNote,
                null,
                tint = TaotaoCoral,
            )
            Text(
                if (isLocalFile) "已下载，正在播放本地文件" else "下载歌曲、封面和歌词",
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                fontWeight = FontWeight.Medium,
            )
            if (!isLocalFile) Icon(Icons.Default.Download, "下载", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
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
        )
    }
}

/** 播放队列面板：展示当前队列并支持直接跳到某一首。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackQueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onItemClick: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("播放队列", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${queue.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (queue.isEmpty()) {
                Text("队列为空", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(queue) { index, item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable { onItemClick(index) }.padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (index == currentIndex) {
                                Icon(Icons.Default.VolumeUp, "正在播放", tint = TaotaoCoral, modifier = Modifier.size(18.dp))
                            } else {
                                Text(
                                    "${index + 1}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.width(18.dp),
                                )
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    item.title,
                                    fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Medium,
                                    color = if (index == currentIndex) TaotaoCoral else Color.Unspecified,
                                    maxLines = 1,
                                )
                                Text(item.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                            }
                            Text(item.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
