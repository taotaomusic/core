package com.taotao.music.player

import android.content.Intent
import android.net.Uri
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
import java.io.IOException

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
        val musicApi = TencentMusicApi(authSession)
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
            val placeholder = TencentMusicApi.parsePlaceholder(url)
                // 自家地址但不是占位地址：不该出现，附上令牌按原样放过去。
                ?: return@Factory authSession.validToken()?.takeIf { it.isNotBlank() }
                    ?.let { dataSpec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $it")) }
                    ?: dataSpec
            val direct = try {
                musicApi.resolveDirectUrl(placeholder.first, placeholder.second)
            } catch (error: Throwable) {
                Log.e(TAG, "解析直链失败：$url", error)
                throw IOException("无法获取播放地址：${error.message ?: "请稍后重试"}", error)
            }
            // 直链在 QQ 的 CDN 上，不需要也不应该带上我们的访问令牌。
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
