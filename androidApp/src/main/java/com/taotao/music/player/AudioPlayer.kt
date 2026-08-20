package com.taotao.music.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.taotao.music.model.Song

/** Android 音频播放封装，页面只通过 play/pause/stop 使用它。 */
class AudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var playing = false

    fun play(song: Song, queue: List<Song> = emptyList(), index: Int = 0) {
        val uri = song.audioUri ?: return
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_PLAY
                putExtra(PlaybackService.EXTRA_URI, uri)
                putExtra(PlaybackService.EXTRA_TITLE, song.title)
                putExtra(PlaybackService.EXTRA_ARTIST, song.artist)
                putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_URI, ArrayList(queue.mapNotNull { it.audioUri }))
                putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_TITLE, ArrayList(queue.map { it.title }))
                putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_ARTIST, ArrayList(queue.map { it.artist }))
                putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_COVER, ArrayList(queue.map { it.coverUri ?: "" }))
                putExtra(PlaybackService.EXTRA_QUEUE_INDEX, index)
            },
        )
        playing = false
    }

    fun pause() {
        if (PlaybackService.instance?.isPrepared() == true) {
            appContext.startService(Intent(appContext, PlaybackService::class.java).setAction(PlaybackService.ACTION_PAUSE))
            playing = false
        }
    }

    fun resume() {
        if (PlaybackService.instance?.isPrepared() == true) {
            appContext.startService(Intent(appContext, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME))
            playing = true
        }
    }
    fun isPlaying(): Boolean = PlaybackService.instance?.isPlaying() == true
    fun currentPositionMs(): Int = PlaybackService.instance?.positionMs() ?: 0
    fun durationMs(): Int = PlaybackService.instance?.durationMs() ?: 0
    fun currentMediaItemIndex(): Int = PlaybackService.instance?.currentMediaItemIndex() ?: 0
    fun next() { PlaybackService.instance?.next() }
    fun previous() { PlaybackService.instance?.previous() }
    fun setRepeatMode(mode: Int) { PlaybackService.instance?.setRepeatMode(mode) }
    fun isPrepared(): Boolean = PlaybackService.instance?.isPrepared() == true
    fun seekTo(positionMs: Int) { PlaybackService.instance?.seekTo(positionMs) }
    fun release() {
        appContext.stopService(Intent(appContext, PlaybackService::class.java))
        playing = false
    }
}
