package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.AudioQuality

/**
 * 播放与下载的音质偏好。
 *
 * 播放和下载分开存:流量敏感的是播放,下载一次的体积反而愿意换更好的音质。
 * 无损一首约 55MB,默认播放档位刻意不选它。
 */
class QualityStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_quality", Context.MODE_PRIVATE)

    fun playbackQuality(): AudioQuality = AudioQuality.of(preferences.getInt(KEY_PLAYBACK, AudioQuality.Default.value))

    fun setPlaybackQuality(quality: AudioQuality) =
        preferences.edit().putInt(KEY_PLAYBACK, quality.value).apply()

    fun downloadQuality(): AudioQuality =
        AudioQuality.of(preferences.getInt(KEY_DOWNLOAD, AudioQuality.LOSSLESS.value))

    fun setDownloadQuality(quality: AudioQuality) =
        preferences.edit().putInt(KEY_DOWNLOAD, quality.value).apply()

    private companion object {
        const val KEY_PLAYBACK = "playback"
        const val KEY_DOWNLOAD = "download"
    }
}
