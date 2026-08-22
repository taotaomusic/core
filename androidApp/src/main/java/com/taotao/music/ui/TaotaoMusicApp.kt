package com.taotao.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.taotao.music.model.Song
import com.taotao.music.data.CrashLog
import com.taotao.music.data.CrashReporter
import com.taotao.music.data.OfflineDownloadManager
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.SearchHistoryStore
import com.taotao.music.data.AuthSession
import com.taotao.music.data.PlaybackStateStore
import com.taotao.music.data.SavedPlaybackState
import com.taotao.music.player.AudioPlayer
import com.taotao.music.update.UpdateManager
import com.taotao.music.update.UpdateStage
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
    val latestPlaybackSongs = rememberUpdatedState(playbackSongs)
    val latestSelectedIndex = rememberUpdatedState(selectedIndex)

    /** 播放状态由播放器事件驱动，不再轮询播放服务。 */
    val isPlaying = audioPlayer.isPlaying

    /**
     * 物理返回键：详情页先收起详情，搜索页先退出搜索，都不在时禁用拦截，
     * 交回系统默认行为（退出应用）—— 这样不必自己去拿 onBackPressedDispatcher。
     */
    BackHandler(enabled = showPlayerDetail || showSearchPage) {
        when {
            showPlayerDetail -> showPlayerDetail = false
            showSearchPage -> showSearchPage = false
        }
    }

    /**
     * 热更新检查。刻意放在登录门禁之前：最需要强制更新的场景恰恰是上一个版本把登录搞坏了，
     * 若要求先登录才能看到更新页，坏版本的用户就永远走不出来。
     */
    val updateManager = remember { UpdateManager(context, authSession) }
    LaunchedEffect(updateManager) { updateManager.check() }
    val updateStatus = updateManager.status
    if (updateStatus.blocking) {
        ForceUpdatePage(
            status = updateStatus,
            onDownload = { scope.launch { updateManager.download() } },
            onInstall = { updateManager.install() },
            onRetry = { scope.launch { updateManager.retry() } },
        )
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
        AuthPage(musicApi) { tokens ->
            authSession.save(tokens)
            signedIn = true
        }
        return
    }

    LaunchedEffect(Unit) {
        // 队列 JSON 可能不小，别在主线程读盘拖慢启动。
        restoredPlayback = withContext(Dispatchers.IO) { playbackStateStore.read() }
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
            val result = runCatching { withContext(Dispatchers.IO) { musicApi.search(query) } }
            // 请求返回后再次校验代次：期间用户可能已经发起新搜索或退出搜索页，旧结果不应覆盖新状态。
            if (generation != searchGeneration) return@launch
            result
                .onSuccess { searchResults = it }
                .onFailure { searchError = it.message ?: "搜索失败，请稍后重试" }
            isSearching = false
        }
    }

    fun playSong(queue: List<Song>, index: Int, positionMs: Int = 0) {
        val requestedSong = queue.getOrNull(index) ?: return
        scope.launch {
            val isLocalFile = requestedSong.audioUri?.startsWith("file:") == true
            val playable = if (!isLocalFile && requestedSong.remoteId != null) {
                // 解析失败时明确提示，而不是拿着可能已过期的旧地址静默重试。
                runCatching { withContext(Dispatchers.IO) { musicApi.resolve(requestedSong) } }
                    .getOrElse {
                        message = it.message ?: "无法获取播放地址，请稍后重试"
                        return@launch
                    }
            } else {
                requestedSong
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
    TaotaoTheme {
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
        Surface(color = TaotaoBackground, modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = TaotaoBackground,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column {
                    if (!showPlayerDetail && playbackSongs.isNotEmpty()) {
                        MiniPlayer(
                            playbackSongs[selectedIndex.coerceIn(playbackSongs.indices)],
                            isPlaying,
                            onOpen = { showPlayerDetail = true },
                            onPrevious = { audioPlayer.previous() },
                            onNext = { audioPlayer.next() },
                        ) {
                            togglePlayback()
                        }
                    }
                    NavigationBar {
                        NavigationBarItem(bottomTab == 0, { bottomTab = 0 }, icon = { Icon(Icons.Default.MusicNote, "音乐") }, label = { Text("音乐") })
                        NavigationBarItem(bottomTab == 1, { bottomTab = 1 }, icon = { Icon(Icons.Default.Person, "我的") }, label = { Text("我的") })
                    }
                    }
                },
            ) { innerPadding ->
            AnimatedContent(
                targetState = when {
                    showPlayerDetail -> "detail"
                    showSearchPage -> "search"
                    else -> "home"
                },
                transitionSpec = {
                    (pageFadeIn() + pageSlideIn()) togetherWith (pageFadeOut() + pageSlideOut())
                },
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
                        val targetIndex = selectedIndex
                        if (target == null) {
                            message = "没有正在播放的歌曲"
                        } else if (target.audioUri?.startsWith("file:") == true) {
                            message = "这首歌已经下载过了"
                        } else scope.launch {
                            message = "正在下载…"
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    // 下载前重新解析，确保播放地址携带的是当前有效的访问令牌。
                                    val resolved = if (target.remoteId != null) musicApi.resolve(target) else target
                                    downloadManager.download(resolved)
                                }
                            }.onSuccess { offline ->
                                if (playbackSongs.getOrNull(targetIndex)?.remoteId == offline.remoteId) {
                                    playbackSongs = playbackSongs.toMutableList().also { it[targetIndex] = offline }
                                }
                                downloadedSongs = withContext(Dispatchers.IO) { downloadManager.listDownloaded() }
                                message = "已下载，可离线播放"
                            }.onFailure { message = it.message ?: "下载失败" }
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
                )
            } else if (bottomTab == 1) {
                MinePage(onLogout = {
                    audioPlayer.stop()
                    playbackStateStore.clear()
                    // 撤销刷新令牌需要访问网络，放到 IO 线程；本地会话已在 signOut 内同步清空。
                    scope.launch(Dispatchers.IO) { authSession.signOut() }
                    signedIn = false
                })
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
                        Text("还没有下载歌曲", color = Color.Gray)
                    } else {
                        downloadedSongs.forEachIndexed { index, song ->
                            SongListItem(song, false) {
                                playSong(downloadedSongs, index)
                            }
                        }
                    }
                }
                Text("在线搜索歌曲，下载后可在无网络时播放", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                SectionTitle("今日推荐")
                RecommendationCard()
                Spacer(Modifier.height(10.dp))
            }
            }
            }
            }
        }
    }
}

@Composable private fun HomeHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("早上好，桃桃", color = Color.Gray, fontSize = 14.sp)
            Text("听点喜欢的", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = {}) { Icon(Icons.Default.NotificationsNone, "通知") }
                Spacer(Modifier.width(48.dp))
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun MinePage(onLogout: () -> Unit) {
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
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null, tint = TaotaoCoral)
            Text("账号设置", modifier = Modifier.weight(1f).padding(start = 12.dp))
        }
        Spacer(Modifier.height(14.dp))
        // 测试机无法连接 adb，崩溃堆栈只能在应用内查看和复制。
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
                .clickable(enabled = crashLogs.isNotEmpty()) { showCrashLogs = true }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.BugReport, "崩溃日志", tint = if (crashLogs.isEmpty()) Color.Gray else TaotaoCoral)
            Text(
                if (crashLogs.isEmpty()) "崩溃日志（暂无记录）" else "崩溃日志（${crashLogs.size} 条）",
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                color = if (crashLogs.isEmpty()) Color.Gray else Color.Unspecified,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onLogout).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
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
            Text(label, fontSize = 16.sp,
                fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                color = if (selectedIndex == index) TaotaoCoral else Color.Gray,
                modifier = Modifier.clickable { onSelected(index) }.padding(vertical = 8.dp))
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
            Text("20 首 · 精选推荐", color = Color.DarkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
) {
    var positionMs by remember(song) { mutableIntStateOf(0) }
    var dragging by remember(song) { mutableStateOf(false) }
    // 这两组状态的 remember key 必须与下面对应 LaunchedEffect 的 key 一致：
    // 解析播放地址后队列里的 Song 会被换成新副本，song 变了但 remoteId / lyricUri 没变，
    // key 不一致就会出现「状态被清空、拉取逻辑却不重跑」的空白歌词和收藏状态丢失。
    var lyricText by remember(song.lyricUri, song.remoteId) { mutableStateOf<String?>(null) }
    var favorite by remember(song.remoteId) { mutableStateOf(false) }
    var favoriteLoading by remember(song.remoteId) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    // 时长和播放态直接读播放器暴露的状态，不再各自轮询。
    val durationMs = audioPlayer.durationMs
    val actualPlaying = audioPlayer.isPlaying
    val detailScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val coverRotation = remember(song) { Animatable(0f) }
    LaunchedEffect(song.lyricUri, song.remoteId) {
        lyricText = runCatching {
            withContext(Dispatchers.IO) {
                val lyricUri = song.lyricUri
                if (lyricUri?.startsWith("file:") == true) {
                    Uri.parse(lyricUri).path
                        ?.let(::File)
                        ?.takeIf(File::isFile)
                        ?.readText()
                } else if (song.remoteId != null) {
                    musicApi.requestLyric(song)
                } else {
                    null
                }
            }
        }.getOrNull()
    }
    LaunchedEffect(song.remoteId) {
        // 本地歌曲没有服务端 ID，跳过收藏状态查询，避免无意义的请求。
        favorite = song.remoteId?.let {
            runCatching { withContext(Dispatchers.IO) { musicApi.isFavorite(song) } }.getOrDefault(false)
        } ?: false
    }
    // key 必须包含 song：positionMs / dragging 是 remember(song)，切歌后会换成新的 state 对象，
    // 若 ticker 不跟着重启，就会一直往已被丢弃的旧对象里写进度，界面上停在 0:00。
    LaunchedEffect(song, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                if (!dragging) positionMs = audioPlayer.currentPositionMs()
                kotlinx.coroutines.delay(500)
            }
        }
    }
    LaunchedEffect(song, isPlaying, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!isPlaying) return@repeatOnLifecycle
            while (isActive) {
                // 角度对 360 取模，避免长时间播放后累加成很大的数值。
                coverRotation.snapTo(coverRotation.value % 360f)
                coverRotation.animateTo(
                    targetValue = coverRotation.value + 360f,
                    animationSpec = tween(durationMillis = 20_000, easing = LinearEasing),
                )
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.KeyboardArrowDown, "收起") }
            Text("正在播放", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "更多") }
        }
        Spacer(Modifier.height(38.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!song.coverUri.isNullOrBlank()) AsyncImage(
                model = song.coverUri,
                contentDescription = "专辑封面",
                modifier = Modifier
                    .size(292.dp)
                    .graphicsLayer { rotationZ = coverRotation.value }
                    .clip(CircleShape),
            )
            else AlbumArt(Color(song.color), 292.dp, 132.sp)
        }
        Spacer(Modifier.height(34.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(song.artist, color = Color.Gray, fontSize = 15.sp, modifier = Modifier.padding(top = 7.dp))
            }
            IconButton(
                // 收藏依赖服务端歌曲 ID，纯本地歌曲不提供该操作。
                enabled = !favoriteLoading && song.remoteId != null,
                onClick = {
                    favoriteLoading = true
                    detailScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { musicApi.setFavorite(song, !favorite) }
                        }.onSuccess { favorite = !favorite }
                            .onFailure { onMessage(it.message ?: "收藏操作失败，请稍后重试") }
                        favoriteLoading = false
                    }
                },
            ) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏", tint = TaotaoCoral) }
        }
        Spacer(Modifier.height(28.dp))
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        Slider(value = progress.coerceIn(0f, 1f), onValueChange = { dragging = true; positionMs = (it * durationMs).toInt() }, onValueChangeFinished = { dragging = false; audioPlayer.seekTo(positionMs) }, colors = SliderDefaults.colors(thumbColor = TaotaoCoral, activeTrackColor = TaotaoCoral))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionMs), color = Color.Gray, fontSize = 12.sp)
            Text(
                formatTime(durationMs).takeIf { durationMs > 0 } ?: "--:--",
                color = Color.Gray,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = onToggleRepeat) {
                Icon(
                    Icons.Default.Repeat,
                    if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) "单曲循环" else "循环",
                    tint = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) TaotaoCoral else Color.Gray,
                )
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(34.dp)) }
            FilledIconButton(onClick = onTogglePlaying, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = TaotaoCoral)) {
                Icon(if (actualPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "播放", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(34.dp)) }
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列", tint = if (queue.isEmpty()) Color.Gray else TaotaoCoral)
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick = onDownload).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = TaotaoCoral)
            Text("下载歌曲、封面和歌词", modifier = Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.Medium)
            Icon(Icons.Default.Download, "下载", tint = Color.Gray)
        }
        lyricText?.let { lyrics ->
            Spacer(Modifier.height(18.dp))
            Text("歌词", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(lyrics, color = Color.DarkGray, lineHeight = 24.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(24.dp))
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = TaotaoBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("播放队列", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${queue.size} 首", color = Color.Gray, fontSize = 13.sp)
            }
            if (queue.isEmpty()) {
                Text("队列为空", color = Color.Gray, modifier = Modifier.padding(vertical = 24.dp))
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
                                    color = Color.Gray,
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
                                Text(item.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                            }
                            Text(item.duration, color = Color.Gray, fontSize = 12.sp)
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
