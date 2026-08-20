package com.taotao.music.player

import android.content.Intent
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.database.ExoDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.net.Uri

/** Media3 系统媒体服务，负责后台播放、锁屏控制和系统媒体通知。 */
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var audioCache: SimpleCache
    private var currentCacheUri: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioCache = SimpleCache(
            java.io.File(cacheDir, "current_audio"),
            LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L),
            ExoDatabaseProvider(this),
        )
        val upstreamFactory = DefaultDataSource.Factory(this)
        val cachedDataSourceFactory = CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cachedDataSourceFactory),
            )
            .build()
            .apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playError = error.message ?: "播放失败"
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    removeCurrentCache()
                    currentCacheUri = mediaItem?.localConfiguration?.uri?.toString()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        removeCurrentCache()
                    }
                }
            })
        }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play(
                intent.getStringExtra(EXTRA_URI).orEmpty(),
                intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_URI).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_TITLE).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_ARTIST).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_COVER).orEmpty(),
                intent.getIntExtra(EXTRA_QUEUE_INDEX, 0),
            )
            ACTION_PAUSE -> player.pause()
            ACTION_RESUME -> resumePlayback()
        }
        return START_STICKY
    }

    private fun play(uri: String, title: String, artist: String, uris: List<String>, titles: List<String>, artists: List<String>, covers: List<String>, index: Int) {
        if (uri.isBlank()) return
        val mediaItems = if (uris.isNotEmpty() && uris.size == titles.size && uris.size == artists.size) {
            uris.mapIndexed { itemIndex, itemUri ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(titles[itemIndex])
                    .setArtist(artists[itemIndex])
                    .setArtworkUri(covers.getOrNull(itemIndex)?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build()
                MediaItem.Builder().setUri(itemUri).setMediaMetadata(metadata).build()
            }
        } else {
            listOf(MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).build()).build())
        }
        player.setMediaItems(mediaItems, index.coerceIn(mediaItems.indices), 0L)
        currentCacheUri = mediaItems[index.coerceIn(mediaItems.indices)].localConfiguration?.uri?.toString()
        player.prepare()
        player.play()
        playError = null
    }

    /** 播放完成后从头重播当前歌曲，避免必须重新点击列表项。 */
    private fun resumePlayback() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
        }
        player.play()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        removeCurrentCache()
        mediaSession.release()
        player.release()
        audioCache.release()
        instance = null
        super.onDestroy()
    }

    fun isPrepared(): Boolean = player.playbackState == Player.STATE_READY
    fun isPlaying(): Boolean = player.isPlaying
    fun positionMs(): Int = player.currentPosition.toInt()
    fun durationMs(): Int = player.duration.coerceAtLeast(0L).toInt()
    fun currentMediaItemIndex(): Int = player.currentMediaItemIndex
    fun next() { player.seekToNextMediaItem() }
    fun previous() { player.seekToPreviousMediaItem() }
    fun setRepeatMode(mode: Int) { player.repeatMode = mode }
    fun seekTo(positionMs: Int) { if (isPrepared()) player.seekTo(positionMs.toLong().coerceIn(0L, durationMs().toLong())) }

    private fun removeCurrentCache() {
        currentCacheUri?.let { uri ->
            runCatching { audioCache.removeResource(uri) }
        }
        currentCacheUri = null
    }

    companion object {
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
        const val EXTRA_QUEUE_URI = "queue_uri"
        const val EXTRA_QUEUE_TITLE = "queue_title"
        const val EXTRA_QUEUE_ARTIST = "queue_artist"
        const val EXTRA_QUEUE_COVER = "queue_cover"
        const val EXTRA_QUEUE_INDEX = "queue_index"
    }
}
