package com.taotao.music.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.taotao.music.R
import com.taotao.music.TaotaoApplication
import com.taotao.music.data.AuthSession
import com.taotao.music.data.PlaybackStateStore
import com.taotao.music.data.SongCodec
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/**
 * Media3 系统媒体服务，负责后台播放、锁屏控制和系统媒体通知。
 *
 * 服务不再处理自定义 Intent 动作：[MediaSessionService.onStartCommand] 只识别媒体按键
 * 和通知自定义动作，其它 action 会被静默忽略，导致调用方 startForegroundService 之后
 * 没有任何代码调用 startForeground，系统超时后直接杀进程。
 * 播放指令统一由 [AudioPlayer] 通过 MediaController 下发。
 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var musicApi: TencentMusicApi
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lyricExecutor = Executors.newSingleThreadExecutor()
    private var lyricRequestGeneration = 0L

    /** 定时器只在播放服务中倒计时，页面退出后仍能按时停止后台播放。 */
    private var sleepTimerRemainingMs = 0L
    private var sleepTimerLastTickMs = 0L

    /** 媒体面板「继续播放」的队列重建在后台线程做，避免 Room 读取卡主线程。 */
    private val resumptionExecutor = Executors.newSingleThreadExecutor()

    /** 「播完整首歌再停止」的开关，由界面经 SET / SET_FLAG 命令下发。 */
    private var sleepTimerWaitForSongEnd = false

    /** 定时已到期、正在等当前这首歌自然播完；此状态下剩余时长固定为 0。 */
    private var sleepTimerWaitingSongEnd = false
    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            tickSleepTimer()
        }
    }

    /** Media3 自定义命令：定时播放与媒体通知「收藏」按钮。 */
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SleepTimerContract.command)
                .add(FavoriteContract.command)
                .build()
            return MediaSession.ConnectionResult.accept(
                commands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                SleepTimerContract.COMMAND -> handleOnMain { handleSleepTimerCommand(args) }
                FavoriteContract.COMMAND -> handleOnMain {
                    // 收藏走进程级 appState 单例：与界面同一份收藏库和账号同步链路
                    // （本地即时生效，远端由 toggleFavorite 自己异步提交并处理失败回滚）。
                    player?.currentMediaItem?.let { item ->
                        SongCodec.decode(item.mediaMetadata.extras?.getString(EXTRA_SONG))?.let { song ->
                            TaotaoApplication.appState.toggleFavorite(song)
                        }
                    }
                    publishFavoriteLayout()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

        /** 统一把命令处理切回主线程，结果经 SettableFuture 回传控制器。 */
        private fun handleOnMain(handle: () -> SessionResult): ListenableFuture<SessionResult> {
            val result = SettableFuture.create<SessionResult>()
            mainHandler.post {
                result.set(runCatching { handle() }.getOrElse {
                    Log.w(TAG, "媒体自定义命令处理失败", it)
                    SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                })
            }
            return result
        }

        /**
         * 媒体面板「继续播放」（进程被系统回收后点播放键 / 恢复卡片）：用落盘的
         * 播放状态重建整条队列并定位到上次进度，交给 Media3 执行随后的播放指令。
         *
         * 队列重建走 [playbackMediaItemOf]（extras 带整首歌 JSON），界面重连后
         * 才能从时间线反查出队列。落盘状态缺位（从未播过 / 被登出清理）时返回
         * 失败 Future，Media3 会按无内容处理。
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            resumptionExecutor.execute {
                runCatching {
                    val saved = PlaybackStateStore(applicationContext).read()
                        ?: throw IllegalStateException("没有可恢复的播放状态")
                    val startIndex = saved.index.coerceIn(saved.queue.indices)
                    MediaSession.MediaItemsWithStartPosition(
                        saved.queue.map { playbackMediaItemOf(it) },
                        startIndex,
                        saved.positionMs.coerceAtLeast(0).toLong(),
                    )
                }.onSuccess { result.set(it) }
                    .onFailure { result.setException(it) }
            }
            return result
        }
    }

    override fun onCreate() {
        super.onCreate()
        val authSession = AuthSession(this)
        // 必须走 Application 的工厂：它带上进程级唯一的 CryptoTransport 与加密开关。
        // 此前这里直接 `TencentMusicApi(authSession, versionCode)`，两个加密参数都吃默认值
        // （null / false），于是取流时解析直链的 /api/v1/songs/:id/link 走明文，
        // 被服务端强制加密白名单以 426/{4007} 拒绝 —— 表现为能下载、能放本地文件，
        // 唯独在线播放不响，而队列里的占位地址看着完全正常。
        musicApi = TaotaoApplication.createMusicApi()
        val upstreamFactory = DefaultDataSource.Factory(
            this,
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
        )
        /**
         * 取流前把占位地址换成上游直链。
         *
         * 队列里存的是 `/api/v1/songs/{id}/play?quality=N` 这种永不过期的占位地址
         * （上游直链是限时的，存进队列后冷启动恢复时早就失效了）。真正的地址在这里、
         * 也就是 ExoPlayer 打开流的那一刻才解析,所以拿到的总是新鲜的。
         *
         * **解析失败直接抛异常，不回退到服务器代理。** 回退看着稳妥，实际是把音频流量
         * 悄悄绕回自己的服务器，还会把「直链解析坏了」这件事藏起来 —— 表现成播放正常、
         * 服务器流量莫名上涨。宁可让播放明确失败。
         *
         * 这个回调跑在 ExoPlayer 的 loader 线程上且是同步的,多一次请求会阻塞几百毫秒,
         * 那段时间正好落在播放器的缓冲状态里,界面上就是加载动画。
         */
        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val url = dataSpec.uri.toString()
            if (!TencentMusicApi.isOwnEndpoint(url)) return@Factory dataSpec
            val placeholder = TencentMusicApi.parsePlaceholderDetails(url)
                // 自家地址但不是占位地址：不该出现，附上令牌按原样放过去。
                ?: return@Factory authSession.validToken()?.takeIf { it.isNotBlank() }
                    ?.let { dataSpec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $it")) }
                    ?: dataSpec
            val direct = try {
                musicApi.resolveDirectUrl(placeholder)
            } catch (error: Throwable) {
                Log.e(TAG, "解析直链失败：$url", error)
                throw IOException("无法获取播放地址：${error.message ?: "请稍后重试"}", error)
            }
            // 直链在上游 CDN 上，不需要也不应该带上我们的访问令牌。
            dataSpec.withUri(Uri.parse(direct))
        }
        val createdPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            // 声明音乐用途，交由系统处理音频焦点；拔耳机时自动暂停。
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // 长时间后台播放需要持有唤醒锁，否则设备休眠后取流中断。
            // 先按本地档位初始化，切到网络歌曲时再升级为 WAKE_MODE_NETWORK。
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "音频播放失败", error)
                    }

                    /**
                     * 按当前曲目切换唤醒锁档位。
                     * WAKE_MODE_NETWORK 会额外持有 WifiLock，让 Wi-Fi 无法进入省电模式，
                     * 播放本地文件时这笔开销纯属浪费，因此只在取网络流时才升档。
                     */
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val uri = mediaItem?.localConfiguration?.uri?.scheme?.lowercase()
                        val needsNetwork = uri == "http" || uri == "https"
                        setWakeMode(if (needsNetwork) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
                        publishOplusLyrics(mediaItem)
                        // 切歌后收藏按钮的图标要跟上新歌的收藏状态。
                        publishFavoriteLayout()
                        // 等本歌播完时，只有自然播到下一首（含单曲循环转圈）才算兑现；
                        // 用户手动切歌不算，等新歌自然播完再停。
                        if (
                            sleepTimerWaitingSongEnd &&
                            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                        ) {
                            finishWaitingSongEnd()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateSleepTimerTicker()
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        // 外部媒体控制器可能直接清空队列；这种显式停止也应取消定时器，
                        // 否则服务会在没有媒体时继续驻留。
                        // 队列自然播完同样没有后续播放，定时器不应把服务一直挂住；
                        // 用户主动暂停则保留定时器，稍后恢复播放仍会继续倒计时。
                        when {
                            // 等本歌播完时倒计时已经归零，队列被清空或最后一首自然
                            // 播完都不会再触发切歌回调，这里兜底立即停止。
                            sleepTimerWaitingSongEnd -> {
                                if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
                                    finishWaitingSongEnd()
                                } else {
                                    updateSleepTimerTicker()
                                }
                            }
                            sleepTimerRemainingMs > 0L &&
                                (player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) -> cancelSleepTimer()
                            else -> updateSleepTimerTicker()
                        }
                    }
                })
            }
        player = createdPlayer
        mediaSession = MediaSession.Builder(this, createdPlayer)
            .setCallback(mediaSessionCallback)
            .setSessionExtras(sleepTimerExtras())
            .build()
        // 媒体通知的自定义「收藏」按钮：Android 13+ 媒体控件与各 ROM 媒体卡
        // （含荣耀流体云）都渲染 session custom layout；图标随当前歌曲的收藏
        // 状态切换，切歌与点按时刷新。
        publishFavoriteLayout()
        // Android 12 起后台启动前台服务可能被拒绝，注册监听避免 Media3 内部异常无人处理。
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w(TAG, "系统拒绝在后台启动前台播放服务，已停止播放")
                runCatching { player?.pause() }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 任务被划掉时，只有队列也空了才结束服务；暂停态保留常驻媒体通知，点播放直接续播。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        // 等本歌播完也算未完成的事：哪怕用户此刻暂停了，划掉任务也不该悄悄把歌掐了。
        // 暂停 + 有队列同样保留：媒体面板里的播放按钮要能一键续播（与其他音乐 App
        // 的常驻媒体行为一致）；不想看到通知时划掉通知即可，Media3 会随之结束服务。
        if (
            sleepTimerRemainingMs <= 0L &&
            !sleepTimerWaitingSongEnd &&
            (current == null || current.mediaItemCount == 0)
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        lyricRequestGeneration++
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerRemainingMs = 0L
        sleepTimerLastTickMs = 0L
        sleepTimerWaitForSongEnd = false
        sleepTimerWaitingSongEnd = false
        lyricExecutor.shutdownNow()
        resumptionExecutor.shutdownNow()
        runCatching { mediaSession?.release() }
        runCatching { player?.release() }
        mediaSession = null
        player = null
        clearListener()
        super.onDestroy()
    }

    /**
     * 异步取得当前歌曲的完整时间轴，再一次性发布到 MediaSession。
     *
     * 不在主线程请求歌词，也不按当前行高频刷新元数据；后者会让通知和 SystemUI 持续重建，
     * 在 AOD 场景既费电又容易触发 ColorOS 的媒体元数据防抖。
     */
    private fun publishOplusLyrics(mediaItem: MediaItem?) {
        val item = mediaItem ?: return
        if (item.mediaMetadata.extras?.containsKey(OplusLyricMetadata.EXTRA_LYRIC_INFO) == true) return
        val song = SongCodec.decode(item.mediaMetadata.extras?.getString(EXTRA_SONG)) ?: return
        val generation = ++lyricRequestGeneration
        lyricExecutor.execute {
            val lyricInfo = runCatching {
                val (lrc, yrc) = loadLyrics(song)
                OplusLyricMetadata.build(song, lrc, yrc)
            }.onFailure { error ->
                Log.w(TAG, "获取 ColorOS 锁屏歌词失败：${song.title}", error)
            }.getOrNull() ?: return@execute

            mainHandler.post {
                if (generation != lyricRequestGeneration) return@post
                val activePlayer = player ?: return@post
                val index = activePlayer.currentMediaItemIndex
                if (index !in 0 until activePlayer.mediaItemCount) return@post
                val current = activePlayer.getMediaItemAt(index)
                if (current.mediaId != item.mediaId) return@post
                val currentExtras = current.mediaMetadata.extras
                if (currentExtras?.getString(OplusLyricMetadata.EXTRA_LYRIC_INFO) == lyricInfo) return@post
                val updatedExtras = Bundle(currentExtras ?: Bundle.EMPTY).apply {
                    putString(OplusLyricMetadata.EXTRA_LYRIC_INFO, lyricInfo)
                }
                val updatedMetadata = current.mediaMetadata.buildUpon()
                    .setExtras(updatedExtras)
                    .build()
                activePlayer.replaceMediaItem(
                    index,
                    current.buildUpon().setMediaMetadata(updatedMetadata).build(),
                )
            }
        }
    }

    private fun loadLyrics(song: Song): Pair<String?, String?> {
        if (song.lyricUri?.startsWith("file:") == true) {
            val lrc = fileText(song.lyricUri)
            val yrc = fileText(song.lyricWordsUri)
            return lrc to yrc
        }
        if (song.remoteId?.let { it > 0L } != true && song.mid.isNullOrBlank()) return null to null
        val rich = musicApi.requestRichLyric(song)
        return rich.lrc to rich.yrc
    }

    /** 处理来自应用内 AudioPlayer 的定时器命令。 */
    private fun handleSleepTimerCommand(args: Bundle): SessionResult {
        return when (args.getString(SleepTimerContract.OPERATION)) {
            SleepTimerContract.SET -> {
                val durationMs = args.getLong(SleepTimerContract.DURATION_MS, -1L)
                if (durationMs !in 1L..SleepTimerContract.MAX_DURATION_MS) {
                    SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    setSleepTimer(durationMs, args.getBoolean(SleepTimerContract.WAIT_FOR_SONG_END))
                    SessionResult(SessionResult.RESULT_SUCCESS, sleepTimerExtras())
                }
            }

            SleepTimerContract.SET_FLAG -> {
                // 只改「播完整首再停」的开关，不动正在进行的倒计时。
                sleepTimerWaitForSongEnd = args.getBoolean(SleepTimerContract.WAIT_FOR_SONG_END)
                publishSleepTimerState()
                SessionResult(SessionResult.RESULT_SUCCESS, sleepTimerExtras())
            }

            SleepTimerContract.CANCEL -> {
                cancelSleepTimer()
                SessionResult(SessionResult.RESULT_SUCCESS, sleepTimerExtras())
            }

            SleepTimerContract.QUERY, null -> {
                publishSleepTimerState()
                SessionResult(SessionResult.RESULT_SUCCESS, sleepTimerExtras())
            }

            else -> SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
        }
    }

    /** 设置新的总时长；重新设置会从当前时刻重新计时，并清掉等待播完的状态。 */
    private fun setSleepTimer(durationMs: Long, waitForSongEnd: Boolean) {
        sleepTimerRemainingMs = durationMs.coerceIn(1L, SleepTimerContract.MAX_DURATION_MS)
        sleepTimerLastTickMs = SystemClock.elapsedRealtime()
        sleepTimerWaitForSongEnd = waitForSongEnd
        sleepTimerWaitingSongEnd = false
        publishSleepTimerState()
        updateSleepTimerTicker()
    }

    /** 显式取消定时器，并通知所有已连接的控制器刷新显示。 */
    private fun cancelSleepTimer() {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerRemainingMs = 0L
        sleepTimerLastTickMs = 0L
        sleepTimerWaitForSongEnd = false
        sleepTimerWaitingSongEnd = false
        publishSleepTimerState()
    }

    /**
     * 只累计实际播放时间：暂停期间保留剩余时长，切歌不重置定时器。
     * 使用 elapsedRealtime 而不是 wall clock，避免用户修改系统时间造成提前停止。
     */
    private fun tickSleepTimer() {
        if (sleepTimerRemainingMs <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - sleepTimerLastTickMs).coerceIn(0L, SleepTimerContract.MAX_DURATION_MS)
        sleepTimerLastTickMs = now
        if (player?.isPlaying != true) {
            // 暂停时不递减，也不继续每秒唤醒服务；恢复播放会由
            // onIsPlayingChanged 重新安排下一次 tick。
            sleepTimerLastTickMs = 0L
            publishSleepTimerState()
            return
        }
        sleepTimerRemainingMs = SleepTimerPolicy.remainingAfterTick(
            remainingMs = sleepTimerRemainingMs,
            elapsedMs = elapsed,
            isPlaying = true,
        )
        if (sleepTimerRemainingMs <= 0L) {
            expireSleepTimer()
        } else {
            publishSleepTimerState()
            mainHandler.postDelayed(sleepTimerRunnable, SLEEP_TIMER_TICK_MS)
        }
    }

    /**
     * 播放到期：默认立刻停止；开了「播完整首歌再停止」且确实在放歌时，
     * 先进入等待态，由切歌回调或队列播完事件兑现停止。
     */
    private fun expireSleepTimer() {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerRemainingMs = 0L
        sleepTimerLastTickMs = 0L
        val activePlayer = player
        if (
            sleepTimerWaitForSongEnd &&
            activePlayer != null &&
            activePlayer.isPlaying &&
            activePlayer.playbackState != Player.STATE_ENDED
        ) {
            sleepTimerWaitingSongEnd = true
            publishSleepTimerState()
            return
        }
        stopPlaybackForSleepTimer()
    }

    /** 等待期满：当前歌已自然播完，立即兑现停止。 */
    private fun finishWaitingSongEnd() {
        stopPlaybackForSleepTimer()
    }

    /** 真正停止 ExoPlayer、清空媒体队列并结束服务；到期与等播完两条路径共用。 */
    private fun stopPlaybackForSleepTimer() {
        sleepTimerWaitingSongEnd = false
        publishSleepTimerState()
        player?.let { activePlayer ->
            runCatching {
                activePlayer.stop()
                activePlayer.clearMediaItems()
            }.onFailure { error -> Log.w(TAG, "定时停止播放器失败", error) }
        }
        stopSelf()
    }

    /** 根据当前播放态决定是否继续调度倒计时。 */
    private fun updateSleepTimerTicker() {
        if (sleepTimerRemainingMs <= 0L) {
            mainHandler.removeCallbacks(sleepTimerRunnable)
            return
        }
        val activePlayer = player
        if (activePlayer?.isPlaying != true) {
            // 暂停期间不调度空转任务；恢复播放时再以恢复时刻作为新的计时锚点，
            // 因而暂停多久都不会消耗定时器剩余时长。
            mainHandler.removeCallbacks(sleepTimerRunnable)
            sleepTimerLastTickMs = 0L
            return
        }
        if (sleepTimerLastTickMs <= 0L) sleepTimerLastTickMs = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(sleepTimerRunnable)
        mainHandler.postDelayed(sleepTimerRunnable, SLEEP_TIMER_TICK_MS)
    }

    private fun sleepTimerExtras(): Bundle = Bundle().apply {
        putBoolean(SleepTimerContract.ACTIVE, sleepTimerRemainingMs > 0L)
        putLong(SleepTimerContract.REMAINING_MS, sleepTimerRemainingMs.coerceAtLeast(0L))
        putBoolean(SleepTimerContract.WAITING_SONG_END, sleepTimerWaitingSongEnd)
    }

    private fun publishSleepTimerState() {
        mediaSession?.setSessionExtras(sleepTimerExtras())
    }

    /** 当前歌曲的收藏状态：经进程级 appState 单例，与界面共用同一份收藏库。 */
    private fun currentSongFavorited(): Boolean {
        val song = player?.currentMediaItem?.let {
            SongCodec.decode(it.mediaMetadata.extras?.getString(EXTRA_SONG))
        } ?: return false
        return TaotaoApplication.appState.favoritesStore.contains(song)
    }

    /** 把媒体通知自定义「收藏」按钮的图标刷成当前歌曲的收藏状态。 */
    private fun publishFavoriteLayout() {
        val session = mediaSession ?: return
        val favoriteButton = CommandButton.Builder()
            .setDisplayName("收藏")
            .setIconResId(
                if (currentSongFavorited()) R.drawable.ic_media_heart_filled
                else R.drawable.ic_media_heart_border,
            )
            .setSessionCommand(FavoriteContract.command)
            .build()
        session.setCustomLayout(ImmutableList.of(favoriteButton))
    }

    private fun fileText(uri: String?): String? = uri
        ?.let(Uri::parse)
        ?.path
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?.readText()

    private companion object {
        const val TAG = "PlaybackService"
        const val SLEEP_TIMER_TICK_MS = 1_000L
    }
}
