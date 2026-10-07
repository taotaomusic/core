package com.taotao.music.ui.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import com.taotao.music.TaotaoApplication
import com.taotao.music.data.AppearanceMode
import com.taotao.music.data.AppearanceStore
import com.taotao.music.data.ArtistSearchResult
import com.taotao.music.data.AlbumSearchResult
import com.taotao.music.data.AuthSession
import com.taotao.music.data.crypto.HardwareDeviceId
import com.taotao.music.crypto.CryptoTransport
import com.taotao.music.data.DeviceIdStore
import com.taotao.music.data.DownloadNotifier
import com.taotao.music.data.FavoritesStore
import com.taotao.music.data.ImPeerStore
import com.taotao.music.data.OfflineDownloadManager
import com.taotao.music.data.PendingPlaybackSnapshot
import com.taotao.music.data.PlaybackHistoryEntry
import com.taotao.music.data.PlaybackHistoryStore
import com.taotao.music.data.PlaybackSnapshotPolicy
import com.taotao.music.data.PlaybackStateStore
import com.taotao.music.data.PlaylistSearchResult
import com.taotao.music.data.PlaybackSyncCoordinator
import com.taotao.music.data.PlaybackSyncStore
import com.taotao.music.data.QualityStore
import com.taotao.music.data.SavedPlaybackState
import com.taotao.music.data.SearchHistoryStore
import com.taotao.music.data.SleepTimerStore
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.bindDownloadedSongs
import com.taotao.music.data.im.ImSessionStore
import com.taotao.music.data.im.WukongImClient
import com.taotao.music.model.Song
import com.taotao.music.player.AudioPlayer
import com.taotao.music.ui.common.dedupeSongs
import com.taotao.music.ui.common.moved
import com.taotao.music.ui.common.movedIndex
import com.taotao.music.ui.common.playbackIdentity
import com.taotao.music.ui.common.playbackSongId
import com.taotao.music.ui.common.playbackSource
import com.taotao.music.ui.common.playbackStableKey
import com.taotao.music.ui.common.sameSongIdentity
import com.taotao.music.ui.common.shareSongLink
import com.taotao.music.ui.library.MineLibrarySection
import com.taotao.music.ui.player.QualityChoice
import com.taotao.music.ui.player.QualitySheetKind
import com.taotao.music.ui.auth.readableMessage
import com.taotao.music.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 应用全局状态容器。
 *
 * 主界面此前是一个两千行的 Composable：所有 `remember { mutableStateOf }` 与操作函数
 * 都散落在函数体内。这里把「状态 + 操作」整体收拢成一个普通类，状态改用类属性持有；
 * Composable 侧只剩组合层职责（副作用、导航骨架与页面装配）。
 *
 * 实例由 [rememberTaotaoAppState] 创建并被 remember 持有，生命周期与原局部状态一致。
 */
internal class TaotaoAppState(private val context: Context, internal val scope: CoroutineScope) {
    // ---- 平台依赖：与原主入口的 remember { ... } 一一对应 ----
    val authSession = AuthSession(context)
    val audioPlayer = AudioPlayer(context)
    /**
     * 传输加密封装（`plans/009`）：设备号取硬件 ANDROID_ID，内嵌 PSK 在 `.so` 里。
     * 灰度总开关默认关闭，接线就绪后改 `cryptoEnabled = true` 即可启用收藏列表加密。
     */
    private val cryptoTransport = CryptoTransport(
        endpoint = TencentMusicApi.ENDPOINT,
        deviceIdProvider = { HardwareDeviceId(context.contentResolver).deviceId() },
        tokenProvider = { authSession.validToken() },
    )
    val musicApi = TencentMusicApi(
        authSession,
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode,
        cryptoTransport = cryptoTransport,
        // 灰度打开传输加密：目前只对白名单接口（收藏列表 /api/v1/favorites）生效，
        // 其余接口仍明文。服务端未配 PSK / 未加载 .node 时 CryptoTransport.enabled=false，
        // 客户端自动回退明文，不影响功能。
        cryptoEnabled = true,
    )
    val downloadManager = OfflineDownloadManager(context, authSession)
    val playbackStateStore = PlaybackStateStore(context)
    val playbackHistoryStore = PlaybackHistoryStore(context)
    val playbackSyncStore = PlaybackSyncStore(context)
    val searchHistoryStore = SearchHistoryStore(context)
    val favoritesStore = FavoritesStore(context)
    val qualityStore = QualityStore(context)
    val downloadNotifier = DownloadNotifier(context)
    val playbackDeviceId = DeviceIdStore(context).deviceId()
    val wukongImClient = WukongImClient(
        context = context,
        api = musicApi,
        sessionStore = ImSessionStore(context),
        deviceId = playbackDeviceId,
    )
    val imPeerStore = ImPeerStore(context)
    val playbackSync = PlaybackSyncCoordinator(
        store = playbackSyncStore,
        api = musicApi,
        deviceId = playbackDeviceId,
        accountIdProvider = { authSession.accountId },
    )
    val appearanceStore = AppearanceStore(context)
    val sleepTimerStore = SleepTimerStore(context)
    val updateManager = UpdateManager(
        context,
        authSession,
        initialPatchVersion = (context.applicationContext as? TaotaoApplication)?.activePatchVersion ?: 0,
    )

    val search = SearchState(
        scope = scope,
        musicApi = musicApi,
        qualityStore = qualityStore,
        historyStore = searchHistoryStore,
        favoritesStore = favoritesStore,
        onMessage = { message = it },
        onFavoritesTouched = { favoriteRevision++ },
    )
    val playlist = PlaylistState(
        scope = scope,
        musicApi = musicApi,
        accountIdProvider = { authSession.accountId },
        signedInProvider = { signedIn },
        onOpenSection = { mineLibrarySection = it },
        onMessage = { message = it },
    )
    val artistPage = ArtistPageState(
        scope = scope,
        musicApi = musicApi,
        qualityStore = qualityStore,
        onMessage = { message = it },
    )
    val albumPage = AlbumPageState(
        scope = scope,
        musicApi = musicApi,
        qualityStore = qualityStore,
        onMessage = { message = it },
    )
    val onlinePlaylistPage = OnlinePlaylistPageState(
        scope = scope,
        musicApi = musicApi,
        qualityStore = qualityStore,
        onMessage = { message = it },
    )

    // ---- 外观与定时关闭 ----
    var appearance by mutableStateOf(appearanceStore.mode())
    // 「上次定时」时长与「播完整首再停」勾选跨进程记忆；勾选变化要即时下发给服务。
    var sleepTimerLastMinutes by mutableIntStateOf(sleepTimerStore.lastMinutes())
    var sleepTimerWaitForSongEnd by mutableStateOf(sleepTimerStore.waitForSongEnd())

    // ---- 播放与导航 ----
    var playbackSongs by mutableStateOf(emptyList<Song>())
    var selectedIndex by mutableIntStateOf(0)
    var showPlayerDetail by mutableStateOf(false)
    // MV 播放页盖在详情页之上；非空即显示。只从详情页的 MV 按钮进入。
    var mvSong by mutableStateOf<Song?>(null)
    var showSearchPage by mutableStateOf(false)
    var showSettingsPage by mutableStateOf(false)
    var showSleepTimerDialog by mutableStateOf(false)
    var showProfilePage by mutableStateOf(false)
    var bottomTab by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)

    // ---- 账号与 IM ----
    var signedIn by mutableStateOf(authSession.isSignedIn)
    var imPeers by mutableStateOf(imPeerStore.read(authSession.accountId))
    /** 资料只在内存中保留；退出登录立即清空邮箱与昵称。 */
    var userProfile by mutableStateOf<TencentMusicApi.UserProfile?>(null)
    var profileLoading by mutableStateOf(false)
    var announcements by mutableStateOf(emptyList<TencentMusicApi.Announcement>())
    var showAnnouncementDialog by mutableStateOf(false)
    var imageTask by mutableStateOf<TencentMusicApi.ImageTask?>(null)
    var imageGenerating by mutableStateOf(false)

    // ---- 播放历史与统计 ----
    var restoredPlayback by mutableStateOf<SavedPlaybackState?>(null)
    var playbackHistory by mutableStateOf(emptyList<PlaybackHistoryEntry>())
    var playbackSession by mutableStateOf<PendingPlaybackSnapshot?>(null)
    var playbackHistoryClearEpoch by mutableIntStateOf(0)
    var pendingResumePositionMs by mutableIntStateOf(0)

    // ---- 收藏 ----
    /** 收藏缓存被改动后自增，让读了它的界面重新组合 —— SharedPreferences 本身不是可观察的。 */
    var favoriteRevision by mutableIntStateOf(0)
    // 收藏页先同步读取本地展示缓存，页面打开时无需等待云端请求与加载动画。
    var favoriteLibrarySongs by mutableStateOf(favoritesStore.cachedSongs())
    var favoriteLibrarySyncing by mutableStateOf(false)
    var favoriteLibraryError by mutableStateOf<String?>(null)

    // ---- 单曲倒带日记 ----
    var diarySong by mutableStateOf<Song?>(null)
    var songDiary by mutableStateOf<TencentMusicApi.SongDiary?>(null)
    var diaryLoading by mutableStateOf(false)
    var diaryError by mutableStateOf<String?>(null)
    var diaryGeneration by mutableIntStateOf(0)
    // 日记的「播放记录」下级页：只有在日记已打开时才有意义，返回键要先退它再退日记。
    var showDiaryRecords by mutableStateOf(false)

    // ---- 下载与音质 ----
    var downloadedSongs by mutableStateOf(emptyList<Song>())
    var playbackQuality by mutableStateOf(qualityStore.playbackQuality())
    var downloadQuality by mutableStateOf(qualityStore.downloadQuality())
    /** 待删除确认的已下载歌曲。删除是不可逆的，不做二次确认容易误触。 */
    var pendingDelete by mutableStateOf<Song?>(null)
    /** 待下载的歌与它在队列里的位置。非空即弹出音质面板。 */
    var downloadTarget by mutableStateOf<Pair<Song, Int>?>(null)
    /** 音质面板的用途。 */
    var qualitySheet by mutableStateOf<QualitySheetKind?>(null)
    var songQualities by mutableStateOf<List<QualityChoice>>(emptyList())
    var qualitiesLoading by mutableStateOf(false)

    // ---- 派生状态 ----

    /** 播放状态由播放器事件驱动，不再轮询播放服务。 */
    val isPlaying: Boolean get() = audioPlayer.isPlaying

    /** 已下载歌曲的 ID 集合，用于在搜索结果里标出「已下载」。 */
    val downloadedIds: Set<Long> get() = downloadedSongs.mapNotNull { it.remoteId }.toSet()

    /** 歌单详情的添加候选：合并用户当前已经看过或播放过的歌曲，避免额外复制一套搜索状态。 */
    val playlistCandidates: List<Song> get() = dedupeSongs(
        (search.results + playbackSongs + favoriteLibrarySongs + playbackHistory.map { it.song } + downloadedSongs)
            .filter { TencentMusicApi.playlistSongId(it) != null },
    )

    // ---- 播放 ----

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
        if ((remoteId?.let { it > 0L } != true && song.mid.isNullOrBlank()) || song.audioUri?.startsWith("file:") == true) {
            message = "本地歌曲的音质由文件本身决定"
            return
        }
        val position = audioPlayer.currentPositionMs()
        val updated = song.copy(
            audioUri = TencentMusicApi.placeholderUri(remoteId, song.mid, song.type, quality, song.source)
                ?: song.audioUri,
        )
        val queue = playbackSongs.toMutableList().also { it[index] = updated }
        playbackSongs = queue
        // 仅切换音质并从原进度继续，不能被统计成一轮新的完整重播。
        audioPlayer.play(updated, queue, index, position, newPlaybackCycle = false)
        scope.launch { withContext(Dispatchers.IO) { playbackStateStore.save(queue, index, position) } }
    }

    fun playSong(queue: List<Song>, index: Int, positionMs: Int = 0) {
        if (index !in queue.indices) return
        scope.launch {
            // 云端歌单只保存跨设备稳定身份，不保存本机 file: 地址。整条队列必须在入队前
            // 一次性绑定本地下载文件，不能只处理用户点击的第一首，否则下一首会错误走网络。
            val localDownloads = withContext(Dispatchers.IO) { downloadManager.listDownloaded() }
            val queueWithLocalFiles = bindDownloadedSongs(queue, localDownloads)
            val requestedSong = queueWithLocalFiles[index]
            // 历史队列/旧版本缓存可能没有高潮区间；播放入队时补齐并回写队列，
            // 避免详情页临时拉到数据后，迷你播放器和下一次恢复又丢失标记。
            // 老队列/收藏恢复的歌曲同样没有音源内歌手 / 专辑 ID，一并回填 ——
            // 详情页「查看歌手 / 查看专辑」才能对非搜索来源生效（2026-10-05 用户反馈）。
            val queueWithMetadata = if (
                !requestedSong.audioUri.orEmpty().startsWith("file:") &&
                requestedSong.remoteId?.let { it > 0L } == true &&
                (requestedSong.refrainStartMs == null || requestedSong.refrainEndMs == null ||
                    (requestedSong.source == TencentMusicApi.SEARCH_SOURCE_KUWO && requestedSong.artistId == null))
            ) {
                val refreshed = withContext(Dispatchers.IO) {
                    runCatching { musicApi.requestSongInfoForPlayback(requestedSong, qualityStore.playbackQuality().value) }
                        .getOrNull()
                }
                if (refreshed != null && (refreshed.refrainStartMs != null || refreshed.refrainEndMs != null ||
                            refreshed.artistId != null || refreshed.albumId != null)) {
                    queueWithLocalFiles.toMutableList().also { items ->
                        items[index] = requestedSong.copy(
                            // elvis 保底合并：上游没给的字段保留原值，绝不把已有数据洗掉。
                            refrainStartMs = refreshed.refrainStartMs ?: requestedSong.refrainStartMs,
                            refrainEndMs = refreshed.refrainEndMs ?: requestedSong.refrainEndMs,
                            artistId = refreshed.artistId ?: requestedSong.artistId,
                            albumId = refreshed.albumId ?: requestedSong.albumId,
                        )
                    }
                } else queueWithLocalFiles
            } else queueWithLocalFiles
            val requestedSongWithMetadata = queueWithMetadata[index]
            val isLocalFile = requestedSongWithMetadata.audioUri?.startsWith("file:") == true
            // remoteId 是别的模块的 public 属性，Kotlin 不做智能转换，先取成局部变量。
            val remoteId = requestedSong.remoteId
            // 下载过就直接放本地文件。搜索结果里的歌与已下载的是同一个 remoteId，
            // 不查这一步的话，明明下载过还是从云端拉流 —— 白费流量，离线也放不了。
            // 网络歌曲统一用占位地址入队，真正的上游直链在取流那一刻才解析 ——
            // 直链是限时的，存进队列后冷启动恢复时就失效了。
            val playable = when {
                isLocalFile -> requestedSongWithMetadata
                !isLocalFile && (remoteId?.let { it > 0L } == true || !requestedSongWithMetadata.mid.isNullOrBlank()) -> requestedSongWithMetadata.copy(
                    audioUri = TencentMusicApi.placeholderUri(
                        remoteId,
                        requestedSongWithMetadata.mid,
                        requestedSongWithMetadata.type,
                        qualityStore.playbackQuality().value,
                        requestedSongWithMetadata.source,
                    ) ?: requestedSongWithMetadata.audioUri,
                    lyricUri = requestedSongWithMetadata.lyricUri ?: TencentMusicApi.lyricUri(remoteId, requestedSongWithMetadata.mid, requestedSongWithMetadata.source),
                )
                else -> requestedSongWithMetadata
            }
            if (playable.audioUri.isNullOrBlank()) {
                message = "歌曲暂时没有可用播放链接"
                return@launch
            }
            message = null
            // 一次点击会把来源列表稳定地变成整条播放队列。以前只给被点中的一首补地址，
            // AudioPlayer 发现其它歌曲没有地址后会把整队列退化为单曲，界面却还短暂显示整列，
            // 这正是播放列表规则看起来忽多忽少的根源。
            val prepared = queueWithLocalFiles.mapIndexedNotNull { originalIndex, candidate ->
                // 不可播的歌（酷我上拿不到播放地址的正版曲）不进队列：它们带着永不过期的
                // 占位地址，下面的 `isNullOrBlank` 判不出来，留着只会在播到那一首时失败。
                // 被点中的那首必然可播（列表行已经禁用了点击），这里保底兜一下。
                if (originalIndex != index && !candidate.playable) return@mapIndexedNotNull null
                val candidateId = candidate.remoteId
                val ready = when {
                    originalIndex == index -> playable
                    candidate.audioUri?.startsWith("file:") == true -> candidate
                    candidateId?.let { it > 0L } == true || !candidate.mid.isNullOrBlank() -> candidate.copy(
                        audioUri = TencentMusicApi.placeholderUri(
                            candidateId,
                            candidate.mid,
                            candidate.type,
                            qualityStore.playbackQuality().value,
                            candidate.source,
                        ) ?: candidate.audioUri,
                        lyricUri = candidate.lyricUri ?: TencentMusicApi.lyricUri(candidateId, candidate.mid, candidate.source),
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
     * 详情页补拉到高潮区间后回写队列并落盘，迷你播放器与下次冷启动恢复才不会丢标记。
     *
     * 按实例身份定位队列里的那首歌：定位不到（队列已被整条换掉）就直接放弃，
     * 不能按 remoteId 猜位置把区间写到另一首同 ID 的歌上。值没变化时也不重复落盘。
     */
    fun applyResolvedRefrain(song: Song, startMs: Long?, endMs: Long?) {
        val index = playbackSongs.indexOfFirst { it === song }
        if (index < 0) return
        val current = playbackSongs[index]
        if (current.refrainStartMs == startMs && current.refrainEndMs == endMs) return
        playbackSongs = playbackSongs.toMutableList().also { items ->
            items[index] = current.copy(refrainStartMs = startMs, refrainEndMs = endMs)
        }
        // 先在主线程取快照与进度，再交给 IO 线程写盘，与 togglePlayback 的落盘节奏一致。
        val queueSnapshot = playbackSongs
        val indexSnapshot = index
        val positionSnapshot = audioPlayer.currentPositionMs()
        scope.launch(Dispatchers.IO) { playbackStateStore.save(queueSnapshot, indexSnapshot, positionSnapshot) }
    }

    /**
     * 手动上一首/下一首必须按目标歌曲重新发起完整播放，不能只移动 Media3 的队列游标。
     * 歌单歌曲还需要在 [playSong] 中重新绑定本机下载文件；只 seek 会绕过这一步。
     */
    fun playAdjacentSong(offset: Int) {
        val queue = playbackSongs
        if (queue.isEmpty() || offset == 0) return
        val current = selectedIndex.coerceIn(queue.indices)
        val requested = current + offset
        val target = when {
            requested in queue.indices -> requested
            audioPlayer.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ALL && offset > 0 -> 0
            audioPlayer.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ALL -> queue.lastIndex
            else -> return
        }
        playSong(queue, target)
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
                !isLocalFile && (remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank()) -> song.copy(
                    audioUri = TencentMusicApi.placeholderUri(
                        remoteId,
                        song.mid,
                        song.type,
                        qualityStore.playbackQuality().value,
                        song.source,
                    ) ?: song.audioUri,
                    lyricUri = song.lyricUri ?: TencentMusicApi.lyricUri(remoteId, song.mid, song.source),
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
            // 先在主线程取进度，再交给 IO 线程写盘。
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

    /** 播放队列拖动排序：先更新界面，再交给播放器同步 Media3 队列。 */
    fun moveQueueItem(from: Int, to: Int) {
        if (from in playbackSongs.indices && to in playbackSongs.indices && from != to) {
            playbackSongs = playbackSongs.moved(from, to)
            selectedIndex = movedIndex(selectedIndex, from, to)
            audioPlayer.moveQueueItem(from, to)
        }
    }

    // ---- 下载 ----

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

    // ---- 收藏与分享 ----

    /**
     * 切换收藏。
     *
     * 先改本地缓存让心形立刻响应，再发请求；失败就回滚。以前是「等服务端返回再改 UI」，
     * 弱网下点一下要等半秒才有反应。
     */
    fun toggleFavorite(song: Song) {
        if (song.remoteId?.let { it > 0L } != true && song.mid.isNullOrBlank()) return
        val account = authSession.accountId
        val target = !favoritesStore.contains(song)
        val previousLibrary = favoriteLibrarySongs
        favoritesStore.set(song, target)
        favoriteLibrarySongs = if (target) {
            listOf(song.copy(favorited = true)) + favoriteLibrarySongs.filterNot { favoritesStore.contains(it) && it.sameSongIdentity(song) }
        } else {
            favoriteLibrarySongs.filterNot { it.sameSongIdentity(song) }
        }
        favoriteRevision++
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { musicApi.setFavorite(song, target) } }
                .onFailure {
                    // 请求跨过退出/换号边界时，本地缓存已经被清空，不要把旧账号的回滚写回来。
                    if (authSession.accountId != account || !signedIn) return@onFailure
                    favoritesStore.set(song, !target)
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
        val account = authSession.accountId ?: return
        if (favoriteLibrarySyncing) return
        favoriteLibrarySyncing = true
        favoriteLibraryError = null
        val knownSongs = favoriteLibrarySongs + playbackSongs + search.results +
            playbackHistory.map { it.song } + downloadedSongs
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { musicApi.favoriteLibrary(knownSongs, playbackQuality.value) }
            }.onSuccess { library ->
                if (authSession.accountId != account || !signedIn) return@onSuccess
                favoritesStore.replaceLibrary(library.ids, library.songs)
                favoriteLibrarySongs = library.songs
                favoriteRevision++
            }.onFailure {
                if (authSession.accountId == account && signedIn) {
                    favoriteLibraryError = it.message ?: "收藏列表加载失败"
                }
            }
            if (authSession.accountId == account && signedIn) favoriteLibrarySyncing = false
        }
    }

    /** 分享动作只传短链，不暴露音频直链；短链生成失败时仍留在当前页面。 */
    fun requestSongShare(song: Song) {
        if (!signedIn) {
            message = "登录后才能分享歌曲"
            return
        }
        if (song.remoteId?.let { it > 0L } != true && song.mid.isNullOrBlank()) {
            message = "本地歌曲缺少可分享的远端身份"
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { musicApi.createSongShare(song) } }
                .onSuccess { share -> shareSongLink(context, song, share.url) }
                .onFailure { message = it.message ?: "分享链接生成失败" }
        }
    }

    /** 清理账号级内存缓存；收藏关系不是设备级数据，退出或会话失效后不得留在界面。 */
    fun clearAccountScopedLibraryState() {
        favoritesStore.clear()
        favoriteLibrarySongs = emptyList()
        favoriteLibrarySyncing = false
        favoriteLibraryError = null
        favoriteRevision += 1
        userProfile = null
        profileLoading = false
    }

    // ---- 单曲倒带日记 ----

    /** 打开某首歌的单曲倒带日记：先切到日记页并显示加载态，再从后端拉取画像。 */
    fun openSongDiary(song: Song) {
        if (!signedIn) {
            message = "登录后才能查看单曲日记"
            return
        }
        if (TencentMusicApi.playlistSongId(song) == null) {
            message = "这首歌暂时无法生成日记"
            return
        }
        diarySong = song
        songDiary = null
        diaryError = null
        diaryLoading = true
        showDiaryRecords = false
        val generation = ++diaryGeneration
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { musicApi.songDiary(song) } }
            if (generation != diaryGeneration) return@launch
            result
                .onSuccess { songDiary = it }
                .onFailure { diaryError = it.message ?: "日记加载失败" }
            diaryLoading = false
        }
    }

    // ---- 播放统计与最近播放 ----

    /**
     * 先同步清空命令，再同步各会话最新快照。任一步失败都保留在本地 outbox，下一次启动、
     * 进入最近播放或新的播放快照到来时重试；网络失败绝不吞掉用户动作。
     */
    suspend fun syncPendingPlayback() {
        playbackSync.syncPending()
    }

    /** 最近播放沿用收藏夹的本地优先同步：网络不可用或服务端未部署时不动本地列表。 */
    fun refreshPlaybackHistory() {
        val historyAccountId = authSession.accountId ?: return
        val knownSongs = playbackHistory.map { it.song } + playbackSongs + search.results +
            favoriteLibrarySongs + downloadedSongs
        scope.launch {
            // 补传失败不妨碍拉取远端权威快照，否则跨设备清空会被离线会话阻塞。
            runCatching { syncPendingPlayback() }
            runCatching {
                withContext(Dispatchers.IO) { musicApi.recentPlaybackLibrary(knownSongs, playbackQuality.value) }
            }.onSuccess { remoteHistory ->
                // 网络请求期间可能发生退出/换号；不能把 A 的远端结果写进 B 的界面或缓存桶。
                if (authSession.accountId != historyAccountId) return@onSuccess
                // recent/state 的 revision 是跨设备清空的唯一权威水位，不拿本机时间戳猜测。
                playbackSyncStore.acceptServerHistoryRevision(historyAccountId, remoteHistory.revision)
                val localHistoryRevision = playbackSync.historyRevision()
                // 本机清空还未送达服务端时，远端的旧 revision 不能把刚清空的 UI 又复活；
                // 同样不再用设备时间与 clearedBefore 比较，跨设备冲突完全由 revision 决定。
                val remoteEntries = if (remoteHistory.revision < localHistoryRevision) emptyList() else remoteHistory.entries
                // 空数组同样是权威结果；只有 generation 未过期的待上传本地会话才应保留在 UI 中。
                val pendingBySongId = playbackSync.pendingSnapshots()
                    .groupBy { "${it.source}:${it.songId}" }
                    .mapValues { (_, snapshots) -> snapshots.maxByOrNull { it.lastPlayedAt } }
                val localUnsynced = playbackHistory.filter { entry ->
                    playbackStableKey(entry.song)
                        ?.let(pendingBySongId::get)
                        ?.let { snapshot -> snapshot.historyRevision >= remoteHistory.revision } == true
                }
                val merged = (remoteEntries + localUnsynced)
                    .sortedByDescending { it.playedAtMillis }
                    .distinctBy { entry -> playbackStableKey(entry.song) ?: entry.song.audioUri }
                    .take(500)
                playbackHistory = withContext(Dispatchers.IO) {
                    playbackHistoryStore.replace(historyAccountId, merged)
                }
            }
        }
    }

    /** 「最近播放」页的清空动作：先落清空标记，再清本地 UI 与存储，最后尽快同步。 */
    fun clearPlaybackHistory() {
        playbackSync.markClearPending()
        playbackSession = null
        playbackHistoryClearEpoch += 1
        playbackHistoryStore.clear(authSession.accountId)
        playbackHistory = emptyList()
        scope.launch { runCatching { syncPendingPlayback() } }
    }

    /**
     * 用单调时钟累计一次稳定播放会话。暂停/恢复复用同一 sessionId；每 15 秒、暂停、切歌、
     * 完成与离开组合时把最新快照写进 outbox，因此弱网和进程被杀都不会丢失统计。
     *
     * 由副作用层在播放曲目、播放态或清空代际变化时调用；方法内部读取的都是实时值。
     */
    suspend fun trackPlaybackSession() {
        val played = audioPlayer.queue.getOrNull(audioPlayer.currentIndex) ?: return
        // 清空最近播放会改变这个 epoch。被取消的旧 effect 即使进入 finally，也不能把已清空的
        // 会话重新写回 outbox 或本地 UI。
        val sessionClearEpoch = playbackHistoryClearEpoch
        // 点击新歌时 Compose 先更新播放队列，再等 MediaController 切换媒体项；这段极短窗口
        // 仍会读到上一首。跳过它，避免把“点下一首”误建成上一首的一轮重播。
        if (playbackSongs.getOrNull(selectedIndex)?.let(::playbackIdentity) != playbackIdentity(played)) return
        val remoteSongId = playbackSongId(played)
        val remoteSource = playbackSource(played)
        val historyAccountId = authSession.accountId
        // 本地歌曲同样听满三秒才进入最近播放，和云端歌曲保持同一准入规则。
        if (remoteSongId == null) {
            if (!audioPlayer.isPlaying || historyAccountId == null) return
            delay(3_000)
            if (
                audioPlayer.isPlaying &&
                authSession.accountId == historyAccountId &&
                PlaybackSnapshotPolicy.shouldPersistAfterClear(sessionClearEpoch, playbackHistoryClearEpoch) &&
                audioPlayer.queue.getOrNull(audioPlayer.currentIndex) == played
            ) {
                playbackHistory = withContext(Dispatchers.IO) {
                    playbackHistoryStore.record(historyAccountId, played)
                }
            }
            return
        }
        val playbackCycle = audioPlayer.playbackCycle
        // 远端会话必须在创建时绑定账号。拿不到已认证账号 ID 时宁可不创建统计会话，也不能
        // 把匿名/上一账号的内存数据在下一次登录时上传给错误用户。
        val sessionAccountId = historyAccountId ?: return
        var session = playbackSession?.takeIf {
            PlaybackSnapshotPolicy.shouldReuseSession(it, sessionAccountId, remoteSource, remoteSongId, playbackCycle)
        } ?: run {
            if (!audioPlayer.isPlaying) return
            // 新会话优先刷新服务端清空代际；请求慢或离线时 1.5 秒后使用本地已知值继续，
            // 不让统计同步阻塞真正的音频播放。服务端上报响应仍会再次校准 revision。
            val historyRevision = withTimeoutOrNull(1_500) {
                runCatching { playbackSync.refreshHistoryRevision() }
                    .getOrElse { playbackSync.historyRevision() }
            } ?: playbackSync.historyRevision()
            // 发起 revision 拉取期间可能已经退出或切换账号，禁止把旧请求的结果带入新账号会话。
            if (authSession.accountId != sessionAccountId) return
            PendingPlaybackSnapshot(
                sessionId = UUID.randomUUID().toString(),
                accountId = sessionAccountId,
                source = remoteSource,
                songId = remoteSongId,
                startedAt = System.currentTimeMillis(),
                lastPlayedAt = System.currentTimeMillis(),
                listenedMs = 0,
                durationSeconds = audioPlayer.durationMs.takeIf { it > 0 }?.div(1_000),
                completed = false,
                historyRevision = historyRevision,
                clearMarker = playbackSyncStore.pendingClearMarker(sessionAccountId),
                playbackCycle = playbackCycle,
            )
        }
        if (!PlaybackSnapshotPolicy.shouldPersistAfterClear(sessionClearEpoch, playbackHistoryClearEpoch)) {
            return
        }
        playbackSession = session
        if (!audioPlayer.isPlaying) {
            if (
                session.listenedMs > 0 &&
                PlaybackSnapshotPolicy.shouldPersistAfterClear(sessionClearEpoch, playbackHistoryClearEpoch)
            ) {
                session = playbackSync.persistTerminalSnapshot(session, completed = false)
                if (playbackSession?.sessionId == session.sessionId) playbackSession = session
                runCatching { syncPendingPlayback() }
            }
            return
        }
        var lastElapsed = SystemClock.elapsedRealtime()
        try {
            while (
                audioPlayer.isPlaying &&
                audioPlayer.queue.getOrNull(audioPlayer.currentIndex)?.let(::playbackIdentity) == playbackIdentity(played) &&
                audioPlayer.playbackCycle == playbackCycle &&
                PlaybackSnapshotPolicy.shouldPersistAfterClear(sessionClearEpoch, playbackHistoryClearEpoch)
            ) {
                delay(1_000)
                if (!PlaybackSnapshotPolicy.shouldPersistAfterClear(sessionClearEpoch, playbackHistoryClearEpoch)) {
                    return
                }
                val nowElapsed = SystemClock.elapsedRealtime()
                val increment = (nowElapsed - lastElapsed).coerceIn(0L, 2_000L)
                lastElapsed = nowElapsed
                val reachedHistoryThreshold = session.listenedMs < 3_000 && session.listenedMs + increment >= 3_000
                val completed = audioPlayer.durationMs > 0 && audioPlayer.positionMs >= audioPlayer.durationMs - 500
                session = session.copy(
                    listenedMs = session.listenedMs + increment,
                    lastPlayedAt = System.currentTimeMillis(),
                    durationSeconds = audioPlayer.durationMs.takeIf { it > 0 }?.div(1_000) ?: session.durationSeconds,
                    completed = session.completed || completed,
                )
                if (playbackSession?.sessionId == session.sessionId) playbackSession = session
                if (
                    reachedHistoryThreshold &&
                    authSession.accountId == sessionAccountId &&
                    PlaybackSnapshotPolicy.shouldPersistAfterClear(sessionClearEpoch, playbackHistoryClearEpoch)
                ) {
                    playbackHistory = withContext(Dispatchers.IO) {
                        playbackHistoryStore.record(sessionAccountId, played)
                    }
                }
                val reachedPeriodicSync = session.listenedMs >= 15_000 && session.listenedMs % 15_000 < increment
                if (reachedHistoryThreshold || reachedPeriodicSync) {
                    session = playbackSync.persistTerminalSnapshot(session, completed = false)
                    if (playbackSession?.sessionId == session.sessionId) playbackSession = session
                    runCatching { syncPendingPlayback() }
                }
            }
        } finally {
            if (
                session.listenedMs > 0 &&
                PlaybackSnapshotPolicy.shouldPersistAfterClear(sessionClearEpoch, playbackHistoryClearEpoch)
            ) {
                session = playbackSync.persistTerminalSnapshot(session, completed = session.completed)
                // 新一轮已经启动时，旧协程 finally 绝不能把内存中的新会话覆盖回去。
                if (playbackSession?.sessionId == session.sessionId) playbackSession = session
                runCatching { syncPendingPlayback() }
            }
        }
    }

    // ---- AI 生图 ----

    /** 发起图片生成并轮询任务状态直到出图或失败。 */
    fun generateImage(model: String, prompt: String, ratio: String, imageSize: String, quality: String) {
        if (prompt.isBlank()) return
        imageGenerating = true
        imageTask = null
        scope.launch {
            try {
                val created = withContext(Dispatchers.IO) { musicApi.createImageTask(model, prompt, ratio, imageSize, quality) }
                imageTask = created
                while (created.taskId.isNotBlank() && imageTask?.state == "IN_PROGRESS") {
                    delay(3_000)
                    val latest = withContext(Dispatchers.IO) { musicApi.requestImageTask(created.taskId) }
                    imageTask = latest
                }
            } catch (error: Throwable) {
                message = error.message ?: "图片生成失败"
            }
            imageGenerating = false
        }
    }

    // ---- 账号会话与导航 ----

    /** 登录成功到账号分桶缓存异步读取之间不能保留前一账号的界面数据。 */
    fun onSignedIn(tokens: TencentMusicApi.TokenPair) {
        playbackHistory = emptyList()
        restoredPlayback = null
        clearAccountScopedLibraryState()
        authSession.save(tokens)
        playlist.clearState()
        signedIn = true
    }

    /** 会话彻底失效（刷新令牌也被拒绝）时停止播放并回到登录页。 */
    fun handleSessionExpired() {
        scope.launch {
            audioPlayer.cancelSleepTimer()
            audioPlayer.stop()
            showSleepTimerDialog = false
            clearAccountScopedLibraryState()
            playlist.clearState()
            playbackSession = null
            signedIn = false
        }
    }

    /** 「我的」页退出登录：先停播放与定时器，再清账号级缓存并撤销令牌。 */
    fun signOut() {
        audioPlayer.cancelSleepTimer()
        audioPlayer.stop()
        showSleepTimerDialog = false
        playbackStateStore.clear()
        playbackHistoryStore.clear(authSession.accountId)
        playbackHistory = emptyList()
        // 收藏是账号状态，换账号不能沿用上一个人的。
        clearAccountScopedLibraryState()
        playlist.clearState()
        // 撤销刷新令牌需要访问网络，放到 IO 线程；本地会话已在 signOut 内同步清空。
        scope.launch(Dispatchers.IO) { authSession.signOut() }
        signedIn = false
    }

    /** 切换底部标签时把所有叠放页面的显示状态复位，避免「点了标签却还停在原页面」。 */
    fun switchTab(target: Int) {
        // 离开聊天页立即取消活动会话，避免切页期间仍把新消息标成已读并发送回执。
        if (target != 2) wukongImClient.setActivePeer(null)
        bottomTab = target
        showPlayerDetail = false
        showSearchPage = false
        showSettingsPage = false
        showProfilePage = false
        mineLibrarySection = null
        playlist.closeAll()
        diarySong = null
        showDiaryRecords = false
        // 歌手 / 专辑 / 在线歌单主页同属叠放下级页，换标签时一并收起，否则路由会被它们抢走。
        artistPage.close()
        albumPage.close()
        onlinePlaylistPage.close()
    }

    fun openSearchPage() {
        search.openPage()
        showSearchPage = true
    }

    /**
     * 打开歌手主页。歌手页、专辑页与在线歌单页是平级下级页（都从搜索域进入），互跳时收起对方：
     * 同一时刻只保留一层下级页，返回键永远「先清当前页、回到搜索页」，
     * 不需要维护返回栈 —— 这是与 playlist.selected 同一套扁平导航约定。
     */
    fun openArtistPage(target: ArtistSearchResult) {
        albumPage.close()
        onlinePlaylistPage.close()
        artistPage.open(target)
    }

    /** 打开专辑页；同 [openArtistPage]，先收起歌手页与在线歌单页。 */
    fun openAlbumPage(target: AlbumSearchResult) {
        artistPage.close()
        onlinePlaylistPage.close()
        albumPage.open(target)
    }

    /**
     * 打开在线歌单页：从搜索「歌单」标签的条目点击进入，搜索行本身就是寻址钥匙
     * （id + sourceMarker 原样记录）。同 [openArtistPage]，先收起歌手 / 专辑页，
     * 一次只保留一层下级页。
     */
    fun openOnlinePlaylistPage(result: PlaylistSearchResult) {
        artistPage.close()
        albumPage.close()
        onlinePlaylistPage.open(result)
    }

    /** 关闭在线歌单页；返回键与页面顶栏共用这一个入口。 */
    fun closeOnlinePlaylistPage() {
        onlinePlaylistPage.close()
    }

    /**
     * 从歌曲直接跳歌手主页：播放详情页头部的歌手名与搜索歌曲行菜单「查看歌手」共用。
     * 歌曲没带音源内歌手 ID（目前只有酷我搜索结果下发）时忽略，不跳转。
     * pic 传 null —— 歌手头像不等于歌曲封面，详情到达前的头部先用歌手名兜底，
     * 头像由歌手页自己拉资料。
     */
    fun openArtistPageFromSong(song: Song) {
        // 播放详情页在页面分发 when 链里排第一：不先收起它，歌手页即使开了也
        // 永远显示不出来（表现为「点了没反应」，2026-10-05 实测踩过）。
        // 详情页关闭后播放由迷你播放器继续，与桌面端行为一致。
        showPlayerDetail = false
        song.artistId?.let { artistId ->
            openArtistPage(
                ArtistSearchResult(
                    source = song.source,
                    id = artistId,
                    name = song.artist,
                    pic = null,
                    songCount = 0,
                    albumCount = 0,
                ),
            )
        }
    }

    /**
     * 从歌曲直接跳专辑主页：播放详情页头部的专辑名与搜索歌曲行菜单「查看专辑」共用。
     * 专辑封面就是歌曲封面，详情到达前可直接显示；歌曲行没有的 songCount / showtime
     * 交给专辑页详情补全。artistId 为 null 时传 0，专辑页对 0 会隐藏「歌手名」跳转入口，
     * 不会带出无效跳转。
     */
    fun openAlbumPageFromSong(song: Song) {
        // 同 [openArtistPageFromSong]：详情页不分发出去就等于「点了没反应」。
        showPlayerDetail = false
        song.albumId?.let { albumId ->
            openAlbumPage(
                AlbumSearchResult(
                    source = song.source,
                    id = albumId,
                    name = song.album,
                    pic = song.coverUri,
                    artist = song.artist,
                    artistId = song.artistId ?: 0L,
                    songCount = 0,
                    showtime = "",
                ),
            )
        }
    }

    fun applyAppearance(mode: AppearanceMode) {
        appearance = mode
        appearanceStore.setMode(mode)
    }

    /** 设置页点资料卡：有缓存直接进，否则拉一次资料再进。 */
    fun openProfilePage() {
        if (userProfile != null) {
            showProfilePage = true
        } else if (!profileLoading) {
            profileLoading = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { musicApi.profile() } }
                    .onSuccess { userProfile = it; showProfilePage = true }
                    .onFailure { message = it.readableMessage() }
                profileLoading = false
            }
        }
    }

    /** 公告免鉴权，启动时安静读取；网络异常不应阻断听歌或登录。 */
    fun loadAnnouncements() {
        scope.launch {
            announcements = runCatching { withContext(Dispatchers.IO) { musicApi.announcements() } }.getOrDefault(emptyList())
        }
    }

    /** 换号后把聊天对端恢复成新账号的本地缓存；悟空回执同步会随后合并进来。 */
    fun reloadImPeers() {
        imPeers = imPeerStore.read(authSession.accountId)
    }

    /** 离线会话同步完成后，把悟空返回的对端 UUID 写入本机入口；消息正文仍只由悟空 SDK 保存。 */
    fun rememberImPeer(peerUid: String) {
        imPeers = imPeerStore.remember(authSession.accountId, peerUid)
    }

    // ---- 导航子状态 ----

    // “我的”库当前打开的分区；null 表示在“我的”主页。
    var mineLibrarySection by mutableStateOf<MineLibrarySection?>(null)
}

/** 创建并记住全局状态容器；组合销毁重建时由 remember 保持实例稳定。 */
@Composable
internal fun rememberTaotaoAppState(): TaotaoAppState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { TaotaoAppState(context, scope) }
}
