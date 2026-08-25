package com.taotao.music.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.taotao.music.data.SongCodec
import com.taotao.music.model.Song
import java.io.File

/**
 * Android 音频播放封装，页面只通过 play/pause/resume 使用它。
 *
 * 通过 Media3 的 [MediaController] 连接 [PlaybackService]，而不是用 Intent 驱动服务：
 * MediaSessionService 只识别媒体按键和通知自定义动作，自定义 action 会被静默忽略，
 * 于是 startForegroundService 之后没有代码调用 startForeground，系统在超时后杀掉进程；
 * 而暂停或队列播完（STATE_ENDED）时 Media3 会主动 stopForeground，此后再次
 * startForegroundService 在后台还会被系统拒绝。MediaController 走 bindService，
 * 既能安全连接，也让 Media3 自行管理前台通知的生命周期。
 *
 * 播放状态由 [Player.Listener] 事件驱动并暴露为 Compose 状态，界面无需轮询播放器。
 * 播放请求的鉴权由 [PlaybackService] 在取流时自行处理。
 */
class AudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** 来自 Media3 状态机的整条队列结束监听器，后台播放结束时也会即时触发。 */
    private val completionListeners = linkedSetOf<(Song) -> Unit>()
    private var lastPlaybackState = Player.STATE_IDLE

    /** 连接完成前收到的播放请求，连接成功后立即补发。 */
    private var pendingCommand: ((MediaController) -> Unit)? = null

    /** 最近一次在主线程读到的播放进度，供非主线程调用时回退使用。 */
    private var lastKnownPositionMs = 0

    /**
     * 唯一的 UI 进度源。不能由播放详情页各自轮询：页面重建、最后一首结束后恢复播放时，
     * 页面自己的协程可能仍在写旧 State，造成音频正常播放但进度锁死。
     */
    var positionMs by mutableIntStateOf(0)
        private set

    private val progressTicker = object : Runnable {
        override fun run() {
            val activeController = controller ?: return
            if (activeController.isPlaying) {
                updateProgress(activeController)
                mainHandler.postDelayed(this, PROGRESS_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    /** 正在播放。 */
    var isPlaying by mutableStateOf(false)
        private set

    /** 播放器已装载队列且未处于空闲态，可以直接续播而不必重新取流。 */
    var hasMedia by mutableStateOf(false)
        private set

    /** 播放器当前所在的队列下标，用于让界面跟随上一首/下一首。 */
    var currentIndex by mutableIntStateOf(0)
        private set

    /** 当前曲目总时长，未知时为 0。 */
    var durationMs by mutableIntStateOf(0)
        private set

    /** 播放顺序由播放器持有，界面重建后仍与通知栏和后台服务保持一致。 */
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    /**
     * 每次真正从头开始的一轮播放都会递增。相同歌曲无法只靠 remoteId 区分“暂停恢复”与
     * “重播”，页面用此编号创建新统计会话；普通暂停/恢复不改变它。
     */
    var playbackCycle by mutableLongStateOf(0L)
        private set

    /**
     * 播放器里的完整队列。
     * 界面被销毁重建（而播放服务仍在后台播放）时，队列从这里恢复，
     * 不依赖磁盘缓存，因此不会出现「还在播但列表只剩一首」。
     */
    var queue by mutableStateOf(emptyList<Song>())
        private set

    /** 播放失败原因，界面提示一次后调用 [consumePlayError] 清空。 */
    var playError by mutableStateOf<String?>(null)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
        }

        /**
         * 单曲循环不会进入 STATE_ENDED，而是同一媒体项的自动位置跳转。把它视为一轮新的
         * 播放，避免多个完整循环共用同一个 sessionId；用户普通拖动进度不会触发该原因。
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                oldPosition.positionMs > 1_000L &&
                newPosition.positionMs <= 1_000L
            ) {
                queue.getOrNull(oldPosition.mediaItemIndex)?.let { completedSong ->
                    completionListeners.toList().forEach { listener -> runCatching { listener(completedSong) } }
                }
                playbackCycle += 1L
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            playError = error.message ?: "播放失败"
        }
    }

    /** 建立与播放服务的连接，应在界面进入时调用一次。 */
    fun connect() {
        if (!isMainThread()) {
            mainHandler.post(::connect)
            return
        }
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            // 显式绑定主线程，之后所有 controller 调用都必须来自主线程。
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrNull()
                if (connected == null) {
                    playError = "无法连接播放服务"
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                syncFrom(connected)
                pendingCommand?.let { command ->
                    pendingCommand = null
                    runCatching { command(connected) }
                        .onFailure { playError = it.message ?: "播放失败" }
                }
            },
            // MediaController 只能在创建它的线程上使用，这里固定回到主线程。
            mainHandler::post,
        )
    }

    fun play(
        song: Song,
        queue: List<Song> = emptyList(),
        index: Int = 0,
        startPositionMs: Int = 0,
        newPlaybackCycle: Boolean = true,
    ) {
        val uri = song.audioUri ?: return
        if (uri.startsWith("file:") && !isPlayableFile(uri)) return
        val queueHasAllAudio = queue.isNotEmpty() && queue.all { !it.audioUri.isNullOrBlank() }
        val songs = if (queueHasAllAudio) queue else listOf(song)
        val startIndex = if (queueHasAllAudio) index else 0
        val mediaItems = songs.map(::mediaItemOf)
        if (newPlaybackCycle) playbackCycle += 1L
        submit { activeController ->
            activeController.setMediaItems(
                mediaItems,
                startIndex.coerceIn(mediaItems.indices),
                startPositionMs.coerceAtLeast(0).toLong(),
            )
            activeController.prepare()
            activeController.play()
            playError = null
        }
    }

    fun pause() = submit { it.pause() }

    /**
     * 冷启动恢复：把队列装载进播放器并定位到上次的进度，但**不开始播放**。
     *
     * 装载后播放器进入 READY，界面立刻拿到时长和进度，点播放走 [resume] 即刻续播，
     * 不必重新解析播放地址（网络歌曲的鉴权由 PlaybackService 在取流时现取令牌）。
     *
     * `mediaItemCount` 的判断放在指令体内部，即在 controller 连接完成后才求值：
     * 播放服务仍在后台播放时会跳过装载，不会把正在播的队列覆盖掉。
     */
    fun prepareQueueIfIdle(songs: List<Song>, index: Int, positionMs: Int) {
        if (songs.isEmpty()) return
        val mediaItems = songs.map(::mediaItemOf)
        submit { activeController ->
            if (activeController.mediaItemCount > 0) return@submit
            activeController.setMediaItems(
                mediaItems,
                index.coerceIn(mediaItems.indices),
                positionMs.coerceAtLeast(0).toLong(),
            )
            activeController.prepare()
        }
    }

    /** 播放完成后从头重播当前歌曲，避免必须重新点击列表项。 */
    fun resume() = submit { activeController ->
        if (activeController.playbackState == Player.STATE_ENDED) {
            playbackCycle += 1L
            activeController.seekTo(0L)
        }
        activeController.play()
        updateProgress(activeController)
    }

    fun next() = submit { it.seekToNextMediaItem() }
    fun previous() = submit { it.seekToPreviousMediaItem() }
    fun updateRepeatMode(mode: Int) = submit { it.repeatMode = mode }

    fun addCompletionListener(listener: (Song) -> Unit) {
        completionListeners += listener
    }

    fun removeCompletionListener(listener: (Song) -> Unit) {
        completionListeners -= listener
    }

    /** 删除一首非当前歌曲；当前歌曲必须先切走，避免删除后播放指针跳到意外位置。 */
    fun removeQueueItem(index: Int) = submit { activeController ->
        if (index !in 0 until activeController.mediaItemCount) return@submit
        if (index == activeController.currentMediaItemIndex) return@submit
        activeController.removeMediaItem(index)
    }

    /** 调整播放列表顺序；Media3 保持当前播放项不变，并自动同步它的新下标。 */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = submit { activeController ->
        val count = activeController.mediaItemCount
        if (fromIndex !in 0 until count || toIndex !in 0 until count || fromIndex == toIndex) return@submit
        activeController.moveMediaItem(fromIndex, toIndex)
    }

    /** 清掉当前歌曲以外的项目，播放不中断，当前歌曲最终位于队列第 1 位。 */
    fun keepOnlyCurrent() = submit { activeController ->
        val current = activeController.currentMediaItemIndex
        val count = activeController.mediaItemCount
        if (current !in 0 until count) return@submit
        if (current + 1 < count) activeController.removeMediaItems(current + 1, count)
        if (current > 0) activeController.removeMediaItems(0, current)
    }

    /** 从播放队列里直接跳到某一首，供播放队列面板使用。 */
    fun playAt(index: Int) = submit { activeController ->
        if (index !in 0 until activeController.mediaItemCount) return@submit
        if (index == activeController.currentMediaItemIndex) playbackCycle += 1L
        activeController.seekToDefaultPosition(index)
        activeController.play()
    }

    /**
     * 将一首已具备播放地址的歌曲插入当前曲目的下一首。
     *
     * 只交给 Media3 改时间线，随后 [listener] 会把最新队列同步回 Compose 状态，避免 UI
     * 自己维护一份容易与通知栏脱节的副本。
     */
    fun addNext(song: Song) = submit { activeController ->
        val insertIndex = (activeController.currentMediaItemIndex + 1)
            .coerceIn(0, activeController.mediaItemCount)
        activeController.addMediaItem(insertIndex, mediaItemOf(song))
    }

    fun seekTo(positionMs: Int) = submit { activeController ->
        val duration = activeController.duration.takeIf { it > 0L } ?: return@submit
        activeController.seekTo(positionMs.toLong().coerceIn(0L, duration))
    }

    /**
     * 当前播放进度。MediaController 有线程亲和，只能在主线程访问，
     * 非主线程调用时返回最近一次已知进度而不是抛异常，避免调用方写错线程直接崩溃。
     */
    fun currentPositionMs(): Int {
        if (!isMainThread()) return lastKnownPositionMs
        controller?.let(::updateProgress)
        return lastKnownPositionMs
    }

    /** 取出并清空失败原因，供界面提示一次。 */
    fun consumePlayError(): String? = playError?.also { playError = null }

    /** 停止播放并清空队列，用于退出登录或会话失效。 */
    fun stop() = submit { activeController ->
        activeController.stop()
        activeController.clearMediaItems()
    }

    /** 断开与播放服务的连接，界面销毁时调用；播放中的服务会继续在后台运行。 */
    fun release() {
        if (!isMainThread()) {
            mainHandler.post(::release)
            return
        }
        controller?.removeListener(listener)
        controller = null
        pendingCommand = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        mainHandler.removeCallbacks(progressTicker)
        isPlaying = false
        hasMedia = false
        durationMs = 0
        positionMs = 0
    }

    /**
     * 下发一条播放指令：连接尚未完成时先记下最后一条，连接成功后补发。
     * 只保留最后一条，因为用户连续点击时只有最新的意图有意义。
     * 非主线程调用会被转到主线程，MediaController 不允许跨线程访问。
     */
    private fun submit(command: (MediaController) -> Unit) {
        if (!isMainThread()) {
            mainHandler.post { submit(command) }
            return
        }
        val activeController = controller
        if (activeController == null || !activeController.isConnected) {
            pendingCommand = command
            connect()
            return
        }
        runCatching { command(activeController) }
            .onFailure { playError = it.message ?: "播放操作失败" }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private companion object {
        /** MediaMetadata extras 中存放整首歌 JSON 的键。 */
        const val EXTRA_SONG = "com.taotao.music.SONG"
        const val PROGRESS_REFRESH_INTERVAL_MILLIS = 100L
    }

    private fun syncFrom(player: Player) {
        val playbackState = player.playbackState
        val restoredQueue = readQueue(player)
        val completedSong = restoredQueue.getOrNull(player.currentMediaItemIndex)
        val justCompleted = playbackState == Player.STATE_ENDED && lastPlaybackState != Player.STATE_ENDED
        lastPlaybackState = playbackState
        isPlaying = player.isPlaying
        hasMedia = player.mediaItemCount > 0 && playbackState != Player.STATE_IDLE
        currentIndex = player.currentMediaItemIndex
        durationMs = player.duration.takeIf { it > 0L }?.toInt() ?: 0
        updateProgress(player)
        mainHandler.removeCallbacks(progressTicker)
        if (player.isPlaying) mainHandler.post(progressTicker)
        repeatMode = player.repeatMode
        queue = restoredQueue
        if (justCompleted && completedSong != null) {
            completionListeners.toList().forEach { listener -> runCatching { listener(completedSong) } }
        }
    }

    private fun updateProgress(player: Player) {
        lastKnownPositionMs = player.currentPosition.coerceAtLeast(0L).toInt()
        positionMs = lastKnownPositionMs
        player.duration.takeIf { it > 0L }?.toInt()?.let { durationMs = it }
    }

    /** 从播放器时间线还原队列：每个 MediaItem 的 extras 里带着完整的 Song。 */
    private fun readQueue(player: Player): List<Song> {
        val count = player.mediaItemCount
        if (count == 0) return emptyList()
        val restored = (0 until count).mapNotNull { index ->
            val extras = player.getMediaItemAt(index).mediaMetadata.extras
            SongCodec.decode(extras?.getString(EXTRA_SONG))
        }
        // 解码不完整说明队列不是本应用写入的，保留原队列避免界面被清空。
        return if (restored.size == count) restored else queue
    }

    private fun mediaItemOf(song: Song): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album.takeIf { it.isNotBlank() })
            .setArtworkUri(song.coverUri?.takeIf { it.isNotBlank() }?.let(Uri::parse))
            // 队列需要能从播放器反查，把整首歌的字段一起带上。
            .setExtras(Bundle().apply { putString(EXTRA_SONG, SongCodec.encode(song)) })
            .build()
        return MediaItem.Builder()
            .setUri(song.audioUri)
            .setMediaId(song.remoteId?.toString() ?: song.audioUri.orEmpty())
            .setMimeType(mimeTypeOf(song.audioUri.orEmpty()))
            .setMediaMetadata(metadata)
            .build()
    }

    /** 离线文件可能下载中断，过小的文件直接跳过，避免播放器反复报错。 */
    private fun isPlayableFile(uri: String): Boolean {
        val localFile = Uri.parse(uri).path?.let(::File)
        return localFile?.isFile == true && localFile.length() >= 4_096L
    }

    private fun mimeTypeOf(uri: String): String? = when (uri.substringBefore('?').substringAfterLast('.').lowercase()) {
        "mp3" -> MimeTypes.AUDIO_MPEG
        "m4a" -> MimeTypes.AUDIO_MP4
        "aac" -> MimeTypes.AUDIO_AAC
        "flac" -> MimeTypes.AUDIO_FLAC
        "ogg" -> MimeTypes.AUDIO_OGG
        "wav" -> MimeTypes.AUDIO_WAV
        else -> null
    }
}
