package com.taotao.music.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.taotao.music.model.Song
import android.net.Uri
import java.io.File

/** Android 音频播放封装，页面只通过 play/pause/stop 使用它。 */
class AudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var playing = false

    fun play(
        song: Song,
        queue: List<Song> = emptyList(),
        index: Int = 0,
        startPositionMs: Int = 0,
    ) {
        val uri = song.audioUri ?: return
        if (uri.startsWith("file:")) {
            val localFile = Uri.parse(uri).path?.let(::File)
            if (localFile?.isFile != true || localFile.length() < 4_096L) return
        }
        val queueHasAllAudio = queue.isNotEmpty() && queue.all { !it.audioUri.isNullOrBlank() }
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_PLAY
                    putExtra(PlaybackService.EXTRA_URI, uri)
                    putExtra(PlaybackService.EXTRA_TITLE, song.title)
                    putExtra(PlaybackService.EXTRA_ARTIST, song.artist)
                    putExtra(PlaybackService.EXTRA_ACCESS_TOKEN, tokenProvider())
                    putExtra(PlaybackService.EXTRA_START_POSITION, startPositionMs.coerceAtLeast(0))
                    putStringArrayListExtra(
                        PlaybackService.EXTRA_QUEUE_URI,
                        ArrayList(if (queueHasAllAudio) queue.mapNotNull { it.audioUri } else emptyList()),
                    )
                    putStringArrayListExtra(
                        PlaybackService.EXTRA_QUEUE_TITLE,
                        ArrayList(if (queueHasAllAudio) queue.map { it.title } else emptyList()),
                    )
                    putStringArrayListExtra(
                        PlaybackService.EXTRA_QUEUE_ARTIST,
                        ArrayList(if (queueHasAllAudio) queue.map { it.artist } else emptyList()),
                    )
                    putStringArrayListExtra(
                        PlaybackService.EXTRA_QUEUE_COVER,
                        ArrayList(if (queueHasAllAudio) queue.map { it.coverUri ?: "" } else emptyList()),
                    )
                    putExtra(PlaybackService.EXTRA_QUEUE_INDEX, if (queueHasAllAudio) index else 0)
                },
            )
            playing = true
        }.onFailure {
            playing = false
            PlaybackService.playError = it.message ?: "无法启动播放服务"
        }
    }

    fun pause() {
        if (PlaybackService.instance?.isPrepared() == true) {
            runCatching {
                appContext.startService(Intent(appContext, PlaybackService::class.java).setAction(PlaybackService.ACTION_PAUSE))
            }
            playing = false
        }
    }

    fun resume() {
        if (PlaybackService.instance?.isPrepared() == true) {
            runCatching {
                appContext.startService(Intent(appContext, PlaybackService::class.java).setAction(PlaybackService.ACTION_RESUME))
            }
            playing = true
        }
    }
    fun isPlaying(): Boolean = PlaybackService.instance?.let { it.isPlaying() || (playing && !it.isEnded()) } ?: playing
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

    private fun tokenProvider(): String? = appContext.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("access_token", null)
}
