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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.taotao.music.TaotaoApplication
import com.taotao.music.model.AudioQuality
import com.taotao.music.model.LyricParser
import com.taotao.music.model.Song
import com.taotao.music.data.AppearanceMode
import com.taotao.music.data.AppearanceStore
import com.taotao.music.data.CrashLog
import com.taotao.music.data.CrashReporter
import com.taotao.music.data.DownloadNotifier
import com.taotao.music.data.FavoritesStore
import com.taotao.music.data.GreetingFormatter
import com.taotao.music.data.OfflineDownloadManager
import com.taotao.music.data.QualityStore
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.SearchHistoryStore
import com.taotao.music.data.AuthSession
import com.taotao.music.data.PlaybackStateStore
import com.taotao.music.data.PlaybackHistoryEntry
import com.taotao.music.data.PlaybackHistoryStore
import com.taotao.music.data.SavedPlaybackState
import com.taotao.music.player.AudioPlayer
import com.taotao.music.update.UpdateManager
import com.taotao.music.update.UpdateStage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.lifecycle.LifecycleEventObserver
import android.net.Uri
import java.io.File
import java.util.Calendar

@Composable
fun TaotaoMusicApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val authSession = remember { AuthSession(context) }
    val audioPlayer = remember { AudioPlayer(context) }
    val musicApi = remember {
        TencentMusicApi(authSession, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
    }
    val downloadManager = remember { OfflineDownloadManager(context, authSession) }
    val playbackStateStore = remember { PlaybackStateStore(context) }
    val playbackHistoryStore = remember { PlaybackHistoryStore(context) }
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
    var searchKeyword by remember { mutableStateOf("") }
    var showPlayerDetail by remember { mutableStateOf(false) }
    var showSearchPage by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloadedSongs by remember { mutableStateOf(emptyList<Song>()) }
    var bottomTab by remember { mutableIntStateOf(0) }
    var imageTask by remember { mutableStateOf<TencentMusicApi.ImageTask?>(null) }
    var imageGenerating by remember { mutableStateOf(false) }
    var searchHistory by remember { mutableStateOf(searchHistoryStore.read()) }
    var signedIn by remember { mutableStateOf(authSession.isSignedIn) }
    /** 资料只在内存中保留；退出登录立即清空邮箱与昵称。 */
    var userProfile by remember { mutableStateOf<TencentMusicApi.UserProfile?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var announcements by remember { mutableStateOf(emptyList<TencentMusicApi.Announcement>()) }
    var showAnnouncementDialog by remember { mutableStateOf(false) }
    var restoredPlayback by remember { mutableStateOf<SavedPlaybackState?>(null) }
    var playbackHistory by remember { mutableStateOf(emptyList<PlaybackHistoryEntry>()) }
    var pendingResumePositionMs by remember { mutableIntStateOf(0) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    /** 分页状态。服务端一直在返回 hasMore / total，客户端以前直接丢掉。 */
    var searchPage by remember { mutableIntStateOf(1) }
    var searchHasMore by remember { mutableStateOf(false) }
    var searchTotal by remember { mutableIntStateOf(0) }
    var isLoadingMore by remember { mutableStateOf(false) }
    /** 收藏缓存被改动后自增，让读了它的界面重新组合 —— SharedPreferences 本身不是可观察的。 */
    var favoriteRevision by remember { mutableIntStateOf(0) }
    // 收藏页先同步读取本地展示缓存，页面打开时无需等待云端请求与加载动画。
    var favoriteLibrarySongs by remember { mutableStateOf(favoritesStore.cachedSongs()) }
    var favoriteLibrarySyncing by remember { mutableStateOf(false) }
    var favoriteLibraryError by remember { mutableStateOf<String?>(null) }
    var showSettingsPage by remember { mutableStateOf(false) }
    var showProfilePage by remember { mutableStateOf(false) }
    var mineLibrarySection by remember { mutableStateOf<MineLibrarySection?>(null) }
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
    // SharedPreferences 不是可观察状态；把修订号纳入 remember，点击收藏后当前页面立刻重组。
    val favoriteIds = remember(favoriteRevision) { favoritesStore.ids() }

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
        // 公告免鉴权，启动时安静读取；网络异常不应阻断听歌或登录。
        announcements = runCatching { withContext(Dispatchers.IO) { musicApi.announcements() } }.getOrDefault(emptyList())
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
        val previousLibrary = favoriteLibrarySongs
        favoritesStore.set(remoteId, target, song)
        favoriteLibrarySongs = if (target) {
            listOf(song.copy(favorited = true)) + favoriteLibrarySongs.filterNot { it.remoteId == remoteId }
        } else {
            favoriteLibrarySongs.filterNot { it.remoteId == remoteId }
        }
        favoriteRevision++
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { musicApi.setFavorite(song, target) } }
                .onFailure {
                    favoritesStore.set(remoteId, !target, song)
                    favoriteLibrarySongs = previousLibrary
                    favoriteRevision++
                    message = it.message ?: "收藏操作失败，请稍后重试"
                }
        }
    }

    /**
     * 收藏页先展示本地缓存，再在后台用账号收藏接口校正。
     *
     * 缓存只负责首屏展示，收藏关系仍完全以服务端返回的 ID 为准，因此不会形成第二套收藏夹。
     */
    fun refreshFavoriteLibrary() {
        if (favoriteLibrarySyncing) return
        favoriteLibrarySyncing = true
        favoriteLibraryError = null
        val knownSongs = favoriteLibrarySongs + playbackSongs + searchResults +
            playbackHistory.map { it.song } + downloadedSongs
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { musicApi.favoriteLibrary(knownSongs, playbackQuality.value) }
            }.onSuccess { library ->
                favoritesStore.replaceLibrary(library.ids, library.songs)
                favoriteLibrarySongs = library.songs
                favoriteRevision++
            }.onFailure {
                favoriteLibraryError = it.message ?: "收藏列表加载失败"
            }
            favoriteLibrarySyncing = false
        }
    }

    /**
     * 物理返回键：详情页先收起详情，搜索页先退出搜索，都不在时禁用拦截，
     * 交回系统默认行为（退出应用）—— 这样不必自己去拿 onBackPressedDispatcher。
     */
    BackHandler(enabled = showPlayerDetail || showSearchPage || showSettingsPage || showProfilePage || mineLibrarySection != null) {
        when {
            showPlayerDetail -> showPlayerDetail = false
            showProfilePage -> showProfilePage = false
            showSettingsPage -> showSettingsPage = false
            showSearchPage -> showSearchPage = false
            mineLibrarySection != null -> mineLibrarySection = null
        }
    }

    /**
     * 热更新检查。刻意放在登录门禁之前：最需要强制更新的场景恰恰是上一个版本把登录搞坏了，
     * 若要求先登录才能看到更新页，坏版本的用户就永远走不出来。
     */
    val updateManager = remember {
        UpdateManager(
            context,
            authSession,
            initialPatchVersion = (context.applicationContext as? TaotaoApplication)?.activePatchVersion ?: 0,
        )
    }
    LaunchedEffect(updateManager) { updateManager.check() }

    /**
     * 确认热修复补丁可用。
     *
     * 刻意等到界面组合起来、再多等几秒才确认：加载补丁前记了尝试计数，只有走到这里
     * 才清零。太早确认等于把自愈机制关掉 —— 一个能让应用起不来的补丁必须能自己退回去。
     *
     * key 是 `activePatchVersion` 而不是 `Unit`：会话中途装上的补丁同样需要被确认。
     * 用 Unit 的话这个效果只在启动时烧一次，中途装的补丁尝试计数永远停在 1，
     * 下次启动被判成"加载后启动失败"而回滚 —— 表现是"点了能生效，一重启就没了"。
     */
    LaunchedEffect(updateManager.activePatchVersion) {
        kotlinx.coroutines.delay(5_000)
        updateManager.confirmPatch(updateManager.activePatchVersion)
    }

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
        musicApi.onLatestPatch = { latestPatch ->
            scope.launch { updateManager.onLatestPatchHint(latestPatch) }
        }
        onDispose {
            musicApi.onLatestVersion = null
            musicApi.onLatestPatch = null
        }
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
        // 队列与播放记录 JSON 都可能不小，别在主线程读盘拖慢启动。
        val restored = withContext(Dispatchers.IO) {
            playbackStateStore.read() to playbackHistoryStore.read()
        }
        restoredPlayback = restored.first
        playbackHistory = restored.second
    }

    /**
     * 播种收藏缓存。
     *
     * 拉一次完整收藏列表就够了 —— 之后靠搜索结果里的 `favorited` 校正、靠本地乐观更新维持。
     * 失败不提示：收藏状态不是主流程，缓存里还有上次的值可用。
     */
    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        profileLoading = true
        runCatching { withContext(Dispatchers.IO) { musicApi.profile() } }
            .onSuccess { userProfile = it }
            .onFailure { message = it.message ?: "账号资料读取失败" }
        profileLoading = false
        runCatching { withContext(Dispatchers.IO) { musicApi.favoriteIds() } }
            .onSuccess { remoteIds ->
                favoritesStore.replaceAll(remoteIds)
                favoriteLibrarySongs = favoritesStore.cachedSongs()
                favoriteRevision++
            }
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

    /**
     * 只在歌曲真正开始播放时记历史；冷启动只是恢复到暂停位置，不应伪造一次播放。
     * 同一首歌暂停后继续播放会被存储层合并并移到最前，不会刷出重复行。
     */
    val activePlayerSong = audioPlayer.queue.getOrNull(audioPlayer.currentIndex)
    LaunchedEffect(activePlayerSong?.remoteId, activePlayerSong?.audioUri, audioPlayer.isPlaying) {
        if (!audioPlayer.isPlaying) return@LaunchedEffect
        val played = activePlayerSong ?: return@LaunchedEffect
        playbackHistory = withContext(Dispatchers.IO) { playbackHistoryStore.record(played) }
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
        withContext(Dispatchers.IO) { favoritesStore.merge(songs) }
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
            // 一次点击会把来源列表稳定地变成整条播放队列。以前只给被点中的一首补地址，
            // AudioPlayer 发现其它歌曲没有地址后会把整队列退化为单曲，界面却还短暂显示整列，
            // 这正是播放列表规则看起来忽多忽少的根源。
            val prepared = queue.mapIndexedNotNull { originalIndex, candidate ->
                val candidateId = candidate.remoteId
                val ready = when {
                    originalIndex == index -> playable
                    candidate.audioUri?.startsWith("file:") == true -> candidate
                    candidateId != null -> candidate.copy(
                        audioUri = TencentMusicApi.placeholderUri(candidateId, qualityStore.playbackQuality().value),
                        lyricUri = candidate.lyricUri
                            ?: "${TencentMusicApi.ENDPOINT}/api/v1/songs/$candidateId/lyrics",
                    )
                    else -> candidate
                }
                ready.takeIf { !it.audioUri.isNullOrBlank() }?.let { originalIndex to it }
            }
            val startIndex = prepared.indexOfFirst { it.first == index }
            if (startIndex < 0) {
                message = "歌曲暂时没有可用播放链接"
                return@launch
            }
            val updatedQueue = prepared.map { it.second }
            playbackSongs = updatedQueue
            selectedIndex = startIndex
            audioPlayer.play(updatedQueue[startIndex], updatedQueue, startIndex, positionMs)
            withContext(Dispatchers.IO) { playbackStateStore.save(updatedQueue, startIndex, positionMs) }
            pendingResumePositionMs = 0
        }
    }

    /**
     * 将歌曲加入当前曲目的下一首。
     *
     * 与正常播放共用离线优先和占位地址规则，队列里永远不保存会过期的上游直链；没有已装载
     * 队列时直接开始播放，避免菜单操作变成无反馈的空动作。
     */
    fun playNext(song: Song) {
        scope.launch {
            val remoteId = song.remoteId
            val isLocalFile = song.audioUri?.startsWith("file:") == true
            val offline = if (!isLocalFile && remoteId != null) {
                withContext(Dispatchers.IO) { downloadManager.findDownloaded(remoteId) }
            } else {
                null
            }
            val playable = when {
                offline != null -> offline.copy(favorited = song.favorited)
                !isLocalFile && remoteId != null -> song.copy(
                    audioUri = TencentMusicApi.placeholderUri(remoteId, qualityStore.playbackQuality().value),
                    lyricUri = song.lyricUri ?: "${TencentMusicApi.ENDPOINT}/api/v1/songs/$remoteId/lyrics",
                )
                else -> song
            }
            if (playable.audioUri.isNullOrBlank()) {
                message = "歌曲暂时没有可用播放链接"
                return@launch
            }
            if (audioPlayer.hasMedia) {
                audioPlayer.addNext(playable)
                message = "已加入下一首"
            } else {
                playSong(listOf(playable), 0)
            }
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
                // Material 3 默认 Snackbar 使用 inverseSurface；暗色主题下会变成浅色大块，
                // 与应用的深色表面脱节。显式使用主题层级色，让所有全局反馈保持一致。
                snackbarHost = {
                    SnackbarHost(snackbarHostState) { snackbarData ->
                        Snackbar(
                            snackbarData = snackbarData,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            actionColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
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
                    // Material 3 的默认底栏会取未配置的 surfaceContainer 色阶，容易回退成
                    // 紫灰色，与桃桃的珊瑚主题割裂。显式使用主题语义色，亮暗主题和将来的
                    // 配色调整都会从 ColorScheme 一处生效。
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        // 切换底部标签时要收起详情页、搜索页和设置页，否则 AnimatedContent 仍停在
                        // 原来的分支，用户点了「音乐」却还留在设置里。新增页面时必须同步这里、
                        // AnimatedContent 的 targetState 和 BackHandler 三处。
                        val switchTab = { target: Int ->
                            bottomTab = target
                            showPlayerDetail = false
                            showSearchPage = false
                            showSettingsPage = false
                            showProfilePage = false
                            mineLibrarySection = null
                        }
                        val navigationColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NavigationBarItem(
                            selected = bottomTab == 0,
                            onClick = { switchTab(0) },
                            icon = { Icon(Icons.Default.MusicNote, "音乐") },
                            label = { Text("音乐") },
                            colors = navigationColors,
                        )
                        NavigationBarItem(
                            selected = bottomTab == 1,
                            onClick = { switchTab(1) },
                            icon = { Icon(Icons.Default.AutoAwesome, "AI 工作台") },
                            label = { Text("AI") },
                            colors = navigationColors,
                        )
                        NavigationBarItem(
                            selected = bottomTab == 2,
                            onClick = { switchTab(2) },
                            icon = { Icon(Icons.Default.Person, "我的") },
                            label = { Text("我的") },
                            colors = navigationColors,
                        )
                    }
                    }
                },
            ) { innerPadding ->
            AnimatedContent(
                targetState = when {
                    showPlayerDetail -> "detail"
                    showProfilePage -> "profile"
                    showSettingsPage -> "settings"
                    showSearchPage -> "search"
                    mineLibrarySection == MineLibrarySection.FAVORITES -> "mine-favorites"
                    mineLibrarySection == MineLibrarySection.HISTORY -> "mine-history"
                    mineLibrarySection == MineLibrarySection.LOCAL -> "mine-local"
                    // 底部标签也参与：不带上它的话音乐 ⇄ 我的是硬切，
                    // 而其它换页都有过渡，观感上很不一致。
                    bottomTab == 1 -> "ai"
                    bottomTab == 2 -> "mine"
                    else -> "home"
                },
                transitionSpec = { pageTransition() },
                label = "页面切换",
            ) { page ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (page == "ai") {
                AiStudioPage(
                    signedIn = signedIn,
                    submitting = imageGenerating,
                    task = imageTask,
                    onGenerate = { prompt, ratio, quality ->
                        if (prompt.isBlank()) return@AiStudioPage
                        imageGenerating = true
                        imageTask = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { musicApi.createImageTask(prompt, ratio, quality) }
                            }.onSuccess { created ->
                                imageTask = created
                                while (created.taskId.isNotBlank() && imageTask?.state == "IN_PROGRESS") {
                                    delay(3_000)
                                    val latest = withContext(Dispatchers.IO) { musicApi.requestImageTask(created.taskId) }
                                    imageTask = latest
                                }
                            }.onFailure { error ->
                                message = error.message ?: "图片生成失败"
                            }
                            imageGenerating = false
                        }
                    },
                )
            } else if (page == "detail") {
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
                    repeatMode = audioPlayer.repeatMode,
                    onToggleRepeat = {
                        val nextMode = when (audioPlayer.repeatMode) {
                            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
                            androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
                            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
                        }
                        audioPlayer.updateRepeatMode(nextMode)
                    },
                    queue = playbackSongs,
                    queueIndex = selectedIndex,
                    onQueueItemClick = { index ->
                        // 播放器里已经装载了同一条队列，直接跳转即可，不必重新解析地址。
                        if (audioPlayer.hasMedia) audioPlayer.playAt(index)
                        else playSong(playbackSongs, index)
                    },
                    onRemoveQueueItem = { index -> audioPlayer.removeQueueItem(index) },
                    onMoveQueueItem = { from, to -> audioPlayer.moveQueueItem(from, to) },
                    onKeepOnlyCurrent = { audioPlayer.keepOnlyCurrent() },
                    history = playbackHistory,
                    localSongs = downloadedSongs,
                    onPlayHistory = { index ->
                        val songs = playbackHistory.map { it.song }
                        playSong(songs, index)
                    },
                    onPlayLocal = { index -> playSong(downloadedSongs, index) },
                    onPlayNext = { song -> playNext(song) },
                    isFavorite = { song -> favoritesStore.contains(song.remoteId) },
                    onToggleSongFavorite = { song -> toggleFavorite(song) },
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
                    onPlayNext = { song -> playNext(song) },
                    downloadedRevision = downloadedSongs.size,
                    isDownloaded = { song -> song.remoteId != null && song.remoteId in downloadedIds },
                    onLoadMore = { loadMoreSearch() },
                    isLoadingMore = isLoadingMore,
                    hasMore = searchHasMore,
                    total = searchTotal,
                    searchSession = searchGeneration,
                )
            } else if (page == "profile") {
                userProfile?.let { profile ->
                    AccountProfilePage(
                        api = musicApi,
                        profile = profile,
                        onProfileChanged = { userProfile = it },
                        onMessage = { message = it },
                        onBack = { showProfilePage = false },
                    )
                }
            } else if (page == "settings") {
                SettingsPage(
                    playbackQuality = playbackQuality,
                    downloadQuality = downloadQuality,
                    appearance = appearance,
                    profile = userProfile,
                    profileLoading = profileLoading,
                    onOpenProfile = {
                        if (userProfile != null) showProfilePage = true
                        else if (!profileLoading) {
                            profileLoading = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { musicApi.profile() } }
                                    .onSuccess { userProfile = it; showProfilePage = true }
                                    .onFailure { message = it.readableMessage() }
                                profileLoading = false
                            }
                        }
                    },
                    onPickPlaybackQuality = { qualitySheet = QualitySheetKind.PLAYBACK_DEFAULT },
                    onPickDownloadQuality = { qualitySheet = QualitySheetKind.DOWNLOAD_DEFAULT },
                    onPickAppearance = { mode ->
                        appearance = mode
                        appearanceStore.setMode(mode)
                    },
                    onBack = { showSettingsPage = false },
                )
            } else if (page == "mine-favorites") {
                MusicLibraryPage(
                    title = "收藏夹",
                    subtitle = "${favoriteLibrarySongs.size} 首 · 本地优先，后台同步账号收藏",
                    songs = favoriteLibrarySongs,
                    emptyTitle = "收藏夹还是空的",
                    emptyDescription = "点击歌曲旁的心形后，会通过收藏接口同步到这里",
                    onBack = { mineLibrarySection = null },
                    onSongClick = { index -> playSong(favoriteLibrarySongs, index) },
                    isFavorite = { song -> song.remoteId?.toString() in favoriteIds },
                    onToggleFavorite = { song -> toggleFavorite(song) },
                    onPlayNext = { song -> playNext(song) },
                    error = favoriteLibraryError,
                    onRetry = { refreshFavoriteLibrary() },
                )
            } else if (page == "mine-history") {
                PlaybackHistoryPage(
                    history = playbackHistory,
                    onBack = { mineLibrarySection = null },
                    onSongClick = { index -> playSong(playbackHistory.map { it.song }, index) },
                    onPlayNext = { song -> playNext(song) },
                    isFavorite = { song -> favoritesStore.contains(song.remoteId) },
                    onToggleFavorite = { song -> toggleFavorite(song) },
                    onClear = {
                        playbackHistoryStore.clear()
                        playbackHistory = emptyList()
                    },
                )
            } else if (page == "mine-local") {
                MusicLibraryPage(
                    title = "本地歌曲",
                    subtitle = "已下载到当前设备 · ${downloadedSongs.size} 首",
                    songs = downloadedSongs,
                    emptyTitle = "还没有本地歌曲",
                    emptyDescription = "下载完成的音乐会集中显示在这里",
                    onBack = { mineLibrarySection = null },
                    onSongClick = { index -> playSong(downloadedSongs, index) },
                    isFavorite = { song -> song.remoteId?.toString() in favoriteIds },
                    onToggleFavorite = { song -> toggleFavorite(song) },
                    onPlayNext = { song -> playNext(song) },
                    onDelete = { song -> pendingDelete = song },
                )
            } else if (page == "mine") {
                MinePage(
                    onLogout = {
                        audioPlayer.stop()
                        playbackStateStore.clear()
                        playbackHistoryStore.clear()
                        playbackHistory = emptyList()
                        // 收藏是账号状态，换账号不能沿用上一个人的。
                        favoritesStore.clear()
                        favoriteLibrarySongs = emptyList()
                        favoriteLibraryError = null
                        userProfile = null
                        // 撤销刷新令牌需要访问网络，放到 IO 线程；本地会话已在 signOut 内同步清空。
                        scope.launch(Dispatchers.IO) { authSession.signOut() }
                        signedIn = false
                    },
                    onOpenSettings = { showSettingsPage = true },
                    versionName = updateManager.installedVersionName,
                    checking = updateStatus.stage == UpdateStage.CHECKING,
                    onCheckUpdate = { scope.launch { updateManager.check(manual = true) } },
                    onMessage = { message = it },
                    favoriteCount = remember(favoriteRevision) { favoritesStore.ids().size },
                    historyCount = playbackHistory.size,
                    localCount = downloadedSongs.size,
                    onOpenFavorites = {
                        mineLibrarySection = MineLibrarySection.FAVORITES
                        refreshFavoriteLibrary()
                    },
                    onOpenHistory = { mineLibrarySection = MineLibrarySection.HISTORY },
                    onOpenLocal = { mineLibrarySection = MineLibrarySection.LOCAL },
                    profile = userProfile,
                    profileLoading = profileLoading,
                    onOpenAccount = { showSettingsPage = true },
                )
            } else Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                Spacer(Modifier.height(24.dp))
                HomeHeader(
                    userName = userProfile?.nickname ?: userProfile?.username ?: "音乐爱好者",
                    onOpenAnnouncements = { showAnnouncementDialog = true },
                )
                MusicSearchBar(searchKeyword, onKeywordChanged = { searchKeyword = it }, onSearch = {
                    if (searchKeyword.isNotBlank()) {
                        openSearchPage()
                        startSearch()
                    }
                }, onFocus = { openSearchPage() })
                Text("在线搜索歌曲，下载后可在无网络时播放", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                if (announcements.isNotEmpty()) {
                    AnnouncementPreview(announcements.first(), onClick = { showAnnouncementDialog = true })
                }
                SectionTitle("今日推荐")
                RecommendationCard()
                Spacer(Modifier.height(10.dp))
            }
            }
            }
            }
        }
    }

    // 弹窗和底部面板位于主 Surface 之后，必须显式继承当前外观主题；否则暗色模式会退回默认亮色。
    TaotaoTheme(darkTheme = darkTheme) {
        if (showAnnouncementDialog) {
            AnnouncementDialog(announcements = announcements, onDismiss = { showAnnouncementDialog = false })
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

@Composable
private fun HomeHeader(userName: String, onOpenAnnouncements: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("${timeGreeting()}，$userName", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Text("听点喜欢的", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onOpenAnnouncements) { Icon(Icons.Default.NotificationsNone, "公告") }
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
    onMessage: (String) -> Unit,
    favoriteCount: Int,
    historyCount: Int,
    localCount: Int,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLocal: () -> Unit,
    profile: TencentMusicApi.UserProfile?,
    profileLoading: Boolean,
    onOpenAccount: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val crashReporter = remember { CrashReporter(context) }
    var crashLogs by remember { mutableStateOf(emptyList<CrashLog>()) }
    var showCrashLogs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        crashLogs = withContext(Dispatchers.IO) { crashReporter.logs() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, top = 28.dp, end = 22.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("我的", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(18.dp),
                // 资料卡是进入昵称与邮箱管理的唯一入口，整块可点更容易发现。
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(62.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!profile?.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = profile?.avatarUrl,
                            contentDescription = "个人头像",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 15.dp)) {
                    Text(profile?.nickname ?: if (profileLoading) "正在读取资料…" else "桃桃音乐", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        profile?.email ?: "点击管理个人昵称与邮箱",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = onOpenAccount, enabled = !profileLoading) { Text(if (profileLoading) "读取中" else "设置") }
            }
        }
        item { Text("我的音乐", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MineLibraryShortcut(
                    icon = Icons.Default.Favorite,
                    iconDescription = "收藏夹",
                    title = "收藏夹",
                    count = favoriteCount,
                    onClick = onOpenFavorites,
                    modifier = Modifier.weight(1f),
                )
                MineLibraryShortcut(
                    icon = Icons.Default.History,
                    iconDescription = "最近播放",
                    title = "最近播放",
                    count = historyCount,
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f),
                )
                MineLibraryShortcut(
                    icon = Icons.Default.DownloadDone,
                    iconDescription = "本地歌曲",
                    title = "本地歌曲",
                    count = localCount,
                    onClick = onOpenLocal,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { Text("应用", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
        item {
            MineActionRow(
                icon = Icons.Default.Settings,
                iconDescription = "设置",
                title = "设置",
                subtitle = "音质、下载与外观",
                iconTint = TaotaoCoral,
                onClick = onOpenSettings,
            )
        }
        item {
            // 测试机无法连接 adb，崩溃堆栈只能在应用内查看和复制。
            MineActionRow(
                icon = Icons.Default.BugReport,
                iconDescription = "崩溃日志",
                title = "崩溃日志",
                subtitle = if (crashLogs.isEmpty()) "暂无记录" else "${crashLogs.size} 条记录",
                enabled = crashLogs.isNotEmpty(),
                iconTint = if (crashLogs.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral,
                onClick = { showCrashLogs = true },
            )
        }
        item {
            MineActionRow(
                icon = Icons.Default.SystemUpdate,
                iconDescription = "检查更新",
                title = "检查更新",
                subtitle = if (checking) "正在检查…" else "当前 $versionName",
                enabled = !checking,
                iconTint = TaotaoCoral,
                trailing = if (checking) {
                    { CircularProgressIndicator(Modifier.size(16.dp), color = TaotaoCoral, strokeWidth = 2.dp) }
                } else {
                    null
                },
                onClick = onCheckUpdate,
            )
        }
        item {
            MineActionRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                iconDescription = "退出登录",
                title = "退出登录",
                iconTint = TaotaoCoral,
                onClick = onLogout,
            )
        }
    }
    if (showCrashLogs) {
        val allLogs = crashLogs.joinToString("\n\n" + "=".repeat(40) + "\n\n") { "${it.name}\n${it.content}" }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showCrashLogs = false },
            title = { Text("崩溃日志（${crashLogs.size} 条）") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(allLogs, fontSize = 11.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = {
                // 导出成 .log 交给系统分享：多条堆栈叠起来轻易上万字符，
                // 剪贴板装不下，粘贴时换行也常被吃掉。
                TextButton(onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { crashReporter.exportToFile() }
                        if (file == null) {
                            onMessage("没有可导出的日志")
                            return@launch
                        }
                        runCatching { shareLogFile(context, file) }
                            .onFailure { onMessage(it.message ?: "导出失败") }
                    }
                }) { Text("导出 .log") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(allLogs)) }) {
                        Text("复制")
                    }
                    TextButton(onClick = {
                        crashReporter.clear()
                        crashLogs = emptyList()
                        showCrashLogs = false
                    }) { Text("清空") }
                }
            },
        )
    }
}

/** “我的音乐”三个入口固定在同一行，数量与入口含义一眼即可比较。 */
@Composable
private fun MineLibraryShortcut(
    icon: ImageVector,
    iconDescription: String,
    title: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.height(118.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(TaotaoCoral.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, iconDescription, tint = TaotaoCoral, modifier = Modifier.size(19.dp))
        }
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                "$count 首",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** 设置、更新等入口使用同一套行组件，避免「我的」页出现四种不一致的卡片规则。 */
@Composable
private fun MineActionRow(
    icon: ImageVector,
    iconDescription: String,
    title: String,
    iconTint: Color,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, iconDescription, tint = iconTint)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        trailing?.invoke()
    }
}

/**
 * 把导出的日志文件交给系统分享面板。
 *
 * 必须走 FileProvider 换成 content:// —— Android 7 起直接传 file:// 给别的应用会抛
 * FileUriExposedException。`crash/export/` 已在 file_paths.xml 里声明。
 */
private fun shareLogFile(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "导出崩溃日志").apply {
        // 从非 Activity 上下文启动分享面板需要这个标记。
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

@Composable private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 14.dp, bottom = 12.dp))
}

@Composable private fun RecommendationCard() {
    Row(
        Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("专属歌单", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "给今天的你\n一点好心情",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 31.sp,
            )
            Text(
                "20 首 · 精选推荐",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        AlbumArt(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f), 112.dp, 64.sp)
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
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onKeepOnlyCurrent: () -> Unit,
    history: List<PlaybackHistoryEntry>,
    localSongs: List<Song>,
    onPlayHistory: (Int) -> Unit,
    onPlayLocal: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleSongFavorite: (Song) -> Unit,
    onMessage: (String) -> Unit,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    playbackQuality: Int,
    onPickQuality: () -> Unit,
) {
    // 播放器维护唯一进度源；拖动期间才暂存本地位置，松手立即交回播放器同步。
    var draggedPositionMs by remember(song) { mutableIntStateOf(audioPlayer.positionMs) }
    var dragging by remember(song) { mutableStateOf(false) }
    val positionMs = if (dragging) draggedPositionMs else audioPlayer.positionMs
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
        // positionMs 已在详情页顶部读取为播放器的唯一状态源。这里直接计算，避免
        // 无 key 的 remember 捕获初始值而让进度条停在首次进入详情页的位置。
        val progress = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        Slider(
            value = progress,
            onValueChange = { dragging = true; draggedPositionMs = (it * durationMs).toInt() },
            onValueChangeFinished = {
                audioPlayer.seekTo(draggedPositionMs)
                dragging = false
            },
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
                val repeatEnabled = repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF
                val tint by animateColorAsState(
                    targetValue = if (repeatEnabled) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = taotaoTween(AnimationDurations.MICRO),
                    label = "循环着色",
                )
                Icon(
                    if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    repeatModeTitle(repeatMode),
                    tint = tint,
                )
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(34.dp)) }
            FilledIconButton(
                onClick = onTogglePlaying,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                PlayPauseIcon(actualPlaying, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimary)
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

/** 播放队列面板：展示当前队列并支持直接跳到某一首。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackQueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onItemClick: (Int) -> Unit,
    repeatMode: Int,
    onCycleRepeat: () -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onKeepOnlyCurrent: () -> Unit,
    history: List<PlaybackHistoryEntry>,
    localSongs: List<Song>,
    onPlayHistory: (Int) -> Unit,
    onPlayLocal: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
) {
    var selectedSource by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("播放列表", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when (selectedSource) {
                            1 -> "从最近播放重新开始"
                            2 -> "从本地下载中选择"
                            else -> if (queue.isEmpty()) "当前没有歌曲" else "第 ${(currentIndex + 1).coerceAtMost(queue.size)} 首 · 共 ${queue.size} 首"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                if (selectedSource == 0) {
                    TextButton(onClick = onKeepOnlyCurrent, enabled = queue.size > 1) {
                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("只留当前")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TabRow(
                selectedTabIndex = selectedSource,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = TaotaoCoral,
            ) {
                listOf("当前 ${queue.size}", "最近 ${history.size}", "本地 ${localSongs.size}").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedSource == index,
                        onClick = { selectedSource = index },
                        text = { Text(label, fontSize = 13.sp, fontWeight = if (selectedSource == index) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            /**
             * 三类来源共享固定的正文视口，不能让空态、短列表和长列表反复改变 BottomSheet 高度。
             * 顶部标题、Tab 和底部圆角的位置因而保持稳定；歌曲多时只在这块区域内滚动。
             */
            Box(Modifier.fillMaxWidth().height(PlaybackQueueContentHeight)) {
                AnimatedContent(
                    targetState = selectedSource,
                    transitionSpec = { contentFadeIn() togetherWith contentFadeOut() using null },
                    label = "播放队列来源",
                ) { source ->
                    when (source) {
                        1 -> PlaybackSourceList(
                            songs = history.map { it.song },
                            emptyText = "还没有播放记录",
                            onItemClick = { index ->
                                onDismiss()
                                onPlayHistory(index)
                            },
                            onPlayNext = onPlayNext,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                        )
                        2 -> PlaybackSourceList(
                            songs = localSongs,
                            emptyText = "还没有本地歌曲",
                            onItemClick = { index ->
                                onDismiss()
                                onPlayLocal(index)
                            },
                            onPlayNext = onPlayNext,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                        )
                        else -> CurrentPlaybackQueue(
                            queue = queue,
                            currentIndex = currentIndex,
                            repeatMode = repeatMode,
                            onCycleRepeat = onCycleRepeat,
                            onItemClick = onItemClick,
                            onRemoveItem = onRemoveItem,
                            onMoveItem = onMoveItem,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 只依赖设备本地时区，离线时也能给出符合当前时段的问候。 */
private fun timeGreeting(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String =
    GreetingFormatter.greetingForHour(hour)

/** 首页只占一行展示最新或置顶公告，详情放进弹层，避免正文挤占搜索与推荐内容。 */
@Composable
private fun AnnouncementPreview(announcement: TencentMusicApi.Announcement, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Campaign, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Text(
            text = if (announcement.pinned) "置顶 · ${announcement.title}" else announcement.title,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
    }
}

private val PlaybackQueueContentHeight = 360.dp

@Composable
private fun CurrentPlaybackQueue(
    queue: List<Song>,
    currentIndex: Int,
    repeatMode: Int,
    onCycleRepeat: () -> Unit,
    onItemClick: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface).clickable(onClick = onCycleRepeat)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                null,
                tint = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    TaotaoCoral
                },
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(repeatModeTitle(repeatMode), fontWeight = FontWeight.Bold)
                Text(repeatModeDescription(repeatMode), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Text("点击切换", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        if (queue.isEmpty()) {
            PlaybackQueueEmptyState("队列为空", Modifier.weight(1f))
        } else {
            var draggedIndex by remember { mutableIntStateOf(-1) }
            var draggedDistance by remember { mutableFloatStateOf(0f) }
            // 触发阈值低于行高，长按后会连续跟手；未用完的距离会保留，
            // 避免每次换位都出现“吸回去再跳”的停顿。
            val dragStep = with(LocalDensity.current) { 34.dp.toPx() }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(queue, key = { index, item -> "${songKeyOf(item)}#$index" }) { index, item ->
                    val isCurrent = index == currentIndex
                    SongRow(
                        song = item,
                        active = isCurrent,
                        subtitle = if (isCurrent) "正在播放 · ${item.artist}" else item.artist,
                        onClick = { onItemClick(index) },
                        onDelete = { onRemoveItem(index) }.takeUnless { isCurrent },
                        favorited = isFavorite(item),
                        modifier = Modifier
                            .animateItem(
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                            .graphicsLayer {
                                // 手势是连续反馈，整行随手移动；其余行由 placement spring 让位。
                                translationY = if (index == draggedIndex) draggedDistance else 0f
                            },
                        onToggleFavorite = onToggleFavorite.takeIf { item.remoteId != null }
                            ?.let { callback -> { callback(item) } },
                        dragHandle = { modifier ->
                            Icon(
                                Icons.Default.DragHandle,
                                "长按拖动调整顺序",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = modifier.pointerInput(queue, index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggedIndex = index; draggedDistance = 0f },
                                        onDragEnd = { draggedIndex = -1; draggedDistance = 0f },
                                        onDragCancel = { draggedIndex = -1; draggedDistance = 0f },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        if (draggedIndex !in queue.indices) return@detectDragGesturesAfterLongPress
                                        draggedDistance += dragAmount.y
                                        while (kotlin.math.abs(draggedDistance) >= dragStep) {
                                            val direction = if (draggedDistance > 0f) 1 else -1
                                            val destination = (draggedIndex + direction).coerceIn(queue.indices)
                                            if (destination == draggedIndex) {
                                                // 到边界仍有阻尼，而不会把项目无限拖出列表。
                                                draggedDistance *= 0.35f
                                                break
                                            }
                                            onMoveItem(draggedIndex, destination)
                                            draggedIndex = destination
                                            draggedDistance -= direction * dragStep
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 最近播放和本地歌曲只是队列来源，点中后会用该来源的顺序替换当前列表。 */
@Composable
private fun PlaybackSourceList(
    songs: List<Song>,
    emptyText: String,
    onItemClick: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 说明区域无论有没有歌曲都保留，避免空态切到有内容时正文又向下移动一次。
        Text(
            "点一首后，将按这个列表的顺序继续播放",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (songs.isEmpty()) {
            PlaybackQueueEmptyState(emptyText, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(songs) { index, item ->
                    SongRow(
                        song = item,
                        downloaded = item.audioUri?.startsWith("file:") == true,
                        onClick = { onItemClick(index) },
                        onPlayNext = { onPlayNext(item) },
                        favorited = isFavorite(item),
                        onToggleFavorite = onToggleFavorite.takeIf { item.remoteId != null }
                            ?.let { callback -> { callback(item) } },
                    )
                }
            }
        }
    }
}

/** 固定正文视口中的空态：信息在剩余空间居中，不影响 BottomSheet 的整体高度。 */
@Composable
private fun PlaybackQueueEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

private fun repeatModeTitle(mode: Int): String = when (mode) {
    androidx.media3.common.Player.REPEAT_MODE_ALL -> "列表循环"
    androidx.media3.common.Player.REPEAT_MODE_ONE -> "单曲循环"
    else -> "顺序播放"
}

private fun repeatModeDescription(mode: Int): String = when (mode) {
    androidx.media3.common.Player.REPEAT_MODE_ALL -> "播完最后一首后从第一首继续"
    androidx.media3.common.Player.REPEAT_MODE_ONE -> "当前歌曲会一直重复播放"
    else -> "按列表顺序播放，最后一首播完即停"
}

private fun formatTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
