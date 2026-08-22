package com.taotao.music.player

import android.content.Intent
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.taotao.music.data.AuthSession
import com.taotao.music.data.TencentMusicApi

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

    override fun onCreate() {
        super.onCreate()
        val authSession = AuthSession(this)
        val upstreamFactory = DefaultDataSource.Factory(
            this,
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true),
        )
        // 每次取流时现取访问令牌：长时间播放或队列续播时令牌可能已经轮换，不能沿用启动时的旧令牌。
        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val url = dataSpec.uri.toString()
            if (!TencentMusicApi.isOwnEndpoint(url)) return@Factory dataSpec
            val token = runCatching { authSession.validToken() }.getOrNull()
            if (token.isNullOrBlank()) dataSpec
            else dataSpec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
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
                    }
                })
            }
        player = createdPlayer
        mediaSession = MediaSession.Builder(this, createdPlayer).build()
        // Android 12 起后台启动前台服务可能被拒绝，注册监听避免 Media3 内部异常无人处理。
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w(TAG, "系统拒绝在后台启动前台播放服务，已停止播放")
                runCatching { player?.pause() }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 任务被划掉时若已暂停就结束服务，避免留下一个不再播放的常驻通知。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { mediaSession?.release() }
        runCatching { player?.release() }
        mediaSession = null
        player = null
        clearListener()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
