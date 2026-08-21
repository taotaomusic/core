package com.taotao.music.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.taotao.music.data.OfflineDownloadManager
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.SearchHistoryStore
import com.taotao.music.data.AuthSession
import com.taotao.music.data.PlaybackStateStore
import com.taotao.music.data.SavedPlaybackState
import com.taotao.music.player.AudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.lifecycle.LifecycleEventObserver
import android.net.Uri
import java.io.File
import java.net.URL

private val Background = Color(0xFFFFF9F7)
private val Coral = Color(0xFFFF6B5F)

@Composable
fun TaotaoMusicApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioPlayer = remember { AudioPlayer(context) }
    val authSession = remember { AuthSession(context) }
    val musicApi = remember { TencentMusicApi { authSession.accessToken } }
    val downloadManager = remember { OfflineDownloadManager(context) { authSession.accessToken } }
    val playbackStateStore = remember { PlaybackStateStore(context) }
    val searchHistoryStore = remember { SearchHistoryStore(context) }
    val scope = rememberCoroutineScope()
    var playbackSongs by remember { mutableStateOf(emptyList<Song>()) }
    var searchResults by remember { mutableStateOf(emptyList<Song>()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
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
    var accessToken by remember { mutableStateOf(authSession.accessToken) }
    var authChecking by remember { mutableStateOf(true) }
    var restoredPlayback by remember { mutableStateOf<SavedPlaybackState?>(null) }
    var pendingResumePositionMs by remember { mutableIntStateOf(0) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    val latestPlaybackSongs = rememberUpdatedState(playbackSongs)
    val latestSelectedIndex = rememberUpdatedState(selectedIndex)

    LaunchedEffect(Unit) {
        restoredPlayback = playbackStateStore.read()
    }

    LaunchedEffect(Unit) {
        if (authSession.isSignedIn && !authSession.isAccessValid) {
            if (authSession.refresh(musicApi)) accessToken = authSession.accessToken
        }
        authChecking = false
    }

    if (authChecking) return
    if (accessToken.isNullOrBlank()) {
        AuthPage(musicApi) { tokens ->
            authSession.save(tokens)
            accessToken = tokens.accessToken
        }
        return
    }

    LaunchedEffect(restoredPlayback) {
        restoredPlayback?.let { savedState ->
            if (playbackSongs.isEmpty()) {
                playbackSongs = listOf(savedState.song)
                pendingResumePositionMs = savedState.positionMs
            }
        }
    }

    LaunchedEffect(Unit) {
        downloadedSongs = withContext(Dispatchers.IO) { downloadManager.listDownloaded() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                if (audioPlayer.isPrepared()) {
                    latestPlaybackSongs.value.getOrNull(latestSelectedIndex.value)?.let { song ->
                        playbackStateStore.save(song, audioPlayer.currentPositionMs())
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var saveTick = 0
            while (isActive) {
                isPlaying = audioPlayer.isPlaying()
                if (playbackSongs.isNotEmpty()) {
                    val playerIndex = audioPlayer.currentMediaItemIndex()
                    if (playerIndex in playbackSongs.indices && playerIndex != selectedIndex) {
                        selectedIndex = playerIndex
                    }
                    if (saveTick++ % 7 == 0 && audioPlayer.isPrepared()) {
                        playbackSongs.getOrNull(selectedIndex)?.let { song ->
                            playbackStateStore.save(song, audioPlayer.currentPositionMs())
                        }
                    }
                }
                kotlinx.coroutines.delay(300)
            }
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
            if (generation == searchGeneration) {
                runCatching { withContext(Dispatchers.IO) { musicApi.search(query) } }
                    .onSuccess { searchResults = it }
                    .onFailure { searchError = it.message ?: "搜索失败，请稍后重试" }
                isSearching = false
            }
        }
    }

    fun playSong(queue: List<Song>, index: Int, positionMs: Int = 0) {
        val requestedSong = queue.getOrNull(index) ?: return
        scope.launch {
            val playable = if (requestedSong.audioUri?.startsWith("file:") == true) {
                requestedSong
            } else if (requestedSong.remoteId != null) {
                runCatching {
                    withContext(Dispatchers.IO) { musicApi.resolve(requestedSong) }
                }.getOrElse { requestedSong }
            } else {
                requestedSong
            }
            if (playable.audioUri.isNullOrBlank()) {
                message = "歌曲暂时没有可用播放链接"
                return@launch
            }
            val updatedQueue = queue.toMutableList().also { it[index] = playable }
            playbackSongs = updatedQueue
            selectedIndex = index
            audioPlayer.play(playable, updatedQueue, index, positionMs)
            playbackStateStore.save(playable, positionMs)
            pendingResumePositionMs = 0
            isPlaying = true
        }
    }

    fun togglePlayback() {
        val currentSong = playbackSongs.getOrNull(selectedIndex) ?: return
        if (audioPlayer.isPlaying()) {
            audioPlayer.pause()
            playbackStateStore.save(currentSong, audioPlayer.currentPositionMs())
            isPlaying = false
        } else if (audioPlayer.isPrepared()) {
            audioPlayer.resume()
            isPlaying = true
        } else {
            playSong(playbackSongs, selectedIndex, pendingResumePositionMs)
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(background = Background, primary = Coral)) {
        Surface(color = Background, modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Background,
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
                    (fadeIn() + slideInHorizontally { it / 10 }) togetherWith
                        (fadeOut() + slideOutHorizontally { -it / 10 })
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
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val resolved = if (playbackSongs[selectedIndex].audioUri == null) musicApi.resolve(playbackSongs[selectedIndex]) else playbackSongs[selectedIndex]
                                downloadManager.download(resolved)
                            }.onSuccess { offline ->
                                playbackSongs = playbackSongs.toMutableList().also { it[selectedIndex] = offline }
                                downloadedSongs = withContext(Dispatchers.IO) { downloadManager.listDownloaded() }
                                message = "已下载，可离线播放"
                            }
                                .onFailure { message = it.message ?: "下载失败" }
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
                MinePage(onLogout = { audioPlayer.release(); authSession.clear(); accessToken = null })
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
                message?.let { Text(it, color = Coral, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
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
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("我的", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null, tint = Coral)
            Text("账号设置", modifier = Modifier.weight(1f).padding(start = 12.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onLogout).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ExitToApp, "退出登录", tint = Coral)
            Text("退出登录", modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable private fun CategoryTabs(selectedIndex: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("为你推荐", "每日推荐", "歌单").forEachIndexed { index, label ->
            Text(label, fontSize = 16.sp,
                fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Normal,
                color = if (selectedIndex == index) Coral else Color.Gray,
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
            Text("专属歌单", color = Coral, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("给今天的你\n一点好心情", fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
            Text("20 首 · 精选推荐", color = Color.DarkGray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        AlbumArt(Color(0xFFFFB4A2), 112.dp, 64.sp)
    }
}

@Composable fun AlbumArt(color: Color, size: Dp, iconSize: TextUnit, imageUri: String? = null) {
    if (!imageUri.isNullOrBlank()) {
        AsyncImage(
            model = imageUri,
            contentDescription = "专辑封面",
            modifier = Modifier.size(size).clip(if (size > 80.dp) CircleShape else RoundedCornerShape(12.dp)),
        )
    } else {
        Box(Modifier.size(size).clip(if (size > 80.dp) CircleShape else RoundedCornerShape(12.dp)).background(color), contentAlignment = Alignment.Center) {
            Text("♫", color = Color.White, fontSize = iconSize)
        }
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
) {
    var positionMs by remember(song) { mutableIntStateOf(0) }
    var durationMs by remember(song) { mutableIntStateOf(0) }
    var dragging by remember(song) { mutableStateOf(false) }
    var lyricText by remember(song) { mutableStateOf<String?>(null) }
    var actualPlaying by remember(song) { mutableStateOf(false) }
    var favorite by remember(song) { mutableStateOf(false) }
    var favoriteLoading by remember(song) { mutableStateOf(false) }
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
        favorite = runCatching { withContext(Dispatchers.IO) { musicApi.isFavorite(song) } }.getOrDefault(false)
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
            if (!dragging) positionMs = audioPlayer.currentPositionMs()
            durationMs = audioPlayer.durationMs()
            actualPlaying = audioPlayer.isPlaying()
            kotlinx.coroutines.delay(500)
            }
        }
    }
    LaunchedEffect(song, isPlaying, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (isPlaying) {
            while (true) {
                val nextRotation = coverRotation.value + 360f
                coverRotation.animateTo(
                    targetValue = nextRotation,
                    animationSpec = tween(durationMillis = 20_000, easing = LinearEasing),
                )
            }
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
                enabled = !favoriteLoading,
                onClick = {
                    favoriteLoading = true
                    detailScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { musicApi.setFavorite(song, !favorite) }
                        }.onSuccess { favorite = !favorite }
                        favoriteLoading = false
                    }
                },
            ) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏", tint = Coral) }
        }
        Spacer(Modifier.height(28.dp))
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        Slider(value = progress.coerceIn(0f, 1f), onValueChange = { dragging = true; positionMs = (it * durationMs).toInt() }, onValueChangeFinished = { dragging = false; audioPlayer.seekTo(positionMs) }, colors = SliderDefaults.colors(thumbColor = Coral, activeTrackColor = Coral))
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
                    tint = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Coral else Color.Gray,
                )
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(34.dp)) }
            FilledIconButton(onClick = onTogglePlaying, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Coral)) {
                Icon(if (actualPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "播放", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(34.dp)) }
            IconButton(onClick = {}) { Icon(Icons.Default.QueueMusic, "播放队列", tint = Color.Gray) }
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).clickable(onClick = onDownload).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MusicNote, null, tint = Coral)
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
}

private fun formatTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable fun SongListItem(song: Song, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(Color(song.color), 48.dp, 24.sp, song.coverUri)
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(song.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            Text(song.artist, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Text(song.duration, color = Color.Gray, fontSize = 12.sp)
        Icon(Icons.Default.MoreVert, "更多", tint = Color.Gray, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlaying: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).clickable(onClick = onOpen).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AlbumArt(Color(song.color), 44.dp, 22.sp, song.coverUri)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, fontWeight = FontWeight.Bold)
            Text(song.artist, color = Color.Gray, fontSize = 12.sp)
        }
        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "上一首") }
        IconButton(onClick = onTogglePlaying) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "播放") }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "下一首") }
    }
}
