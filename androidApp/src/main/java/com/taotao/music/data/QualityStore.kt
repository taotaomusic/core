package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.MirroredPreference
import com.taotao.music.data.db.PREFERENCE_ROW_ID
import com.taotao.music.data.db.QualityRow
import com.taotao.music.data.db.TaotaoLocalDatabase
import com.taotao.music.model.AudioQuality

/**
 * 播放与下载的音质偏好。
 *
 * 播放和下载分开存:流量敏感的是播放,下载一次的体积反而愿意换更好的音质。
 * 无损一首约 55MB,默认播放档位刻意不选它。
 */
class QualityStore(context: Context) {
    private val mirror = object : MirroredPreference<QualityRow>(context, "quality") {
        override fun default() = QualityRow(PREFERENCE_ROW_ID, AudioQuality.Default.value, AudioQuality.LOSSLESS.value)
        override fun load(db: TaotaoLocalDatabase) = db.preferenceDao().getQuality()
        override fun persist(db: TaotaoLocalDatabase, value: QualityRow) {
            db.preferenceDao().upsertQuality(value)
        }
    }

    fun playbackQuality(): AudioQuality = AudioQuality.of(mirror.current().playback)

    fun setPlaybackQuality(quality: AudioQuality) = mirror.commit(mirror.current().copy(playback = quality.value))

    fun downloadQuality(): AudioQuality = AudioQuality.of(mirror.current().download)

    fun setDownloadQuality(quality: AudioQuality) = mirror.commit(mirror.current().copy(download = quality.value))
}
