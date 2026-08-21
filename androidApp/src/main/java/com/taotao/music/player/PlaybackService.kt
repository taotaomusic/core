package com.taotao.music.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Media3 系统媒体服务，负责后台播放、锁屏控制和系统媒体通知。 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory

    @Volatile
    private var released = false

    override fun onCreate() {
        super.onCreate()
        released = false
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val createdPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(upstreamFactory))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playError = error.message ?: "播放失败"
                        Log.e(TAG, "音频播放失败", error)
                    }
                })
            }
        val createdSession = MediaSession.Builder(this, createdPlayer).build()
        player = createdPlayer
        mediaSession = createdSession
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (released) return START_NOT_STICKY
        runCatching {
            when (intent?.action) {
                ACTION_PLAY -> play(
                    intent.getStringExtra(EXTRA_URI).orEmpty(),
                    intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                    intent.getStringExtra(EXTRA_ACCESS_TOKEN),
                    intent.getIntExtra(EXTRA_START_POSITION, 0),
                    intent.getStringArrayListExtra(EXTRA_QUEUE_URI).orEmpty(),
                    intent.getStringArrayListExtra(EXTRA_QUEUE_TITLE).orEmpty(),
                    intent.getStringArrayListExtra(EXTRA_QUEUE_ARTIST).orEmpty(),
                    intent.getStringArrayListExtra(EXTRA_QUEUE_COVER).orEmpty(),
                    intent.getIntExtra(EXTRA_QUEUE_INDEX, 0),
                )
                ACTION_PAUSE -> player?.pause()
                ACTION_RESUME -> resumePlayback()
            }
        }.onFailure { error ->
            playError = error.message ?: "播放服务异常"
            Log.e(TAG, "播放服务处理请求失败", error)
        }

        // MediaSessionService 负责前台通知、媒体按键和系统媒体会话生命周期，不能绕过父类。
        return super.onStartCommand(intent, flags, startId)
    }

    private fun play(
        uri: String,
        title: String,
        artist: String,
        accessToken: String?,
        startPositionMs: Int,
        uris: List<String>,
        titles: List<String>,
        artists: List<String>,
        covers: List<String>,
        index: Int,
    ) {
        if (uri.isBlank()) return
        val currentPlayer = player ?: return
        if (!accessToken.isNullOrBlank()) {
            httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer $accessToken"))
        }
        val mediaItems = if (uris.isNotEmpty() && uris.size == titles.size && uris.size == artists.size) {
            uris.mapIndexed { itemIndex, itemUri ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(titles[itemIndex])
                    .setArtist(artists[itemIndex])
                    .setArtworkUri(covers.getOrNull(itemIndex)?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()
                MediaItem.Builder()
                    .setUri(itemUri)
                    .setMimeType(mimeTypeOf(itemUri))
                    .setMediaMetadata(metadata)
                    .build()
            }
        } else {
            listOf(
                MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(mimeTypeOf(uri))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build())
                .build(),
            )
        }
        currentPlayer.setMediaItems(
            mediaItems,
            index.coerceIn(mediaItems.indices),
            startPositionMs.coerceAtLeast(0).toLong(),
        )
        currentPlayer.prepare()
        currentPlayer.play()
        playError = null
    }

    /** 播放完成后从头重播当前歌曲，避免必须重新点击列表项。 */
    private fun resumePlayback() {
        val currentPlayer = player ?: return
        if (currentPlayer.playbackState == Player.STATE_ENDED) {
            currentPlayer.seekTo(0L)
        }
        currentPlayer.play()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        released = true
        instance = null
        runCatching { mediaSession?.release() }
        runCatching { player?.release() }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    fun isPrepared(): Boolean = !released && runCatching { player?.playbackState == Player.STATE_READY }.getOrDefault(false)
    fun isPlaying(): Boolean = !released && runCatching { player?.isPlaying == true }.getOrDefault(false)
    fun isEnded(): Boolean = released || runCatching { player?.playbackState == Player.STATE_ENDED }.getOrDefault(true)
    fun positionMs(): Int = if (released) 0 else runCatching { player?.currentPosition?.toInt() ?: 0 }.getOrDefault(0)
    fun durationMs(): Int = if (released) 0 else runCatching { player?.duration?.coerceAtLeast(0L)?.toInt() ?: 0 }.getOrDefault(0)
    fun currentMediaItemIndex(): Int = if (released) 0 else runCatching { player?.currentMediaItemIndex ?: 0 }.getOrDefault(0)
    fun next() { if (!released) runCatching { player?.seekToNextMediaItem() } }
    fun previous() { if (!released) runCatching { player?.seekToPreviousMediaItem() } }
    fun setRepeatMode(mode: Int) { if (!released) runCatching { player?.repeatMode = mode } }
    fun seekTo(positionMs: Int) {
        if (isPrepared()) {
            runCatching { player?.seekTo(positionMs.toLong().coerceIn(0L, durationMs().toLong())) }
        }
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

    companion object {
        private const val TAG = "PlaybackService"

        @Volatile
        var instance: PlaybackService? = null
        @Volatile
        var playError: String? = null
        const val ACTION_PLAY = "com.taotao.music.action.PLAY"
        const val ACTION_PAUSE = "com.taotao.music.action.PAUSE"
        const val ACTION_RESUME = "com.taotao.music.action.RESUME"
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_START_POSITION = "start_position_ms"
        const val EXTRA_QUEUE_URI = "queue_uri"
        const val EXTRA_QUEUE_TITLE = "queue_title"
        const val EXTRA_QUEUE_ARTIST = "queue_artist"
        const val EXTRA_QUEUE_COVER = "queue_cover"
        const val EXTRA_QUEUE_INDEX = "queue_index"
    }
}
