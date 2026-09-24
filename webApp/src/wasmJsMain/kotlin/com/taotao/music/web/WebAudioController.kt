package com.taotao.music.web

import kotlinx.browser.document
import org.w3c.dom.HTMLAudioElement

/**
 * 浏览器试听播放器。
 *
 * 服务端只转发上游的**完整**音频，不做裁剪也不缓存，所以「最多 60 秒」这条限制
 * **完全由这里守**：到点必须自己绕回开头。
 *
 * 也正因为如此不能开 `audio.loop` —— 浏览器会节流后台标签页的定时回调，
 * 一旦漏掉那次检查，`loop` 会让它把整首歌循环播下去，而不是停在文件末尾。
 */
class WebAudioController(
    private val previewDurationSeconds: Int,
    private val onStateChanged: (WebAudioState) -> Unit,
) {
    private val audio = document.createElement("audio") as HTMLAudioElement

    init {
        // 只取元信息：试听只有 60 秒，没必要把整首歌先拉下来。
        audio.preload = "metadata"
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
        // ⚠️ 这里**刻意不加** `audio.onended` 之类的兜底回调。
        //
        // Kotlin/Wasm 每引用一个新的 `org.w3c.dom` 外部属性，就会在 wasm 里多出一条
        // `js_code` 导入，而这条导入必须由**同一批**构建出来的 JS 胶水提供。一旦浏览器
        // 里留下旧 JS、又拉到新 wasm，就会直接炸：
        // `LinkError: WebAssembly.instantiate(): Import #N "js_code"
        //  "org.w3c.dom.onended_$external_prop_setter": function import requires a callable`
        // （2026-09-21 实际踩到过）。60 秒上限由上面的 `ontimeupdate` 与 `seekTo` 的
        // 夹取一起守已经够用，不值得为一点兜底再扩大这个导入面。
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
