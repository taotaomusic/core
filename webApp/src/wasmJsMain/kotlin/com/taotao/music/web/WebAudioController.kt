package com.taotao.music.web

import kotlinx.browser.document
import org.w3c.dom.HTMLAudioElement

/** 浏览器试听播放器。服务端负责裁出 60 秒文件，这里再做一次时长上限防护。 */
class WebAudioController(
    private val previewDurationSeconds: Int,
    private val onStateChanged: (WebAudioState) -> Unit,
) {
    private val audio = document.createElement("audio") as HTMLAudioElement

    init {
        audio.preload = "metadata"
        audio.loop = true
        audio.onloadedmetadata = {
            publish(buffering = false)
            null
        }
        audio.ontimeupdate = {
            if (audio.currentTime >= previewDurationSeconds) {
                audio.currentTime = 0.0
                audio.play()
            }
            publish()
            null
        }
        audio.onplay = {
            publish(playing = true, buffering = false)
            null
        }
        audio.onpause = {
            publish(playing = false)
            null
        }
        audio.onwaiting = {
            publish(buffering = true)
            null
        }
        audio.onerror = { _, _, _, _, _ ->
            onStateChanged(currentState().copy(isBuffering = false, errorMessage = "试听加载失败，请稍后重试"))
            null
        }
    }

    fun load(url: String) {
        audio.src = url
        audio.load()
        publish(playing = false, buffering = true)
    }

    fun togglePlaying() {
        if (audio.paused) audio.play() else audio.pause()
    }

    fun seekTo(positionMs: Long) {
        audio.currentTime = (positionMs.coerceAtLeast(0L) / 1_000.0).coerceAtMost(previewDurationSeconds.toDouble())
        publish()
    }

    fun release() {
        audio.pause()
        audio.removeAttribute("src")
        audio.load()
    }

    private fun publish(
        playing: Boolean = !audio.paused,
        buffering: Boolean = false,
    ) {
        onStateChanged(currentState().copy(isPlaying = playing, isBuffering = buffering))
    }

    private fun currentState() = WebAudioState(
        isPlaying = !audio.paused,
        isBuffering = false,
        positionMs = (audio.currentTime * 1_000).toLong(),
        durationMs = previewDurationSeconds * 1_000L,
    )
}

data class WebAudioState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 60_000L,
    val errorMessage: String? = null,
)
