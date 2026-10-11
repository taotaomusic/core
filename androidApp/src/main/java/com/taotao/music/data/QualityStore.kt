package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.LocalKvStore
import com.taotao.music.model.AudioQuality

/**
 * 播放与下载的音质偏好。
 *
 * 播放和下载分开存:流量敏感的是播放,下载一次的体积反而愿意换更好的音质。
 * 无损一首约 55MB,默认播放档位刻意不选它。
 */
class QualityStore(context: Context) {
    private val kv = LocalKvStore.get(context)

    fun playbackQuality(): AudioQuality = AudioQuality.of(kv.getInt(KEY_PLAYBACK, AudioQuality.Default.value))

    fun setPlaybackQuality(quality: AudioQuality) = kv.putInt(KEY_PLAYBACK, quality.value)

    fun downloadQuality(): AudioQuality = AudioQuality.of(kv.getInt(KEY_DOWNLOAD, AudioQuality.LOSSLESS.value))

    fun setDownloadQuality(quality: AudioQuality) = kv.putInt(KEY_DOWNLOAD, quality.value)

    private companion object {
        const val KEY_PLAYBACK = "playback_quality.playback"
        const val KEY_DOWNLOAD = "playback_quality.download"
    }
}
