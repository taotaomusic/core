package com.taotao.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.taotao.music.model.AudioQuality
import com.taotao.music.model.Lyric
import com.taotao.music.model.LyricParser
import com.taotao.music.model.Song
import com.taotao.music.model.labelOfQuality
import com.taotao.music.playerui.PlayerCoral
import com.taotao.music.playerui.TaotaoPlayerTheme
import com.taotao.music.playerui.theme.TaotaoElevation
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.TaotaoTypeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.Locale
import javax.imageio.ImageIO

internal val Coral = PlayerCoral
private const val HISTORY_ADMISSION_MS = 3_000L
private const val PLAYBACK_SYNC_INTERVAL_MS = 15_000L
private const val MAX_PLAYBACK_REVISION_RETRIES = 3
internal const val SLEEP_TIMER_MINUTE_MS = 60_000L
internal const val SLEEP_TIMER_MAX_MINUTES = 24 * 60
internal val SLEEP_TIMER_PRESET_MINUTES = listOf(5, 10, 15, 30, 60, 90)

/**
 * 品牌标识（珊瑚色方块 + 音符）的边长。
 *
 * 侧边栏与登录卡片上是同一个视觉元素，所以只定义一次；
 * 两者原先分别是 40 / 42，收敛到 40。
 */
internal val BrandMarkSize = 40.dp

/** 登录卡片的固定宽度。 */
private val LoginCardWidth = 430.dp

/** 登录 / 注册按钮高度。 */
private val LoginButtonHeight = 46.dp

private val desktopSelectableQualityValues = AudioQuality.entries.map(AudioQuality::value).toSet()

/** 将定时播放输入限制在一个可控范围，避免误输入造成超长后台任务。 */
internal fun sleepTimerDurationMs(minutes: Int): Long? =
    minutes.takeIf { it in 1..SLEEP_TIMER_MAX_MINUTES }?.toLong()?.times(SLEEP_TIMER_MINUTE_MS)

/** 只在实际播放时扣减定时额度；暂停、负耗时和超长时钟跳变都不会产生异常结果。 */
internal fun sleepTimerRemainingAfterTick(remainingMs: Long, elapsedMs: Long, isPlaying: Boolean): Long {
    if (remainingMs <= 0L || !isPlaying) return remainingMs.coerceAtLeast(0L)
    return (remainingMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
}

/** 倒计时显示使用向上取整，避免还剩不到一秒时提前显示 00:00。 */
internal fun formatSleepTimerRemaining(remainingMs: Long): String {
    val safeMs = remainingMs.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1_000L + if (safeMs % 1_000L == 0L) 0L else 1L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}

/** 服务端可能用 mid 保存收藏/历史，而补全资料后又返回正整数 ID，两种身份都要能关联同一首歌。 */
private fun remoteIdentityKeys(song: Song): Set<String> = buildSet {
    song.remoteId?.takeIf { it > 0L }?.let { add(DesktopMusicApi.key(song.source, it.toString())) }
    song.mid?.trim()?.takeIf(String::isNotBlank)?.let { add(DesktopMusicApi.key(song.source, it)) }
}

/** 服务端记录使用 mid 时继续以 mid 作为本地主键，避免补全出的正 ID 改变收藏/历史身份。 */
private fun Song.preferRemoteIdentity(identity: String): Song =
    if (mid?.trim() == identity && remoteId?.toString() != identity) copy(remoteId = null) else this

internal enum class DesktopPage { MUSIC, PLAYLISTS, FAVORITES, HISTORY, DOWNLOADS, SETTINGS }

internal data class LocalHistory(
    val song: Song,
    val playedAt: Long,
    val firstPlayedAt: Long = playedAt,
    val playCount: Int = 1,
    val completedCount: Int = 0,
    val totalListenedMs: Long = 0L,
    /** 与服务端清空代际绑定；清空前的会话即使稍后补传也不可重新显示。 */
    val historyRevision: Long = 0L,
)

internal data class DesktopDownloadProgress(
    val completedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let { (completedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

fun main() {
    // 只有新版本真正稳定运行满 10 秒才确认更新。线程设为 daemon，启动期崩溃时不会
    // 为了等待确认而托住 JVM，下一次 launcher 启动就会自动恢复 backup。
    Thread {
        Thread.sleep(10_000L)
        WindowsUpdateManager.confirmStableRun()
    }.apply {
        name = "desktop-update-confirmation"
        isDaemon = true
        start()
    }
    application {
    var windowVisible by remember { mutableStateOf(true) }
    var windowHiddenGeneration by remember { mutableLongStateOf(0L) }
    val traySupported = remember { java.awt.SystemTray.isSupported() }
    val windowIcon = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("taotao-music.png")
            ?.use(ImageIO::read)
            ?.toPainter()
    }
    Window(
        onCloseRequest = {
            if (traySupported) {
                windowVisible = false
                windowHiddenGeneration++
            } else {
                exitApplication()
            }
        },
        title = "桃桃音乐 · Windows",
        icon = windowIcon,
        resizable = true,
        visible = windowVisible,
    ) {
        DesktopMusicApp(
            onShowWindow = { windowVisible = true },
            windowHiddenGeneration = windowHiddenGeneration,
            onExit = ::exitApplication,
        )
    }
    }
}

private data class DesktopPlaybackSource(
    val uri: String,
    val localSong: Song? = null,
    val quality: Int? = null,
)

@Composable
private fun DesktopMusicApp(
    onShowWindow: () -> Unit,
    windowHiddenGeneration: Long,
    onExit: () -> Unit,
) {
    val storage = remember { DesktopStorage() }
    val session = remember { DesktopSession() }
    val accountId = session.accountId
    remember(accountId) { storage.prepareAccount(accountId) }
    val api = remember { DesktopMusicApi(session) }
    val initialVolume = remember { storage.getSetting("volume", "1.0").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f }
    val player = remember { DesktopPlayer().apply { volume = initialVolume } }
    val downloads = remember { storage.downloads() }
    val playbackOutbox = remember { DesktopPlaybackOutbox(storage.root) }
    val playbackSyncMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val deviceId = remember {
        storage.getSetting("deviceId", "").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
            storage.setSetting("deviceId", it)
        }
    }

    var signedIn by remember { mutableStateOf(session.isSignedIn) }
    var darkTheme by remember { mutableStateOf(storage.getSetting("theme", "light") == "dark") }
    var page by remember { mutableStateOf(DesktopPage.MUSIC) }
    var query by remember { mutableStateOf("") }
    var searchHistory by remember { mutableStateOf(storage.loadSearchHistory()) }
    var source by remember { mutableStateOf("all") }
    var results by remember { mutableStateOf(emptyList<Song>()) }
    var searchPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var totalResults by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableLongStateOf(0L) }
    var playbackLoadJob by remember { mutableStateOf<Job?>(null) }
    // 统一标记当前直链解析请求；切歌或切音质时推进，旧请求即使取消不及时也不能落地播放。
    var playbackLoadGeneration by remember { mutableLongStateOf(0L) }
    var lyricJob by remember { mutableStateOf<Job?>(null) }
    var qualitySwitchJob by remember { mutableStateOf<Job?>(null) }
    var qualityOptionsJob by remember { mutableStateOf<Job?>(null) }
    var qualityOptionsGeneration by remember { mutableLongStateOf(0L) }
    var downloadQualityJob by remember { mutableStateOf<Job?>(null) }
    var downloadQualityGeneration by remember { mutableLongStateOf(0L) }
    var queue by remember { mutableStateOf(storage.loadQueue()?.queue.orEmpty()) }
    var currentIndex by remember { mutableIntStateOf(storage.loadQueue()?.index ?: 0) }
    var currentPositionMs by remember { mutableIntStateOf(storage.loadQueue()?.positionMs ?: 0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var playerBusy by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var repeatMode by remember { mutableIntStateOf(0) }
    var volume by remember { mutableStateOf(initialVolume) }
    var showPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var lyric by remember { mutableStateOf(Lyric.EMPTY) }
    var lyricLoading by remember { mutableStateOf(false) }
    var lyricError by remember { mutableStateOf<String?>(null) }
    var favorites by remember(accountId) { mutableStateOf(storage.loadFavorites()) }
    var favoriteBusy by remember { mutableStateOf(emptySet<String>()) }
    // 与 in-flight 操作分开保留最后一次变更代际；请求成功后不能删除它，否则同步快照会覆盖刚完成的点击。
    var favoriteMutationGenerations by remember { mutableStateOf(emptyMap<String, Long>()) }
    // 保存最后一次本地目标值，直到服务端快照真正观察到它，避免 eventual consistency 覆盖乐观状态。
    var favoriteMutationTargets by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var history by remember(accountId) {
        mutableStateOf(storage.loadHistory().map { item ->
            LocalHistory(
                song = item.song,
                playedAt = item.playedAt,
                firstPlayedAt = item.firstPlayedAt,
                playCount = item.playCount,
                completedCount = item.completedCount,
                totalListenedMs = item.totalListenedMs,
                historyRevision = item.historyRevision,
            )
        })
    }
    var downloaded by remember { mutableStateOf(downloads.list()) }
    var downloadBusy by remember { mutableStateOf(emptySet<String>()) }
    var downloadProgress by remember { mutableStateOf(emptyMap<String, DesktopDownloadProgress>()) }
    // 每个歌曲只保留当前下载任务的 token，避免旧账号任务清理新账号的 UI 状态。
    var downloadTaskTokens by remember { mutableStateOf(emptyMap<String, Long>()) }
    var downloadTaskJobs by remember { mutableStateOf(emptyMap<Long, Job>()) }
    var downloadTaskSequence by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    // 稳定安装布局下启动即检查桌面更新。下载与差分准备不触碰正在加载的 JAR；
    // 独立 updater 启动后才退出本进程，并在文件句柄释放后原子切换 current。
    LaunchedEffect(deviceId) {
        if (WindowsUpdateManager.installationRoot() == null) return@LaunchedEffect
        delay(2_000L)
        val manager = WindowsUpdateManager()
        val result = withContext(Dispatchers.IO) { runCatching { manager.check(deviceId) } }
        result.onSuccess { checked ->
            if (checked is WindowsUpdateResult.Available) {
                message = "发现 Windows ${checked.release.versionName}，正在准备更新"
                val staged = withContext(Dispatchers.IO) { runCatching { manager.stage(checked.release) } }
                staged.onSuccess { prepared ->
                    if (prepared is WindowsUpdateResult.Staged) {
                        message = "更新已准备完成，正在重启"
                        withContext(Dispatchers.IO) { manager.launchUpdater(checked.release) }
                        onExit()
                    } else if (prepared is WindowsUpdateResult.Unsupported) {
                        message = prepared.reason
                    }
                }.onFailure { error -> message = "Windows 更新准备失败：${error.message}" }
            }
        }.onFailure { error -> message = "Windows 更新检查失败：${error.message}" }
    }
    var playbackSessionId by remember { mutableStateOf<String?>(null) }
    var playbackSessionAccountId by remember { mutableStateOf<Long?>(null) }
    var playbackSessionSongKey by remember { mutableStateOf<String?>(null) }
    var playbackStartedAt by remember { mutableStateOf(0L) }
    var playbackListenedMs by remember { mutableLongStateOf(0L) }
    var historyRecordedMs by remember { mutableLongStateOf(0L) }
    var historyAdmitted by remember { mutableStateOf(false) }
    var playbackCompleted by remember { mutableStateOf(false) }
    // 清空/退出账号时推进本地同步代际；旧网络回包不能再删除或改写新会话快照。
    var playbackSyncEpoch by remember { mutableLongStateOf(0L) }
    var playbackHistoryRevision by remember(accountId) { mutableLongStateOf(playbackOutbox.historyRevision(accountId)) }
    var historyRevision by remember(accountId) {
        mutableLongStateOf(maxOf(loadHistoryRevision(storage, accountId), playbackOutbox.historyRevision(accountId)))
    }
    var qualityOptions by remember { mutableStateOf<List<DesktopMusicApi.QualityOption>>(emptyList()) }
    var qualityOptionsLoading by remember { mutableStateOf(false) }
    var qualityOptionsError by remember { mutableStateOf<String?>(null) }
    var downloadTarget by remember { mutableStateOf<Song?>(null) }
    var downloadQualityOptions by remember { mutableStateOf<List<DesktopMusicApi.QualityOption>>(emptyList()) }
    var downloadQualityLoading by remember { mutableStateOf(false) }
    var downloadQualityError by remember { mutableStateOf<String?>(null) }
    var favoriteOperationGenerations by remember { mutableStateOf(emptyMap<String, Long>()) }
    var accountSyncJob by remember { mutableStateOf<Job?>(null) }
    var accountSyncGeneration by remember { mutableLongStateOf(0L) }
    var playbackQuality by remember { mutableStateOf(desktopQuality(storage.getSetting("playbackQuality", AudioQuality.Default.value.toString()).toIntOrNull())) }
    // 播放页切换音质只作用于当前歌曲；设置页的 playbackQuality 始终是默认档位。
    var currentPlaybackQuality by remember { mutableStateOf<AudioQuality?>(null) }
    var downloadQuality by remember { mutableStateOf(desktopQuality(storage.getSetting("downloadQuality", AudioQuality.LOSSLESS.value.toString()).toIntOrNull())) }
    var playlists by remember(accountId) { mutableStateOf(emptyList<DesktopMusicApi.Playlist>()) }
    var selectedPlaylistId by remember(accountId) { mutableStateOf<Long?>(null) }
    var selectedPlaylist by remember(accountId) { mutableStateOf<DesktopMusicApi.Playlist?>(null) }
    var playlistLoading by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    // 定时播放是设备会话级状态，不写入账号或磁盘；切歌继续计时，暂停时保留剩余时间。
    var sleepTimerRemainingMs by remember { mutableLongStateOf(0L) }
    var sleepTimerGeneration by remember { mutableLongStateOf(0L) }
    // stop() 会让播放器媒体代际递增；保留停止前代际以吞掉可能已经排队的结束回调，避免自动下一首。
    var sleepTimerStopRequested by remember { mutableStateOf(false) }
    var sleepTimerStopMediaGeneration by remember { mutableLongStateOf(-1L) }
    var sleepTimerStopStateGeneration by remember { mutableLongStateOf(-1L) }

    val currentSong = queue.getOrNull(currentIndex)

    // `currentSong` 是本次组合的派生快照；播放器线程和长生命周期协程不能捕获它，
    // 否则快速切歌后旧闭包会把新歌误判为过期。通过可变队列/下标实时读取当前项。
    fun currentSongNow(): Song? = queue.getOrNull(currentIndex)

    fun sessionIsCurrent(account: Long, generation: Long): Boolean =
        session.accountId == account && session.sessionGeneration == generation

    fun playbackSyncIsCurrent(account: Long, generation: Long, epoch: Long): Boolean =
        sessionIsCurrent(account, generation) && playbackSyncEpoch == epoch

    /** 只有清空之后开始的当前会话才可以迁移到新的历史代际。 */
    fun canRebaseCurrentPlaybackSession(account: Long, sessionId: String, clearedAt: Long): Boolean =
        playbackSessionId == sessionId &&
            playbackSessionAccountId == account &&
            playbackStartedAt >= clearedAt

    fun accountSyncIsCurrent(account: Long, generation: Long, syncGeneration: Long): Boolean =
        sessionIsCurrent(account, generation) && accountSyncGeneration == syncGeneration

    fun invalidatePlaybackLoad(): Long {
        playbackLoadGeneration += 1
        playbackLoadJob?.cancel()
        qualitySwitchJob?.cancel()
        return playbackLoadGeneration
    }

    fun playbackLoadIsCurrent(
        loadGeneration: Long,
        account: Long?,
        sessionGeneration: Long,
        sessionId: String,
        songKey: String,
        expectedQuality: Int? = null,
    ): Boolean = playbackLoadGeneration == loadGeneration &&
        session.accountId == account &&
        session.sessionGeneration == sessionGeneration &&
        playbackSessionId == sessionId &&
        playbackSessionSongKey == songKey &&
        currentSongNow()?.let { DesktopStorage.songKey(it) == songKey } == true &&
        (expectedQuality == null || (currentPlaybackQuality ?: playbackQuality).value == expectedQuality)

    fun persistQueue() {
        if (queue.isNotEmpty()) storage.saveQueue(queue, currentIndex, currentPositionMs)
    }

    /** 更新队列中的远程歌曲音质占位地址，保证冷启动恢复时不会回到旧档位。 */
    fun updateRemotePlaceholder(song: Song, quality: Int) {
        val key = DesktopStorage.songKey(song)
        val index = queue.indexOfFirst { DesktopStorage.songKey(it) == key }
        val queued = queue.getOrNull(index) ?: return
        if (!queued.hasRemoteIdentity()) return
        if (queued.audioUri.orEmpty().startsWith("file:", ignoreCase = true)) return
        val updated = queued.copy(
            audioUri = DesktopMusicApi.placeholderUri(queued, quality, api.endpoint),
        )
        if (updated.audioUri == queued.audioUri) return
        queue = queue.toMutableList().also { it[index] = updated }
        persistQueue()
    }

    fun showMessage(text: String) {
        message = text
        scope.launch { delay(3_500); if (message == text) message = null }
    }

    fun cancelSleepTimer(showMessage: Boolean = true) {
        sleepTimerGeneration += 1
        sleepTimerRemainingMs = 0L
        sleepTimerStopRequested = false
        sleepTimerStopMediaGeneration = -1L
        sleepTimerStopStateGeneration = -1L
        if (showMessage) showMessage("已取消定时播放")
    }

    /** 清理播放媒体时同时清理睡眠计时器，避免退出账号/应用后留下后台协程。 */
    fun clearSleepTimerForPlaybackReset() {
        if (sleepTimerRemainingMs > 0L || sleepTimerStopRequested || sleepTimerStopStateGeneration >= 0L) {
            cancelSleepTimer(showMessage = false)
        }
    }

    fun configureSleepTimer(durationMs: Long) {
        val safeDuration = durationMs.coerceIn(SLEEP_TIMER_MINUTE_MS, SLEEP_TIMER_MAX_MINUTES.toLong() * SLEEP_TIMER_MINUTE_MS)
        sleepTimerGeneration += 1
        sleepTimerRemainingMs = safeDuration
        sleepTimerStopRequested = false
        sleepTimerStopMediaGeneration = -1L
        sleepTimerStopStateGeneration = -1L
        showMessage("已设置定时播放：${formatSleepTimerRemaining(safeDuration)}")
    }

    fun refreshPlaylists(selectFirst: Boolean = false) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        playlistLoading = true
        playlistError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.playlists(expectedGeneration = generation) } }
            if (!sessionIsCurrent(account, generation)) return@launch
            result.onSuccess { loaded ->
                playlists = loaded
                val target = when {
                    selectFirst -> loaded.firstOrNull()?.id
                    selectedPlaylistId != null && loaded.any { it.id == selectedPlaylistId } -> selectedPlaylistId
                    else -> loaded.firstOrNull()?.id
                }
                selectedPlaylistId = target
                selectedPlaylist = target?.let { id ->
                    withContext(Dispatchers.IO) { runCatching { api.playlist(id, playbackQuality.value, generation) }.getOrNull() }
                }
                playlistError = null
            }.onFailure { playlistError = it.message ?: "歌单同步失败" }
            playlistLoading = false
        }
    }

    fun selectPlaylist(id: Long) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        selectedPlaylistId = id
        playlistLoading = true
        playlistError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.playlist(id, playbackQuality.value, generation) } }
            if (!sessionIsCurrent(account, generation) || selectedPlaylistId != id) return@launch
            result.onSuccess { selectedPlaylist = it }
                .onFailure { playlistError = it.message ?: "歌单读取失败" }
            playlistLoading = false
        }
    }

    fun applyPlaylistDetail(detail: DesktopMusicApi.Playlist) {
        playlists = playlists.map { if (it.id == detail.id) detail.copy(songs = emptyList()) else it }
        selectedPlaylistId = detail.id
        selectedPlaylist = detail
    }

    fun createPlaylist(name: String) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.createPlaylist(name, expectedGeneration = generation) } }
            if (!sessionIsCurrent(account, generation)) return@launch
            result.onSuccess { detail ->
                playlists = listOf(detail) + playlists
                selectedPlaylistId = detail.id
                selectedPlaylist = detail.copy(songs = emptyList())
                showMessage("已创建歌单“${detail.name}”")
            }.onFailure { showMessage(it.message ?: "创建歌单失败") }
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.updatePlaylist(id, name = name, expectedGeneration = generation) } }
            if (!sessionIsCurrent(account, generation)) return@launch
            result.onSuccess { applyPlaylistDetail(it); showMessage("歌单名称已更新") }
                .onFailure { showMessage(it.message ?: "修改歌单失败") }
        }
    }

    fun deletePlaylist(id: Long) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.deletePlaylist(id, generation); Unit } }
            if (!sessionIsCurrent(account, generation)) return@launch
            result.onSuccess {
                playlists = playlists.filterNot { it.id == id }
                val next = playlists.firstOrNull()?.id
                selectedPlaylistId = next
                selectedPlaylist = next?.let { withContext(Dispatchers.IO) { runCatching { api.playlist(it, playbackQuality.value, generation) }.getOrNull() } }
                showMessage("歌单已删除")
            }.onFailure { showMessage(it.message ?: "删除歌单失败") }
        }
    }

    fun addPlaylistSong(id: Long, song: Song) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        playlistLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.addPlaylistSong(id, song, generation) } }
            if (!sessionIsCurrent(account, generation) || selectedPlaylistId != id) return@launch
            result.onSuccess { applyPlaylistDetail(it); showMessage("已加入歌单") }
                .onFailure { showMessage(it.message ?: "加入歌单失败") }
            playlistLoading = false
        }
    }

    fun removePlaylistSong(id: Long, song: Song) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        playlistLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.removePlaylistSong(id, song, generation) } }
            if (!sessionIsCurrent(account, generation) || selectedPlaylistId != id) return@launch
            result.onSuccess { detail ->
                detail?.let(::applyPlaylistDetail)
                if (detail == null) selectPlaylist(id)
                showMessage("已从歌单移除")
            }.onFailure { showMessage(it.message ?: "移除歌曲失败") }
            playlistLoading = false
        }
    }

    fun reorderPlaylist(id: Long, songs: List<Song>) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        playlistLoading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.reorderPlaylist(id, songs, generation) } }
            if (!sessionIsCurrent(account, generation) || selectedPlaylistId != id) return@launch
            result.onSuccess(::applyPlaylistDetail)
                .onFailure { showMessage(it.message ?: "歌单排序失败") }
            playlistLoading = false
        }
    }

    val playlistAvailableSongs = remember(results, queue, favorites, history, downloaded) {
        buildList {
            addAll(results)
            addAll(queue)
            addAll(favorites.values)
            addAll(history.map { it.song })
            addAll(downloaded)
        }.distinctBy { DesktopStorage.songKey(it) }
    }

    fun recordHistory(song: Song, started: Boolean = false, completed: Boolean = false, listenedMs: Long = 0L) {
        // 服务端清空后仍在播放的旧会话可以继续统计本地时长，但不能重新进入最近播放。
        // 等它自然结束或切歌后，新会话会重新建立可见代际。
        val sessionRevision = playbackHistoryRevision.coerceAtLeast(0L)
        if (sessionRevision < historyRevision) return
        val now = System.currentTimeMillis()
        val key = DesktopStorage.songKey(song)
        // 跨代际的旧条目不能把清空前的播放次数/累计时长带入新会话。
        val old = history.firstOrNull { DesktopStorage.songKey(it.song) == key }
            ?.takeIf { it.historyRevision == sessionRevision }
        val updated = LocalHistory(
            song = song,
            playedAt = now,
            firstPlayedAt = old?.firstPlayedAt ?: now,
            playCount = (old?.playCount ?: 0) + if (started) 1 else 0,
            completedCount = (old?.completedCount ?: 0) + if (completed) 1 else 0,
            totalListenedMs = (old?.totalListenedMs ?: 0L) + listenedMs,
            historyRevision = sessionRevision,
        )
        history = listOf(updated) + history.filterNot { DesktopStorage.songKey(it.song) == key }.take(DesktopStorage.MAX_HISTORY - 1)
        storage.saveHistory(history.map { it.toStorage() })
    }

    /** 接收服务端历史状态，并立即按代际裁剪本地历史，避免跨设备清空后旧会话复活。 */
    fun applyHistoryState(
        account: Long,
        state: DesktopMusicApi.PlaybackState,
        expectedSessionId: String? = null,
    ): Long {
        val accepted = playbackOutbox.acceptServerRevision(account, state.revision)
        val activeSession = playbackSessionId
        val canRebase = activeSession != null &&
            (expectedSessionId == null || activeSession == expectedSessionId) &&
            canRebaseCurrentPlaybackSession(account, activeSession, state.clearedAt)
        if (canRebase && accepted > playbackHistoryRevision) {
            activeSession?.let { playbackOutbox.rebaseSessionRevision(account, it, accepted) }
            playbackHistoryRevision = accepted
            // 当前会话可能已在旧代际达到 3 秒准入；重置本地准入游标，
            // 下一次快照会按新代际重新记录完整收听时长。
            historyAdmitted = false
            historyRecordedMs = 0L
            playbackCompleted = false
        }
        historyRevision = accepted
        storage.setSetting(historyRevisionKey(account), accepted.toString())
        val retained = history.filter { it.historyRevision >= accepted }
        if (retained.size != history.size) {
            history = retained
            storage.saveHistory(history.map { it.toStorage() })
        }
        return accepted
    }

    suspend fun syncPendingPlayback(account: Long) {
        val generation = session.sessionGeneration
        val syncEpoch = playbackSyncEpoch
        playbackSyncMutex.withLock {
            if (!playbackSyncIsCurrent(account, generation, syncEpoch)) return@withLock

            var syncRevision = playbackOutbox.historyRevision(account)
            var knownClearedAt: Long? = null
            val remoteState = withContext(Dispatchers.IO) { runCatching { api.historyState(expectedGeneration = generation) }.getOrNull() }
            if (!playbackSyncIsCurrent(account, generation, syncEpoch)) return@withLock
            remoteState?.let { state ->
                knownClearedAt = state.clearedAt
                syncRevision = applyHistoryState(account, state)
            }

            playbackOutbox.clearMarker(account)?.let { marker ->
                val cleared = withContext(Dispatchers.IO) { runCatching { api.clearRecent(marker, expectedGeneration = generation) }.getOrNull() }
                    ?: return@withLock
                if (!playbackSyncIsCurrent(account, generation, syncEpoch)) return@withLock
                val confirmed = playbackOutbox.confirmClear(account, cleared.revision)
                syncRevision = confirmed
                knownClearedAt = cleared.clearedAt
                applyHistoryState(account, cleared)
            }

            for (snapshot in playbackOutbox.snapshots(account)) {
                if (!playbackSyncIsCurrent(account, generation, syncEpoch)) return@withLock
                var upload = snapshot
                var revisionRetryCount = 0
                while (true) {
                    // rebaseSessionRevision 与 put 都是原子落盘；重新取一份快照，避免在
                    // 读取旧版本后被 refreshHistoryRevision 更新的场景下仍发送旧代际。
                    if (revisionRetryCount == 0 && upload.historyRevision < syncRevision &&
                        knownClearedAt != null && canRebaseCurrentPlaybackSession(account, upload.sessionId, knownClearedAt!!)
                    ) {
                        upload = playbackOutbox.rebaseSessionRevision(account, upload.sessionId, syncRevision)
                            ?: playbackOutbox.snapshots(account).firstOrNull { it.sessionId == upload.sessionId }
                            ?: upload
                    }
                    val report = withContext(Dispatchers.IO) {
                        runCatching {
                            api.reportPlayback(
                                sessionId = upload.sessionId,
                                deviceId = deviceId,
                                source = upload.source,
                                songId = upload.songId,
                                startedAt = upload.startedAt,
                                lastPlayedAt = upload.lastPlayedAt,
                                listenedMs = upload.listenedMs,
                                durationSeconds = upload.durationSeconds,
                                completed = upload.completed,
                                historyRevision = upload.historyRevision,
                                expectedGeneration = generation,
                            )
                        }.getOrNull()
                    } ?: break
                    if (!playbackSyncIsCurrent(account, generation, syncEpoch)) return@withLock

                    // 清空可能发生在本次请求飞行期间。只有确认这是当前会话且它在清空之后
                    // 开始，才迁移代际并重试；旧离线会话必须继续留在旧代际，不能复活到列表。
                    if (report.currentHistoryRevision > upload.historyRevision &&
                        playbackSessionAccountId == account && playbackSessionId == upload.sessionId
                    ) {
                        val stateAfterReport = withContext(Dispatchers.IO) {
                            runCatching { api.historyState(expectedGeneration = generation) }.getOrNull()
                        } ?: break
                        if (!playbackSyncIsCurrent(account, generation, syncEpoch)) return@withLock
                        val accepted = applyHistoryState(account, stateAfterReport)
                        syncRevision = maxOf(syncRevision, accepted)
                        knownClearedAt = stateAfterReport.clearedAt
                        if (stateAfterReport.revision > upload.historyRevision &&
                            canRebaseCurrentPlaybackSession(account, upload.sessionId, stateAfterReport.clearedAt)
                        ) {
                            // 清空可能连续发生在多台设备上。每次都以最新 clearedAt
                            // 重新判断当前会话，达到上限时保留 outbox，下一轮继续收敛。
                            if (revisionRetryCount >= MAX_PLAYBACK_REVISION_RETRIES) break
                            upload = playbackOutbox.rebaseSessionRevision(account, upload.sessionId, stateAfterReport.revision) ?: break
                            revisionRetryCount++
                            continue
                        }
                    }

                    if (!playbackSyncIsCurrent(account, generation, syncEpoch)) return@withLock
                    playbackOutbox.removeIfUnchanged(account, upload)
                    val accepted = playbackOutbox.acceptServerRevision(account, report.currentHistoryRevision)
                    syncRevision = maxOf(syncRevision, accepted)
                    historyRevision = accepted
                    storage.setSetting(historyRevisionKey(account), accepted.toString())
                    break
                }
            }
        }
    }

    fun launchPlaybackSync(account: Long?) {
        account?.takeIf { it > 0L }?.let { scope.launch { syncPendingPlayback(it) } }
    }

    /** 先同步落盘，再异步上传；seek 只改变播放位置，不会改变这里的实际收听累计。 */
    fun persistPlaybackSnapshot(song: Song, completed: Boolean = false) {
        val sessionId = playbackSessionId ?: return
        val account = playbackSessionAccountId ?: return
        if (playbackSessionSongKey != DesktopStorage.songKey(song)) return
        val listened = playbackListenedMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val historyEligible = playbackHistoryRevision >= historyRevision
        val admittedNow = historyEligible && !historyAdmitted && listened >= HISTORY_ADMISSION_MS
        val shouldCompleteHistory = historyEligible && completed && !playbackCompleted && (historyAdmitted || admittedNow)
        if (historyEligible && (historyAdmitted || admittedNow)) {
            val delta = (listened - historyRecordedMs).coerceAtLeast(0L)
            if (admittedNow || shouldCompleteHistory || delta > 0L) {
                recordHistory(
                    song = song,
                    started = admittedNow,
                    completed = shouldCompleteHistory,
                    listenedMs = delta,
                )
                historyAdmitted = true
                historyRecordedMs = listened
            }
        }
        playbackCompleted = playbackCompleted || completed

        val songId = song.remoteIdentity() ?: return
        if (listened <= 0L && !completed) return
        playbackOutbox.put(
            DesktopPlaybackSnapshot(
                sessionId = sessionId,
                accountId = account,
                source = song.source,
                songId = songId,
                startedAt = playbackStartedAt,
                lastPlayedAt = now,
                listenedMs = listened,
                durationSeconds = durationMs.takeIf { it > 0 }?.div(1_000),
                completed = completed,
                historyRevision = playbackHistoryRevision,
            ),
        )
        launchPlaybackSync(account.takeIf { session.accountId == it })
    }

    /** 定时到点只停止当前媒体，保留队列和位置，后续手动播放时从该位置重新加载。 */
    fun stopForSleepTimer() {
        val activeSong = currentSongNow()
        val stoppedPosition = maxOf(currentPositionMs, player.positionMs).coerceAtLeast(0)
        val stoppedDuration = maxOf(durationMs, player.durationMs).coerceAtLeast(0)
        val mediaGeneration = player.mediaGeneration

        sleepTimerGeneration += 1
        sleepTimerRemainingMs = 0L
        sleepTimerStopRequested = true
        sleepTimerStopMediaGeneration = mediaGeneration
        sleepTimerStopStateGeneration = -1L
        currentPositionMs = stoppedPosition
        durationMs = stoppedDuration
        activeSong?.takeIf { playbackSessionId != null }?.let { persistPlaybackSnapshot(it) }
        invalidatePlaybackLoad()
        player.stop()
        // 读取实际代际，而不是假定 stop() 只会递增一次，避免并发媒体操作时误吞新回调。
        sleepTimerStopStateGeneration = player.mediaGeneration
        playbackSessionId = null
        playbackSessionAccountId = null
        playbackSessionSongKey = null
        playbackListenedMs = 0L
        historyRecordedMs = 0L
        historyAdmitted = false
        playbackCompleted = false
        playing = false
        playerBusy = false
        playerError = null
        persistQueue()
        showMessage("定时播放结束，已停止当前歌曲")
    }

    fun beginPlaybackTracking(song: Song): String {
        val account = session.accountId
        val sessionId = UUID.randomUUID().toString()
        playbackSessionId = sessionId
        playbackSessionAccountId = account
        playbackSessionSongKey = DesktopStorage.songKey(song)
        playbackStartedAt = System.currentTimeMillis()
        playbackListenedMs = 0L
        historyRecordedMs = 0L
        historyAdmitted = false
        playbackCompleted = false
        playbackHistoryRevision = playbackOutbox.acceptServerRevision(account, historyRevision)
        return sessionId
    }

    fun loadLyric(song: Song) {
        val account = session.accountId ?: return
        val key = DesktopStorage.songKey(song)
        val generation = session.sessionGeneration
        lyricJob?.cancel()
        lyricLoading = true
        lyricError = null
        lyric = Lyric.EMPTY
        lyricJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                val local = downloads.find(song)
                val lrc = local?.lyricUri?.let(::readUri)
                val yrc = local?.lyricWordsUri?.let(::readUri)
                if (!lrc.isNullOrBlank() || !yrc.isNullOrBlank()) {
                    Result.success(LyricParser.parse(lrc, yrc))
                } else {
                    runCatching { api.requestRichLyric(song, expectedGeneration = generation) }
                        .map { response -> LyricParser.parse(response.lrc, response.yrc) }
                }
            }
            if (sessionIsCurrent(account, generation) && currentSongNow()?.let { DesktopStorage.songKey(it) == key } == true) {
                result.onSuccess { lyric = it }
                    .onFailure { lyricError = it.message ?: "歌词加载失败" }
                lyricLoading = false
            }
        }
    }

    fun refreshHistoryRevision(sessionId: String) {
        val account = session.accountId
        if (account == null) return
        val generation = session.sessionGeneration
        val syncEpoch = playbackSyncEpoch
        scope.launch {
            playbackSyncMutex.withLock {
                val state = withContext(Dispatchers.IO) { runCatching { api.historyState(expectedGeneration = generation) }.getOrNull() }
                if (state != null && playbackSyncIsCurrent(account, generation, syncEpoch)) {
                    applyHistoryState(account, state, expectedSessionId = sessionId)
                }
            }
        }
    }

    fun startSong(song: Song, songs: List<Song> = queue, index: Int = songs.indexOf(song).coerceAtLeast(0), positionMs: Int = 0) {
        val account = session.accountId
        val generation = session.sessionGeneration
        val loadGeneration = invalidatePlaybackLoad()
        // 手动切歌表示用户重新开始播放；旧的定时停止标记只用于吞掉已排队的结束回调。
        sleepTimerStopRequested = false
        sleepTimerStopMediaGeneration = -1L
        sleepTimerStopStateGeneration = -1L
        currentSongNow()?.takeIf { playbackSessionId != null }?.let { previous ->
            persistPlaybackSnapshot(previous)
            playbackSessionId = null
        }
        // 解析新歌直链可能需要数秒；先停止旧媒体，避免 UI 已切到 B 时仍在播放 A、
        // 播放计时也被错误记到 B 的会话中。
        player.stop()
        playing = false
        durationMs = 0
        // 不可播的歌（酷我上拿不到播放地址的正版曲）不进队列：它们带着永不过期的占位地址，
        // 留着只会在播到那一首时失败。被点中的那首必然可播（列表行已禁用点击），
        // 用 `== song` 保底留下它，否则下面按 song 定位会落空。
        val normalizedQueue = songs.ifEmpty { listOf(song) }.filter { it.playable || it == song }
        queue = normalizedQueue
        currentIndex = normalizedQueue.indexOf(song)
            .takeIf { it >= 0 }
            ?: index.coerceIn(normalizedQueue.indices)
        currentPositionMs = positionMs.coerceAtLeast(0)
        playerBusy = true
        playerError = null
        showPlayer = true
        val sessionSong = normalizedQueue[currentIndex]
        val newSessionId = beginPlaybackTracking(sessionSong)
        refreshHistoryRevision(newSessionId)
        loadLyric(sessionSong)
        val requestedQuality = placeholderQuality(sessionSong) ?: playbackQuality.value
        currentPlaybackQuality = desktopQuality(requestedQuality)
        playbackLoadJob = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val local = downloads.find(sessionSong)?.let { candidate ->
                        val localUri = candidate.audioUri
                        if (DesktopPlayer.supports(localUri)) {
                            candidate.copy(favorited = sessionSong.favorited)
                        } else {
                            null
                        }
                    }
                    if (local != null) {
                        DesktopPlaybackSource(requireNotNull(local.audioUri), local, local.localQuality)
                    } else {
                        val link = api.resolveLink(sessionSong, requestedQuality, expectedGeneration = generation)
                        DesktopPlayer.unsupportedExtension(link.url)?.let {
                            throw DesktopApiException("Windows 暂不支持 NAC 私有音频格式，请选择其他音质")
                        }
                        DesktopPlaybackSource(link.url, quality = link.quality)
                    }
                }
            }
            if (!playbackLoadIsCurrent(loadGeneration, account, generation, newSessionId, DesktopStorage.songKey(sessionSong))) return@launch
            result.onSuccess { source ->
                if (!playbackLoadIsCurrent(loadGeneration, account, generation, newSessionId, DesktopStorage.songKey(sessionSong))) return@onSuccess
                source.localSong?.let { localSong ->
                    val key = DesktopStorage.songKey(sessionSong)
                    val localIndex = queue.indexOfFirst { DesktopStorage.songKey(it) == key }
                    if (localIndex >= 0) {
                        queue = queue.toMutableList().also { it[localIndex] = localSong }
                        currentPlaybackQuality = desktopQuality(localSong.localQuality ?: requestedQuality)
                    }
                }
                source.quality?.let { actualQuality ->
                    currentPlaybackQuality = desktopQuality(actualQuality)
                    if (source.localSong == null) updateRemotePlaceholder(sessionSong, actualQuality)
                }
                playerBusy = false
                player.play(source.uri, positionMs)
                persistQueue()
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    playerBusy = false
                    playerError = error.message ?: "无法播放这首歌"
                }
            }
        }
    }

    fun nextSong() {
        if (queue.isEmpty()) return
        val next = when {
            repeatMode == 2 -> currentIndex
            currentIndex + 1 < queue.size -> currentIndex + 1
            repeatMode == 1 -> 0
            else -> return
        }
        startSong(queue[next], queue, next)
    }

    fun previousSong() {
        if (queue.isEmpty()) return
        if (currentPositionMs > 3_000) {
            player.seek(0)
            currentPositionMs = 0
            return
        }
        val previous = if (currentIndex > 0) currentIndex - 1 else if (repeatMode == 1) queue.lastIndex else 0
        startSong(queue[previous], queue, previous)
    }

    fun playNext(song: Song) {
        val active = currentSongNow()
        if (active == null || queue.isEmpty()) {
            startSong(song, listOf(song), 0)
            return
        }
        val activeKey = DesktopStorage.songKey(active)
        val songKey = DesktopStorage.songKey(song)
        if (activeKey == songKey) return
        val updated = queue.toMutableList()
        updated.indexOfFirst { DesktopStorage.songKey(it) == songKey }
            .takeIf { it >= 0 }
            ?.let(updated::removeAt)
        val activeIndex = updated.indexOfFirst { DesktopStorage.songKey(it) == activeKey }.coerceAtLeast(0)
        updated.add((activeIndex + 1).coerceAtMost(updated.size), song)
        queue = updated
        currentIndex = activeIndex
        persistQueue()
        showMessage("已设为下一首：${song.title}")
    }

    fun clearHistory() {
        val account = session.accountId ?: return
        accountSyncJob?.cancel()
        accountSyncGeneration++
        playbackSyncEpoch++
        val activeSong = currentSongNow()
        val shouldResume = player.isPlaying || playing
        val resumePosition = player.positionMs.coerceAtLeast(currentPositionMs)
        // 清空会话同时失效正在解析的直链和播放器媒体代际；否则旧回调可能把
        // 清空前的结束事件写回新会话，或让 playerBusy 永久停在加载态。
        invalidatePlaybackLoad()
        player.stop()
        playerBusy = false
        playing = false
        val targetRevision = playbackOutbox.markClearPending(account) ?: return
        historyRevision = targetRevision
        storage.setSetting(historyRevisionKey(account), targetRevision.toString())
        history = emptyList()
        storage.saveHistory(emptyList())

        // 清空前的会话已经由 outbox 丢弃；继续播放时另起新会话，满 3 秒后才重新进入历史。
        playbackSessionId = null
        playbackSessionAccountId = null
        playbackSessionSongKey = null
        if (activeSong != null) {
            if (shouldResume) {
                startSong(activeSong, queue, currentIndex, resumePosition)
            } else {
                // 即使当前暂停，也要为下一次继续播放建立新的统计会话；否则清空后恢复播放不会再上报。
                beginPlaybackTracking(activeSong)
            }
        }
        showMessage("最近播放已清空")
        launchPlaybackSync(account)
    }

    fun toggleFavorite(song: Song) {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        val key = DesktopStorage.songKey(song)
        if (favoriteBusy.contains(key) || !song.hasRemoteIdentity()) return
        val previousSongEntries = favorites.filterValues { it.sameRemoteSong(song) }
        val target = previousSongEntries.isEmpty()
        val previous = favorites
        val operation = (favoriteMutationGenerations[key] ?: 0L) + 1L
        favoriteMutationGenerations = favoriteMutationGenerations + (key to operation)
        favoriteMutationTargets = favoriteMutationTargets + (key to target)
        favoriteOperationGenerations = favoriteOperationGenerations + (key to operation)
        favorites = if (target) {
            previous + (key to song.copy(favorited = true))
        } else {
            previous.filterValues { !it.sameRemoteSong(song) }
        }
        storage.saveFavorites(favorites.values)
        favoriteBusy = favoriteBusy + key
        scope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { api.setFavorite(song, target, expectedGeneration = generation) }.exceptionOrNull() }
            val stillCurrent = sessionIsCurrent(account, generation) && favoriteOperationGenerations[key] == operation
            if (failure != null && stillCurrent) {
                // 其它歌曲的收藏操作可以并发进行；失败时只回滚当前键，不能恢复整张旧快照。
                favorites = favorites.filterValues { !it.sameRemoteSong(song) } + previousSongEntries
                storage.saveFavorites(favorites.values)
                // 失败响应无法证明服务端是否已经落地（例如响应超时）；回滚值不能当作
                // 已确认目标长期保护，否则服务端实际成功时会被本地状态永久遮住。下一次
                // 账号同步会重新读取服务端快照并收敛到真实状态。
                favoriteMutationTargets = favoriteMutationTargets - key
                showMessage("收藏同步失败：${failure.message ?: "请稍后重试"}")
            }
            if (stillCurrent) {
                favoriteBusy = favoriteBusy - key
                favoriteOperationGenerations = favoriteOperationGenerations - key
            }
        }
    }

    fun shareSong(song: Song) {
        val account = session.accountId ?: run {
            showMessage("登录后才能分享歌曲")
            return
        }
        val generation = session.sessionGeneration
        if (!song.hasRemoteIdentity()) {
            showMessage("本地歌曲缺少可分享的远端身份")
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.createSongShare(song, expectedGeneration = generation) }
            }
            if (!sessionIsCurrent(account, generation)) return@launch
            result.onSuccess { share ->
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(share.url), null)
                showMessage("分享链接已复制到剪贴板")
            }.onFailure { error ->
                showMessage("分享链接生成失败：${error.message ?: "网络错误"}")
            }
        }
    }

    fun openDownloadQuality(song: Song) {
        downloadQualityJob?.cancel()
        val requestGeneration = ++downloadQualityGeneration
        if (!song.hasRemoteIdentity() || downloadBusy.contains(DesktopStorage.songKey(song)) || downloads.find(song) != null) return
        val account = session.accountId ?: return
        val key = DesktopStorage.songKey(song)
        val generation = session.sessionGeneration
        downloadTarget = song
        downloadQualityOptions = emptyList()
        downloadQualityError = null
        downloadQualityLoading = true
        downloadQualityJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.requestQualities(song, expectedGeneration = generation) }
            }
            if (requestGeneration != downloadQualityGeneration ||
                !sessionIsCurrent(account, generation) ||
                downloadTarget?.let { DesktopStorage.songKey(it) == key } != true
            ) return@launch
            result.onSuccess { options ->
                val normalizedOptions = normalizeDesktopQualityOptions(song, options)
                val supported = normalizedOptions.filter { option ->
                    DesktopPlayer.supportsQuality(song.source, option.quality) && option.quality in desktopSelectableQualityValues
                }
                if (supported.isEmpty()) {
                    downloadQualityOptions = emptyList()
                    downloadQualityError = if (options.any { it.quality == 18 } && song.source.equals("tencent", ignoreCase = true)) {
                        "这首歌仅提供腾讯 NAC 私有格式，Windows 暂不支持下载。"
                    } else {
                        "这首歌暂无可播放的 Windows 音质档位。"
                    }
                } else {
                    downloadQualityOptions = supported
                    downloadQualityError = if (song.source.equals("netease", ignoreCase = true) && options.any { it.quality == 18 }) {
                        "网易云接口将最高档统一标记为 18，桌面已映射为臻品母带。"
                    } else {
                        null
                    }
                }
            }.onFailure {
                downloadQualityOptions = desktopQualityOptions()
                downloadQualityError = "无法查询实际音质，以下为通用档位；服务端可能自动降级。"
            }
            if (requestGeneration == downloadQualityGeneration && sessionIsCurrent(account, generation)) {
                downloadQualityLoading = false
            }
        }
    }

    fun downloadSong(song: Song, quality: Int = downloadQuality.value) {
        val generation = session.sessionGeneration
        val account = session.accountId ?: return
        val key = DesktopStorage.songKey(song)
        if (!song.hasRemoteIdentity() || downloadBusy.contains(key) || downloads.find(song) != null) return
        if (!DesktopPlayer.supportsQuality(song.source, quality)) {
            showMessage("NAC 私有格式暂不支持 Windows 下载，请选择其他音质")
            return
        }
        downloadTarget = null
        downloadQualityJob?.cancel()
        downloadQualityGeneration++
        val taskToken = ++downloadTaskSequence
        downloadTaskTokens = downloadTaskTokens + (key to taskToken)
        downloadBusy = downloadBusy + key
        val task = scope.launch(start = CoroutineStart.LAZY) {
            val taskJob = currentCoroutineContext()[Job]
            try {
                val saved = withContext(Dispatchers.IO) {
                    val link = api.resolveLink(song, quality, expectedGeneration = generation)
                    DesktopPlayer.unsupportedExtension(link.url)?.let {
                        throw DesktopApiException("Windows 暂不支持 NAC 私有音频格式，请选择其他音质")
                    }
                    val rich = runCatching { api.requestRichLyric(song, expectedGeneration = generation) }.getOrNull()
                    downloads.download(
                        song = song,
                        audioUrl = link.url,
                        quality = link.quality,
                        lyric = rich,
                        onProgress = { completed, total ->
                            scope.launch {
                                if (sessionIsCurrent(account, generation) && downloadTaskTokens[key] == taskToken && downloadBusy.contains(key)) {
                                    downloadProgress = downloadProgress + (key to DesktopDownloadProgress(completed, total))
                                }
                            }
                        },
                        shouldContinue = {
                            taskJob?.isActive == true &&
                                sessionIsCurrent(account, generation) &&
                                downloadTaskTokens[key] == taskToken
                        },
                    )
                }
                if (sessionIsCurrent(account, generation) && downloadTaskTokens[key] == taskToken) {
                    downloaded = downloads.list()
                    showMessage("已保存：${saved.title}")
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (sessionIsCurrent(account, generation) && downloadTaskTokens[key] == taskToken) {
                    showMessage("下载失败：${error.message ?: "网络错误"}")
                }
            } finally {
                // finally 不依赖登录代际；token 匹配时才允许清理，避免误删新任务状态。
                if (downloadTaskTokens[key] == taskToken) {
                    downloadTaskTokens = downloadTaskTokens - key
                    downloadBusy = downloadBusy - key
                    downloadProgress = downloadProgress - key
                }
                downloadTaskJobs = downloadTaskJobs - taskToken
            }
        }
        downloadTaskJobs = downloadTaskJobs + (taskToken to task)
        task.start()
    }

    fun switchQuality(quality: Int) {
        val song = currentSongNow() ?: return
        val key = DesktopStorage.songKey(song)
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        if (quality == 18 && !song.source.equals("netease", ignoreCase = true)) {
            showMessage("NAC 私有格式暂不支持 Windows 播放，请选择其他音质")
            return
        }
        if (downloads.find(song) != null) {
            showMessage("本地歌曲的音质由已下载文件决定")
            return
        }
        val selected = desktopQuality(quality)
        val previousPlaybackQuality = currentPlaybackQuality ?: playbackQuality
        currentPlaybackQuality = selected
        // 与 Android 一致：播放页的临时切档不改设置页的默认音质；把占位地址写回队列，
        // 下次从同一播放队列恢复时仍能使用用户刚选的档位。
        val hasRemoteIdentity = song.hasRemoteIdentity()
        if (hasRemoteIdentity && !song.audioUri.orEmpty().startsWith("file:", ignoreCase = true)) {
            updateRemotePlaceholder(song, selected.value)
        }
        // 歌曲自然播放结束后 playbackSessionId 会被清空；重新选音质仍应创建
        // 一轮新的统计会话，否则这次重新播放不会进入最近播放或 outbox。
        val hadPlaybackSession = playbackSessionId != null
        val activeSession = playbackSessionId ?: beginPlaybackTracking(song)
        if (!hadPlaybackSession) refreshHistoryRevision(activeSession)
        val position = if (playbackSessionId == activeSession && durationMs > 0 && currentPositionMs >= durationMs - 500) 0 else currentPositionMs
        val loadGeneration = invalidatePlaybackLoad()
        qualitySwitchJob = scope.launch {
            playerBusy = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.resolveLink(song, selected.value, expectedGeneration = generation).also { link ->
                        DesktopPlayer.unsupportedExtension(link.url)?.let {
                            throw DesktopApiException("Windows 暂不支持 NAC 私有音频格式，请选择其他音质")
                        }
                    }
                }
            }
            if (!playbackLoadIsCurrent(loadGeneration, account, generation, activeSession, key, selected.value)) {
                return@launch
            }
            result.onSuccess { link ->
                if (!playbackLoadIsCurrent(loadGeneration, account, generation, activeSession, key, selected.value)) return@onSuccess
                player.play(link.url, position)
                currentPlaybackQuality = desktopQuality(link.quality)
                updateRemotePlaceholder(song, link.quality)
                if (link.fallback) {
                    val kbps = link.kbps.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
                    showMessage("已降级到 ${labelOfQuality(link.quality)}$kbps")
                }
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException && playbackLoadIsCurrent(loadGeneration, account, generation, activeSession, key, selected.value)) {
                    currentPlaybackQuality = previousPlaybackQuality
                    if (hasRemoteIdentity) updateRemotePlaceholder(song, previousPlaybackQuality.value)
                    showMessage("切换音质失败：${it.message ?: "网络错误"}")
                }
            }
            if (playbackLoadIsCurrent(loadGeneration, account, generation, activeSession, key)) playerBusy = false
        }
    }

    fun loadQualityOptions(song: Song) {
        qualityOptionsJob?.cancel()
        val requestGeneration = ++qualityOptionsGeneration
        if (!song.hasRemoteIdentity() || song.audioUri?.startsWith("file:", ignoreCase = true) == true) {
            qualityOptions = emptyList()
            qualityOptionsLoading = false
            qualityOptionsError = null
            return
        }
        val account = session.accountId ?: return
        val key = DesktopStorage.songKey(song)
        val generation = session.sessionGeneration
        qualityOptionsLoading = true
        qualityOptionsError = null
        qualityOptionsJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.requestQualities(song, expectedGeneration = generation) }
            }
            // 歌曲切换期间旧请求可能晚返回，只有当前歌曲仍相同才应用结果。
            if (requestGeneration != qualityOptionsGeneration ||
                !sessionIsCurrent(account, generation) ||
                currentSongNow()?.let { DesktopStorage.songKey(it) == key } != true
            ) return@launch
            result.onSuccess { options ->
                val normalizedOptions = normalizeDesktopQualityOptions(song, options)
                val supported = normalizedOptions.filter { option ->
                    DesktopPlayer.supportsQuality(song.source, option.quality) && option.quality in desktopSelectableQualityValues
                }
                if (supported.isEmpty()) {
                    qualityOptions = emptyList()
                    qualityOptionsError = if (options.any { it.quality == 18 } && song.source.equals("tencent", ignoreCase = true)) {
                        "这首歌仅提供腾讯 NAC 私有格式，Windows 暂不支持播放。"
                    } else {
                        "这首歌暂无可播放的 Windows 音质档位。"
                    }
                } else {
                    qualityOptions = supported
                    qualityOptionsError = if (song.source.equals("netease", ignoreCase = true) && options.any { it.quality == 18 }) {
                        "网易云接口将最高档统一标记为 18，桌面已映射为臻品母带。"
                    } else {
                        null
                    }
                }
            }.onFailure {
                qualityOptions = desktopQualityOptions()
                qualityOptionsError = "无法查询实际音质，以下为通用档位；服务端可能自动降级。"
            }
            if (requestGeneration == qualityOptionsGeneration && sessionIsCurrent(account, generation)) {
                qualityOptionsLoading = false
            }
        }
    }

    fun search(reset: Boolean = true) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            searchJob?.cancel()
            searchGeneration++
            searching = false
            searchError = null
            results = emptyList()
            searchPage = 1
            hasMore = false
            totalResults = 0
            return
        }
        searchJob?.cancel()
        val requestGeneration = ++searchGeneration
        val sessionGeneration = session.sessionGeneration
        val requestedSource = source
        val existingResults = if (reset) emptyList() else results
        if (reset) {
            results = emptyList()
            searchPage = 1
            hasMore = false
            totalResults = 0
        }
        searching = true
        searchError = null
        val requestedPage = if (reset) 1 else searchPage + 1
        searchJob = scope.launch {
            val uiContext = currentCoroutineContext()
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    api.search(
                        keyword = keyword,
                        page = requestedPage,
                        num = 60,
                        quality = playbackQuality.value,
                        source = requestedSource,
                        onProgress = { partial ->
                            withContext(uiContext) {
                                if (searchGeneration != requestGeneration) return@withContext
                                val candidate = existingResults + partial.filterNot { item -> existingResults.any { DesktopStorage.songKey(it) == DesktopStorage.songKey(item) } }
                                if (candidate.size >= results.size) results = candidate
                            }
                        },
                        expectedGeneration = sessionGeneration,
                    )
                }
            }
            if (searchGeneration != requestGeneration) return@launch
            outcome.onSuccess { result ->
                results = existingResults + result.songs.filterNot { item -> existingResults.any { DesktopStorage.songKey(it) == DesktopStorage.songKey(item) } }
                searchPage = result.page
                hasMore = result.hasMore
                totalResults = result.total
                if (reset) {
                    searchHistory = (listOf(keyword) + searchHistory.filterNot { it == keyword })
                        .take(DesktopStorage.MAX_SEARCH_HISTORY)
                    storage.saveSearchHistory(searchHistory)
                }
            }.onFailure { error -> if (error !is kotlinx.coroutines.CancellationException) searchError = error.message ?: "搜索失败" }
            searching = false
        }
    }

    fun syncAccountData() {
        val account = session.accountId ?: return
        val generation = session.sessionGeneration
        accountSyncJob?.cancel()
        val syncGeneration = ++accountSyncGeneration
        val localFavorites = favorites.values.toList()
        val localResults = results
        val localDownloaded = downloaded
        val localHistory = history
        val favoriteOperationsAtStart = favoriteOperationGenerations
        val favoriteMutationsAtStart = favoriteMutationGenerations
        val favoriteTargetsAtStart = favoriteMutationTargets
        accountSyncJob = scope.launch {
            runCatching { syncPendingPlayback(account) }
            if (!accountSyncIsCurrent(account, generation, syncGeneration)) return@launch
            val historyState = withContext(Dispatchers.IO) { runCatching { api.historyState(expectedGeneration = generation) }.getOrNull() }
            if (!accountSyncIsCurrent(account, generation, syncGeneration)) return@launch
            val syncedFavorites = withContext(Dispatchers.IO) {
                runCatching {
                    val keys = api.favoriteKeys(expectedGeneration = generation)
                    val known = localFavorites.flatMap { song -> remoteIdentityKeys(song).map { it to song } }.toMap()
                    val fetched = api.songsByKeys(keys - known.keys, expectedGeneration = generation)
                    val resolved = known + fetched.flatMap { song -> remoteIdentityKeys(song).map { it to song } }.toMap()
                    keys.mapNotNull { key ->
                        val identity = key.substringAfter(':')
                        resolved[key]?.preferRemoteIdentity(identity)
                    }.distinctBy(DesktopStorage::songKey)
                }.getOrNull()
            }
            if (!accountSyncIsCurrent(account, generation, syncGeneration)) return@launch
            val syncedHistory = withContext(Dispatchers.IO) {
                runCatching {
                    val records = api.recentPlayback(expectedGeneration = generation)
                    val knownBefore = (localResults + localFavorites + localDownloaded + localHistory.map { it.song })
                        .flatMap { song -> remoteIdentityKeys(song).map { it to song } }
                        .toMap()
                    val missing = records.map { DesktopMusicApi.key(it.source, it.songId) }
                        .filterNot { it in knownBefore }
                        .toSet()
                    val fetched = api.songsByKeys(missing, expectedGeneration = generation)
                        .flatMap { song -> remoteIdentityKeys(song).map { it to song } }
                        .toMap()
                    val known = knownBefore + fetched
                    records.mapNotNull { record ->
                        known[DesktopMusicApi.key(record.source, record.songId)]?.let { song ->
                            LocalHistory(
                                song = song.preferRemoteIdentity(record.songId),
                                playedAt = record.playedAt,
                                firstPlayedAt = record.firstPlayedAt,
                                playCount = record.playCount,
                                completedCount = record.completedCount,
                                totalListenedMs = record.totalListenedMs,
                                historyRevision = historyState?.revision ?: 0L,
                            )
                        }
                    }
                }.getOrNull()
            }
            if (!accountSyncIsCurrent(account, generation, syncGeneration)) return@launch
            historyState?.let { applyHistoryState(account, it) }
            syncedFavorites?.let { remoteFavorites ->
                val remoteByKey = remoteFavorites.associateBy { song -> DesktopStorage.songKey(song) }
                // 同步期间用户的乐观点击优先于旧快照；目标值与远端不一致时也要继续保护，
                // 直到服务端最终一致地观察到这次变更。
                val favoriteKeysAtApply = (favoriteOperationsAtStart.keys +
                    favoriteMutationsAtStart.keys +
                    favoriteTargetsAtStart.keys +
                    favoriteOperationGenerations.keys +
                    favoriteMutationGenerations.keys +
                    favoriteMutationTargets.keys).toSet()
                val changedKeys = favoriteKeysAtApply
                    .filter { key ->
                        favoriteOperationsAtStart[key] != favoriteOperationGenerations[key] ||
                            favoriteMutationsAtStart[key] != favoriteMutationGenerations[key] ||
                            favoriteMutationTargets[key] != favoriteTargetsAtStart[key] ||
                            favoriteMutationTargets[key]?.let { target -> (key in remoteByKey) != target } == true
                    }
                    .toSet()
                val merged = (remoteByKey - changedKeys) + favorites.filterKeys { it in changedKeys }
                favorites = merged
                storage.saveFavorites(merged.values)
                // 只有没有并发变更、且本次快照确实匹配目标值时才释放保护。
                favoriteMutationTargets = favoriteMutationTargets.filterKeys { key ->
                    val target = favoriteMutationTargets[key] ?: return@filterKeys false
                    val changedDuringSync = favoriteOperationsAtStart[key] != favoriteOperationGenerations[key] ||
                        favoriteMutationsAtStart[key] != favoriteMutationGenerations[key]
                    changedDuringSync || (key in remoteByKey) != target
                }
            }
            // recent/state 必须成对成功；state 失败时不知道服务端清空水位，不能覆盖本地未上传记录。
            val verifiedHistoryState = if (historyState != null) {
                withContext(Dispatchers.IO) { runCatching { api.historyState(expectedGeneration = generation) }.getOrNull() }
            } else {
                null
            }
            // 最后一次网络读取之后仍可能发生清空或退出；在计算合并快照前再核对代际，
            // 防止旧响应把刚清掉的最近播放重新写回本地。
            if (!accountSyncIsCurrent(account, generation, syncGeneration)) return@launch
            val historyConsistent = historyState != null && verifiedHistoryState != null &&
                historyState.revision == verifiedHistoryState.revision && historyState.clearedAt == verifiedHistoryState.clearedAt
            // 即使两次读取不一致，也先应用最后一次状态裁剪本地历史；只有一致时才合并
            // 最近播放条目，避免清空恰好发生在请求飞行期间时旧快照重新出现。
            verifiedHistoryState?.let { applyHistoryState(account, it) }
            if (historyConsistent) {
                val state = requireNotNull(verifiedHistoryState)
                syncedHistory?.let { remote ->
                    val localAfterClear = history.filter { item -> item.historyRevision >= state.revision }
                    val localAtApply = localAfterClear.associateBy { DesktopStorage.songKey(it.song) }
                    val localAtStart = localHistory.associateBy { DesktopStorage.songKey(it.song) }
                    val changedHistoryKeys = localAtApply.keys.filter { key ->
                        localAtApply[key] != localAtStart[key]
                    }.toSet()
                    val stableRemote = remote.filterNot { DesktopStorage.songKey(it.song) in changedHistoryKeys }
                    history = (localAtApply.values.filter { DesktopStorage.songKey(it.song) in changedHistoryKeys } +
                        stableRemote + localAtApply.values.filter { item ->
                            val key = DesktopStorage.songKey(item.song)
                            key !in changedHistoryKeys && stableRemote.none { DesktopStorage.songKey(it.song) == key }
                        }).distinctBy { DesktopStorage.songKey(it.song) }
                        .sortedByDescending { it.playedAt }
                        .take(DesktopStorage.MAX_HISTORY)
                    storage.saveHistory(history.map { it.toStorage() })
                }
            }
            if (accountSyncGeneration == syncGeneration) accountSyncJob = null
        }
    }

    fun clearAccountCaches() {
        searchJob?.cancel()
        searching = false
        searchError = null
        accountSyncJob?.cancel()
        accountSyncGeneration++
        playbackSyncEpoch++
        searchGeneration++
        qualityOptionsJob?.cancel()
        qualityOptionsGeneration++
        qualityOptions = emptyList()
        qualityOptionsLoading = false
        qualityOptionsError = null
        downloadQualityJob?.cancel()
        downloadQualityGeneration++
        downloadTarget = null
        downloadQualityOptions = emptyList()
        downloadQualityLoading = false
        downloadQualityError = null
        downloadTaskJobs.values.forEach(Job::cancel)
        downloadTaskJobs = emptyMap()
        downloadTaskTokens = emptyMap()
        downloadBusy = emptySet()
        downloadProgress = emptyMap()
        favorites = emptyMap()
        history = emptyList()
        favoriteBusy = emptySet()
        favoriteMutationGenerations = emptyMap()
        favoriteMutationTargets = emptyMap()
        favoriteOperationGenerations = emptyMap()
        query = ""
        results = emptyList()
        searchPage = 1
        hasMore = false
        totalResults = 0
        storage.saveFavorites(emptyList())
        storage.saveHistory(emptyList())
    }

    fun clearPlaybackForAccount(clearQueue: Boolean = false) {
        clearSleepTimerForPlaybackReset()
        currentSongNow()?.takeIf { playbackSessionId != null }?.let { persistPlaybackSnapshot(it) }
        playbackSyncEpoch++
        invalidatePlaybackLoad()
        lyricJob?.cancel()
        qualityOptionsJob?.cancel()
        qualityOptionsGeneration++
        player.stop()
        playbackSessionId = null
        playbackSessionAccountId = null
        playbackSessionSongKey = null
        playbackListenedMs = 0L
        historyRecordedMs = 0L
        historyAdmitted = false
        playbackCompleted = false
        if (clearQueue) {
            queue = emptyList()
            currentIndex = 0
            currentPositionMs = 0
            storage.clearQueue()
        } else {
            // 队列与下载属于设备级数据，退出账号时保留，登录另一账号后仍可播放本地歌曲。
            persistQueue()
        }
        durationMs = 0
        playing = false
        playerBusy = false
        playerError = null
        showPlayer = false
        showQueue = false
        lyric = Lyric.EMPTY
        lyricLoading = false
        lyricError = null
        currentPlaybackQuality = null
        qualityOptions = emptyList()
        qualityOptionsLoading = false
        qualityOptionsError = null
    }

    /** 系统媒体的 Stop 与 Pause 不同：清除当前媒体、队列和播放会话，和 Android 行为一致。 */
    fun stopPlayback() {
        clearPlaybackForAccount(clearQueue = true)
    }

    fun pausePlayback() {
        currentSongNow()?.takeIf { playbackSessionId != null }?.let { persistPlaybackSnapshot(it) }
        player.pause()
    }

    fun resumePlayback() {
        val song = currentSongNow() ?: return
        if (player.hasMedia) {
            if (playbackSessionId == null || playbackSessionSongKey != DesktopStorage.songKey(song)) {
                val sessionId = beginPlaybackTracking(song)
                refreshHistoryRevision(sessionId)
            }
            player.resume()
        } else {
            val restartPosition = if (durationMs > 0 && currentPositionMs >= durationMs - 500) 0 else currentPositionMs
            startSong(song, queue, currentIndex, restartPosition)
        }
    }

    fun togglePlayback() {
        if (playing) pausePlayback() else resumePlayback()
    }

    fun exitDesktop() {
        clearSleepTimerForPlaybackReset()
        currentSongNow()?.takeIf { playbackSessionId != null }?.let { persistPlaybackSnapshot(it) }
        persistQueue()
        onExit()
    }

    val systemMedia = remember { DesktopSystemMedia(storage.root) }
    SideEffect {
        systemMedia.callbacks = DesktopSystemCallbacks(
            onShow = { scope.launch { onShowWindow() } },
            onPlay = { scope.launch { resumePlayback() } },
            onPause = { scope.launch { pausePlayback() } },
            onStop = { scope.launch { stopPlayback() } },
            onNext = { scope.launch { nextSong() } },
            onPrevious = { scope.launch { previousSong() } },
            onSeek = { position -> scope.launch { player.seek(position); currentPositionMs = position } },
            onExit = { scope.launch { exitDesktop() } },
        )
    }
    DisposableEffect(systemMedia) {
        systemMedia.start()
        onDispose { systemMedia.close() }
    }
    LaunchedEffect(windowHiddenGeneration) {
        if (windowHiddenGeneration > 0L) systemMedia.notifyHidden()
    }
    LaunchedEffect(currentSong, playing, currentPositionMs, durationMs, repeatMode, volume) {
        systemMedia.update(currentSong, playing, currentPositionMs, durationMs, repeatMode, volume)
    }

    DisposableEffect(session) {
        session.onExpired = {
            scope.launch {
                clearPlaybackForAccount()
                clearAccountCaches()
                signedIn = false
                page = DesktopPage.MUSIC
            }
        }
        player.onStateChanged = { stateGeneration ->
            scope.launch {
                if (sleepTimerStopRequested && stateGeneration == sleepTimerStopStateGeneration) {
                    // player.stop() 会把位置/时长清零；定时停止要保留可恢复位置，忽略这次清理回调。
                    sleepTimerStopRequested = false
                    sleepTimerStopMediaGeneration = -1L
                    sleepTimerStopStateGeneration = -1L
                    playing = false
                    playerBusy = false
                    return@launch
                }
                if (player.mediaGeneration != stateGeneration) return@launch
                playing = player.isPlaying
                currentPositionMs = player.positionMs
                durationMs = player.durationMs
                player.error?.let { playerError = it }
            }
        }
        player.onEnded = { endedMediaGeneration, endedPositionMs, endedDurationMs ->
            scope.launch {
                // 播放器回调来自解码线程；所有 Compose 状态读取都放回当前协程（UI）线程。
                if (sleepTimerStopRequested && endedMediaGeneration == sleepTimerStopMediaGeneration) {
                    // 定时停止只结束当前媒体，不应沿用 repeatMode 自动切到下一首。
                    playing = false
                    playerBusy = false
                    return@launch
                }
                if (player.mediaGeneration != endedMediaGeneration) return@launch
                val endedSessionId = playbackSessionId
                val endedSongKey = currentSongNow()?.let(DesktopStorage::songKey)
                if (endedSessionId == null || playbackSessionSongKey != endedSongKey) return@launch
                // 回调可能早于 Compose 状态同步，结束统计以播放器的最终位置为准。
                currentPositionMs = endedPositionMs
                durationMs = endedDurationMs
                val ended = currentSongNow()
                if (ended != null && playbackSessionId != null) {
                    persistPlaybackSnapshot(ended, completed = true)
                    playbackSessionId = null
                    playbackSessionAccountId = null
                    playbackSessionSongKey = null
                }
                if (repeatMode == 2 || currentIndex + 1 < queue.size || repeatMode == 1) nextSong()
                else playing = false
            }
        }
        onDispose {
            currentSongNow()?.takeIf { playbackSessionId != null }?.let { persistPlaybackSnapshot(it) }
            persistQueue()
            player.release()
            session.onExpired = null
        }
    }

    LaunchedEffect(signedIn) {
        if (signedIn) {
            historyRevision = maxOf(loadHistoryRevision(storage, session.accountId), playbackOutbox.historyRevision(session.accountId))
            syncAccountData()
            refreshPlaylists(selectFirst = true)
            currentSongNow()?.let(::loadLyric)
        } else {
            historyRevision = 0L
            playlists = emptyList()
            selectedPlaylistId = null
            selectedPlaylist = null
        }
    }

    LaunchedEffect(currentSong?.source, currentSong?.remoteId, currentSong?.mid, currentSong?.type, currentSong?.audioUri) {
        currentSongNow()?.let {
            loadLyric(it)
            loadQualityOptions(it)
        } ?: run {
            lyric = Lyric.EMPTY
            lyricLoading = false
            lyricError = null
            qualityOptions = emptyList()
            qualityOptionsLoading = false
            qualityOptionsError = null
        }
    }

    LaunchedEffect(volume) {
        delay(300)
        storage.setSetting("volume", volume.toString())
    }

    LaunchedEffect(playbackSessionId, playing) {
        val activeSessionId = playbackSessionId ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        var previousTick = System.nanoTime()
        try {
            while (playing && playbackSessionId == activeSessionId) {
                delay(1_000)
                if (!playing || playbackSessionId != activeSessionId) break
                val now = System.nanoTime()
                val increment = ((now - previousTick) / 1_000_000L).coerceIn(0L, 2_000L)
                previousTick = now
                val before = playbackListenedMs
                playbackListenedMs += increment
                val crossedAdmission = before < HISTORY_ADMISSION_MS && playbackListenedMs >= HISTORY_ADMISSION_MS
                val crossedPeriodic = playbackListenedMs / PLAYBACK_SYNC_INTERVAL_MS > before / PLAYBACK_SYNC_INTERVAL_MS
                if (crossedAdmission || crossedPeriodic) {
                    currentSongNow()?.takeIf { playbackSessionSongKey == DesktopStorage.songKey(it) }
                        ?.let { persistPlaybackSnapshot(it) }
                }
            }
        } finally {
            if (playbackSessionId == activeSessionId && playbackListenedMs > 0L) {
                currentSongNow()?.takeIf { playbackSessionSongKey == DesktopStorage.songKey(it) }
                    ?.let { persistPlaybackSnapshot(it) }
            }
        }
    }

    // 只在实际播放时消耗定时额度；暂停会取消本轮 effect 并保留剩余时间，恢复时从剩余时间继续。
    LaunchedEffect(sleepTimerGeneration, playing) {
        if (!playing || sleepTimerRemainingMs <= 0L) return@LaunchedEffect
        val timerGeneration = sleepTimerGeneration
        var previousNanos = System.nanoTime()
        while (timerGeneration == sleepTimerGeneration && playing && sleepTimerRemainingMs > 0L) {
            delay(250L)
            if (timerGeneration != sleepTimerGeneration || !playing) break
            val now = System.nanoTime()
            val elapsedMs = ((now - previousNanos) / 1_000_000L).coerceIn(0L, 2_000L)
            previousNanos = now
            sleepTimerRemainingMs = sleepTimerRemainingAfterTick(
                remainingMs = sleepTimerRemainingMs,
                elapsedMs = elapsedMs,
                isPlaying = true,
            )
            if (sleepTimerRemainingMs == 0L && timerGeneration == sleepTimerGeneration && playing) {
                stopForSleepTimer()
                break
            }
        }
    }

    if (!signedIn) {
        DesktopTheme(darkTheme) {
            LoginPage(
                session = session,
                api = api,
                onSignedIn = { signedIn = true },
                onToggleTheme = {
                    darkTheme = !darkTheme
                    storage.setSetting("theme", if (darkTheme) "dark" else "light")
                },
            )
        }
        return
    }

    DesktopTheme(darkTheme) {
        DesktopShell(
            page = page,
            onPageChange = { page = it },
            query = query,
            onQueryChange = { query = it },
            source = source,
            onSourceChange = { source = it; if (query.isNotBlank()) search() },
            onSearch = { search() },
            searching = searching,
            results = results,
            searchError = searchError,
            totalResults = totalResults,
            hasMore = hasMore,
            onLoadMore = { if (!searching && hasMore) search(reset = false) },
            searchHistory = searchHistory,
            onHistorySearch = { query = it; search() },
            onHistoryRemove = { value ->
                searchHistory = searchHistory.filterNot { it == value }
                storage.saveSearchHistory(searchHistory)
            },
            onHistoryClear = {
                searchHistory = emptyList()
                storage.saveSearchHistory(emptyList())
            },
            favorites = favorites.values.toList(),
            history = history,
            downloaded = downloaded,
            downloadBusy = downloadBusy,
            favoriteBusy = favoriteBusy,
            currentSong = currentSong,
            queue = queue,
            currentIndex = currentIndex,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            playing = playing,
            playerBusy = playerBusy,
            playerError = playerError,
            lyric = lyric,
            lyricLoading = lyricLoading,
            lyricError = lyricError,
            qualityOptions = qualityOptions,
            qualityOptionsLoading = qualityOptionsLoading,
            qualityOptionsError = qualityOptionsError,
            downloadTarget = downloadTarget,
            downloadQualityOptions = downloadQualityOptions,
            downloadQualityLoading = downloadQualityLoading,
            downloadQualityError = downloadQualityError,
            repeatMode = repeatMode,
            volume = volume,
            showPlayer = showPlayer,
            showQueue = showQueue,
             playbackQuality = playbackQuality,
             currentPlaybackQuality = currentPlaybackQuality ?: playbackQuality,
             downloadQuality = downloadQuality,
             darkTheme = darkTheme,
             sleepTimerRemainingMs = sleepTimerRemainingMs,
             message = message,
            onPlay = { song, list, index -> startSong(song, list, index) },
            onPlayNext = ::playNext,
            onTogglePlaying = {
                if (!playerBusy) togglePlayback()
            },
            onNext = ::nextSong,
            onPrevious = ::previousSong,
            onSeek = { value -> player.seek(value); currentPositionMs = value },
            onRepeat = { repeatMode = (repeatMode + 1) % 3 },
            onVolumeChange = { value ->
                volume = value.coerceIn(0f, 1f)
                player.volume = volume
            },
            onOpenPlayer = { showPlayer = true },
            onClosePlayer = { showPlayer = false },
            onOpenQueue = { showQueue = true },
            onCloseQueue = { showQueue = false },
            onToggleFavorite = ::toggleFavorite,
            onShare = ::shareSong,
            onDownload = ::downloadSong,
            onOpenDownloadQuality = ::openDownloadQuality,
            onDownloadQualityPick = ::downloadSong,
             onDismissDownloadQuality = {
                 downloadTarget = null
                 downloadQualityJob?.cancel()
                 downloadQualityGeneration++
                 downloadQualityOptions = emptyList()
                downloadQualityLoading = false
                downloadQualityError = null
            },
            onRetryDownloadQuality = ::openDownloadQuality,
            downloadProgress = downloadProgress,
            playlists = playlists,
            selectedPlaylistId = selectedPlaylistId,
            selectedPlaylist = selectedPlaylist,
            playlistLoading = playlistLoading,
            playlistError = playlistError,
            playlistAvailableSongs = playlistAvailableSongs,
            apiEndpoint = api.endpoint,
            onQuality = ::switchQuality,
            onDeleteDownload = { song ->
                val key = DesktopStorage.songKey(song)
                val current = currentSongNow()?.let(DesktopStorage::songKey) == key
                if (current) {
                    clearSleepTimerForPlaybackReset()
                    invalidatePlaybackLoad()
                    currentSongNow()?.takeIf { playbackSessionId != null }?.let { persistPlaybackSnapshot(it) }
                    player.stop()
                    playbackSessionId = null
                    playbackSessionAccountId = null
                    playbackSessionSongKey = null
                    playing = false
                    playerBusy = false
                    currentPositionMs = 0
                    durationMs = 0
                    playerError = null
                    currentPlaybackQuality = null
                }
                // 文件删除可能要等待解码线程释放 Windows 句柄，不能阻塞 Compose/UI 线程。
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { downloads.delete(song) }
                    val refreshed = withContext(Dispatchers.IO) { downloads.list() }
                    downloaded = refreshed
                    if (deleted) {
                        // 下载目录删除后，队列中的歌曲元数据也要恢复为云端占位地址；否则
                        // 后续歌词/音质查询仍会把它当成 file: 本地歌曲，且播放页无法重新选档。
                        val queueIndex = queue.indexOfFirst { DesktopStorage.songKey(it) == key }
                        if (queueIndex >= 0) {
                            val queued = queue[queueIndex]
                            val restored = queued.copy(
                                audioUri = DesktopMusicApi.placeholderUri(queued, playbackQuality.value, api.endpoint),
                                coverUri = queued.coverUri?.takeUnless { it.startsWith("file:", ignoreCase = true) },
                                lyricUri = queued.lyricUri?.takeUnless { it.startsWith("file:", ignoreCase = true) },
                                lyricWordsUri = queued.lyricWordsUri?.takeUnless { it.startsWith("file:", ignoreCase = true) },
                                localQuality = null,
                            )
                            queue = queue.toMutableList().also { it[queueIndex] = restored }
                            persistQueue()
                        }
                    }
                    showMessage(if (deleted) (if (current) "已停止当前歌曲并删除本地文件" else "已删除本地文件") else "本地文件不存在或删除失败")
                }
            },
            onClearHistory = ::clearHistory,
            onRefreshPlaylists = { refreshPlaylists(selectFirst = true) },
            onSelectPlaylist = ::selectPlaylist,
            onCreatePlaylist = ::createPlaylist,
            onRenamePlaylist = ::renamePlaylist,
            onDeletePlaylist = ::deletePlaylist,
            onAddPlaylistSong = ::addPlaylistSong,
            onRemovePlaylistSong = ::removePlaylistSong,
            onReorderPlaylist = ::reorderPlaylist,
            onQueuePlay = { index -> queue.getOrNull(index)?.let { startSong(it, queue, index) } },
            onQueueRemove = { index -> if (index != currentIndex && index in queue.indices) { queue = queue.toMutableList().also { it.removeAt(index) }; if (index < currentIndex) currentIndex--; persistQueue() } },
            onQueueMove = { from, to -> if (from in queue.indices && to in queue.indices && from != to) { queue = queue.toMutableList().also { val item = it.removeAt(from); it.add(to, item) }; currentIndex = when { currentIndex == from -> to; from < currentIndex && to >= currentIndex -> currentIndex - 1; from > currentIndex && to <= currentIndex -> currentIndex + 1; else -> currentIndex }; persistQueue() } },
            onKeepCurrent = { currentSongNow()?.let { queue = listOf(it); currentIndex = 0; persistQueue() } },
             onSetPlaybackQuality = { quality -> playbackQuality = quality; storage.setSetting("playbackQuality", quality.value.toString()) },
             onSetDownloadQuality = { quality -> downloadQuality = quality; storage.setSetting("downloadQuality", quality.value.toString()) },
             onConfigureSleepTimer = ::configureSleepTimer,
             onCancelSleepTimer = { cancelSleepTimer() },
             onToggleTheme = { darkTheme = !darkTheme; storage.setSetting("theme", if (darkTheme) "dark" else "light") },
            onSignOut = {
                val refresh = session.clearLocalSession()
                clearPlaybackForAccount()
                clearAccountCaches()
                signedIn = false
                page = DesktopPage.MUSIC
                scope.launch(Dispatchers.IO) { session.revokeRefreshToken(refresh) }
            },
            onExit = ::exitDesktop,
        )
    }
}

@Composable
private fun DesktopTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    TaotaoPlayerTheme(darkTheme = darkTheme, content = content)
}

private fun readUri(uri: String): String? = runCatching {
    when {
        uri.startsWith("file:") -> File(java.net.URI(uri)).takeIf(File::isFile)?.readText()
        uri.startsWith("http") -> URL(uri).readText()
        else -> File(uri).takeIf(File::isFile)?.readText()
    }
}.getOrNull()

/** FFmpeg 后端覆盖 Android 暴露的五档音质；服务端降级到 0 档时显示为标准音质。 */
private fun desktopQuality(value: Int?): AudioQuality = when (value) {
    null -> AudioQuality.Default
    0, 1, 2, 3, 5, 6, 7 -> AudioQuality.STANDARD
    9 -> AudioQuality.HIGH
    12, 13 -> AudioQuality.MASTER
    15, 16, 17 -> AudioQuality.HIGH
    18 -> AudioQuality.MASTER
    else -> AudioQuality.of(value)
}

private fun desktopQualityOptions(): List<DesktopMusicApi.QualityOption> =
    AudioQuality.entries.map { DesktopMusicApi.QualityOption(it.value, it.label, 0L) }

/** 从持久化的播放占位地址恢复当前歌曲的临时音质；真实直链没有这个参数时返回 null。 */
private fun placeholderQuality(song: Song): Int? {
    val rawQuery = song.audioUri?.let { uri -> runCatching { java.net.URI(uri).rawQuery }.getOrNull() } ?: return null
    return rawQuery.split('&')
        .firstOrNull { it.substringBefore('=').equals("quality", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.toIntOrNull()
        ?.takeIf { it in 0..DesktopMusicApi.MAX_QUALITY }
}

/** 网易云没有逐档 info 接口，18 只是“最高档”标记；映射成桌面五档后仍由服务端按档位请求。 */
private fun normalizeDesktopQualityOptions(
    song: Song,
    options: List<DesktopMusicApi.QualityOption>,
): List<DesktopMusicApi.QualityOption> {
    if (!song.source.equals("netease", ignoreCase = true) || options.none { it.quality == 18 }) return options
    val highestSize = options.firstOrNull { it.quality == 18 }?.size ?: 0L
    return desktopQualityOptions().map { option ->
        option.copy(size = if (option.quality == AudioQuality.MASTER.value) highestSize else 0L)
    }
}

private fun historyRevisionKey(accountId: Long?): String = "historyRevision.${accountId ?: 0L}"

private fun loadHistoryRevision(storage: DesktopStorage, accountId: Long?): Long =
    storage.getSetting(historyRevisionKey(accountId), "0").toLongOrNull()?.coerceAtLeast(0L) ?: 0L

@Composable
internal fun AlbumCover(song: Song, modifier: Modifier = Modifier, size: Int = 54) {
    val source = song.coverUri
    val image by produceState<BufferedImage?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    source.isNullOrBlank() -> null
                    source.startsWith("file:") -> ImageIO.read(File(java.net.URI(source)))
                    source.startsWith("http") -> ImageIO.read(URL(source))
                    else -> ImageIO.read(File(source))
                }
            }.getOrNull()
        }
    }
    if (image != null) {
        androidx.compose.foundation.Image(image!!.toPainter(), "专辑封面", modifier.size(size.dp).clip(TaotaoShapes.medium), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size.dp).clip(TaotaoShapes.medium).background(Color(song.color)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, "专辑封面", tint = Color.White, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun LoginPage(
    session: DesktopSession,
    api: DesktopMusicApi,
    onSignedIn: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    var register by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var verificationBusy by remember { mutableStateOf(false) }
    var verificationStatus by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().padding(TaotaoSpacing.xxl), contentAlignment = Alignment.Center) {
            Card(modifier = Modifier.width(LoginCardWidth), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(TaotaoElevation.raised)) {
                Column(Modifier.padding(TaotaoSpacing.xxl), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(BrandMarkSize).clip(TaotaoShapes.medium).background(Coral), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Color.White) }
                        Column(Modifier.padding(start = TaotaoSpacing.sm).weight(1f)) {
                            Text("桃桃音乐", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Windows 音乐工作区", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = onToggleTheme) { Icon(Icons.Default.DarkMode, "切换主题") }
                    }
                    Divider()
                    Text(if (register) "创建账号" else "登录桃桃音乐", style = TaotaoTypeScale.sectionTitle, fontWeight = FontWeight.Bold)
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("用户名") }, leadingIcon = { Icon(Icons.Default.Person, null) })
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation())
                    if (register) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(email, { email = it }, Modifier.weight(1f), singleLine = true, label = { Text("邮箱") })
                            OutlinedButton(
                                onClick = {
                                    if (email.isBlank()) { error = "请先输入邮箱"; return@OutlinedButton }
                                    verificationBusy = true
                                    verificationStatus = null
                                    error = null
                                    scope.launch {
                                        val failure = withContext(Dispatchers.IO) {
                                            runCatching { api.sendRegistrationVerification(email.trim()) }.exceptionOrNull()
                                        }
                                        verificationBusy = false
                                        if (failure == null) verificationStatus = "验证码已发送，请检查邮箱"
                                        else error = failure.message ?: "验证码发送失败"
                                    }
                                },
                                enabled = !verificationBusy,
                                modifier = Modifier.padding(start = TaotaoSpacing.xs),
                            ) { Text(if (verificationBusy) "发送中" else "发送验证码") }
                        }
                        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("验证码") })
                        verificationStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    }
                    error?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodySmall) }
                    Button(
                        onClick = {
                            if (username.isBlank() || password.isBlank()) { error = "请输入用户名和密码"; return@Button }
                            if (register && (email.isBlank() || code.isBlank())) { error = "请输入邮箱和验证码"; return@Button }
                            busy = true; error = null
                            scope.launch {
                                val failure = withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (register) session.register(username.trim(), password, email.trim(), code.trim())
                                        else session.login(username.trim(), password)
                                    }.exceptionOrNull()
                                }
                                if (failure == null) onSignedIn() else error = failure.message ?: "请求失败"
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(LoginButtonHeight),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(TaotaoSizes.progressInline), strokeWidth = TaotaoStroke.medium, color = Color.White) else Icon(if (register) Icons.Default.Person else Icons.Default.Login, null)
                        Spacer(Modifier.width(TaotaoSpacing.xs)); Text(if (register) "注册并进入" else "登录")
                    }
                    TextButton(onClick = { register = !register; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (register) "已有账号？返回登录" else "没有账号？创建一个") }
                }
            }
        }
    }
}

/** 将最近播放条目写回本地，并保留服务端清空代际。 */
private fun LocalHistory.toStorage() = DesktopStorage.HistoryEntry(
    song = song,
    playedAt = playedAt,
    firstPlayedAt = firstPlayedAt,
    playCount = playCount,
    completedCount = completedCount,
    totalListenedMs = totalListenedMs,
    historyRevision = historyRevision,
)
