package com.taotao.music.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.taotao.music.data.SoundEffectsStore

/** 实际音效能力由播放服务发布；没有音频会话时允许先保存设置。 */
data class SoundEffectsStatus(
    val sessionReady: Boolean = false,
    val equalizerAvailable: Boolean = false,
    val bassAvailable: Boolean = false,
) {
    fun putInto(extras: Bundle) {
        extras.putBoolean(SESSION_READY, sessionReady)
        extras.putBoolean(EQUALIZER_AVAILABLE, equalizerAvailable)
        extras.putBoolean(BASS_AVAILABLE, bassAvailable)
    }

    companion object {
        private const val SESSION_READY = "sound_effects.session_ready"
        private const val EQUALIZER_AVAILABLE = "sound_effects.equalizer_available"
        private const val BASS_AVAILABLE = "sound_effects.bass_available"

        fun from(extras: Bundle): SoundEffectsStatus = SoundEffectsStatus(
            sessionReady = extras.getBoolean(SESSION_READY),
            equalizerAvailable = extras.getBoolean(EQUALIZER_AVAILABLE),
            bassAvailable = extras.getBoolean(BASS_AVAILABLE),
        )
    }
}

/**
 * 音效绑定 ExoPlayer 的音频会话，由服务持有，关闭页面不会丢失。
 * 每项独立降级，设备不支持或被其他音效应用占用时不能打断音乐播放。
 */
class SoundEffectsController(context: Context, private val onStatusChanged: (SoundEffectsStatus) -> Unit) {
    private val store = SoundEffectsStore(context)
    private val handler = Handler(Looper.getMainLooper())
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var sessionId = 0
    private var released = false
    var status = SoundEffectsStatus()
        private set

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SoundEffectsStore.KEY) applySettings()
    }

    init {
        store.observe(preferenceListener)
    }

    fun attach(audioSessionId: Int) {
        if (released || sessionId == audioSessionId) return
        releaseEffects()
        sessionId = audioSessionId
        // 0 是全局混音会话，绝不能把本应用的音效附到其他应用上。
        if (sessionId > 0) {
            equalizer = runCatching { Equalizer(0, sessionId) }
                .onFailure { Log.w(TAG, "当前设备无法创建均衡器", it) }.getOrNull()
            bassBoost = runCatching { BassBoost(0, sessionId) }
                .onFailure { Log.w(TAG, "当前设备无法创建低音增强", it) }.getOrNull()
            listOfNotNull(equalizer, bassBoost).forEach { effect ->
                effect.setControlStatusListener { _, _ ->
                    handler.post { if (!released) applySettings() }
                }
            }
        }
        applySettings()
    }

    private fun applySettings() {
        if (released) return
        val settings = store.read()
        val equalizerAvailable = equalizer?.let { effect ->
            runCatching {
                check(effect.hasControl())
                val range = effect.bandLevelRange
                val bands = effect.numberOfBands.toInt()
                check(bands > 0 && range.size >= 2)
                for (band in 0 until bands) {
                    val frequency = effect.getCenterFreq(band.toShort()) / 1_000
                    effect.setBandLevel(
                        band.toShort(),
                        settings.gainAt(frequency).coerceIn(range[0].toInt(), range[1].toInt()).toShort(),
                    )
                }
                check(effect.setEnabled(settings.enabled) == AudioEffect.SUCCESS)
                true
            }.getOrElse {
                runCatching { effect.enabled = false }
                false
            }
        } ?: false
        val bassAvailable = bassBoost?.let { effect ->
            runCatching {
                check(effect.hasControl() && effect.strengthSupported)
                effect.setStrength(settings.bassStrength.toShort())
                check(effect.setEnabled(settings.enabled && settings.bassStrength > 0) == AudioEffect.SUCCESS)
                true
            }.getOrElse {
                runCatching { effect.enabled = false }
                false
            }
        } ?: false
        status = SoundEffectsStatus(sessionId > 0, equalizerAvailable, bassAvailable)
        onStatusChanged(status)
    }

    fun release() {
        released = true
        store.stopObserving(preferenceListener)
        handler.removeCallbacksAndMessages(null)
        releaseEffects()
    }

    private fun releaseEffects() {
        listOfNotNull(equalizer, bassBoost).forEach { effect ->
            runCatching { effect.setControlStatusListener(null) }
            runCatching { effect.release() }
        }
        equalizer = null
        bassBoost = null
    }

    private companion object {
        const val TAG = "SoundEffects"
    }
}
