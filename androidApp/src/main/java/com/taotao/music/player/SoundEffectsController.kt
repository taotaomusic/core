@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taotao.music.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.taotao.music.audio.StereoEffectsParameters
import com.taotao.music.data.SoundEffectsStore

/** 音效能力状态；处理器实际工作在 Media3 音频线程。 */
data class SoundEffectsStatus(
    val sessionReady: Boolean = false,
    val equalizerAvailable: Boolean = false,
    val bassAvailable: Boolean = false,
    val spatialAvailable: Boolean = false,
    val reverbAvailable: Boolean = false,
) {
    fun putInto(extras: Bundle) {
        extras.putBoolean(SESSION_READY, sessionReady)
        extras.putBoolean(EQUALIZER_AVAILABLE, equalizerAvailable)
        extras.putBoolean(BASS_AVAILABLE, bassAvailable)
        extras.putBoolean(SPATIAL_AVAILABLE, spatialAvailable)
        extras.putBoolean(REVERB_AVAILABLE, reverbAvailable)
    }

    companion object {
        private const val SESSION_READY = "sound_effects.session_ready"
        private const val EQUALIZER_AVAILABLE = "sound_effects.equalizer_available"
        private const val BASS_AVAILABLE = "sound_effects.bass_available"
        private const val SPATIAL_AVAILABLE = "sound_effects.spatial_available"
        private const val REVERB_AVAILABLE = "sound_effects.reverb_available"

        fun from(extras: Bundle): SoundEffectsStatus = SoundEffectsStatus(
            sessionReady = extras.getBoolean(SESSION_READY),
            equalizerAvailable = extras.getBoolean(EQUALIZER_AVAILABLE),
            bassAvailable = extras.getBoolean(BASS_AVAILABLE),
            spatialAvailable = extras.getBoolean(SPATIAL_AVAILABLE),
            reverbAvailable = extras.getBoolean(REVERB_AVAILABLE),
        )
    }
}

/** 服务持有偏好和处理器，音频线程只消费不可变参数快照。 */
class SoundEffectsController(context: Context, private val onStatusChanged: (SoundEffectsStatus) -> Unit) {
    private val store = SoundEffectsStore(context)
    private val handler = Handler(Looper.getMainLooper())
    private var sessionId = 0
    private var pcmChannels = 0
    @Volatile private var released = false
    val audioProcessor = SoundEffectsAudioProcessor { channels ->
        handler.post {
            if (!released) {
                pcmChannels = channels
                publishStatus()
            }
        }
    }
    var status = SoundEffectsStatus()
        private set

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SoundEffectsStore.KEY) applySettings()
    }

    init {
        applySettings()
        store.observe(preferenceListener)
    }

    fun attach(audioSessionId: Int) {
        if (released) return
        sessionId = audioSessionId
        publishStatus()
    }

    private fun applySettings() {
        if (released) return
        val settings = store.read()
        audioProcessor.parameters = StereoEffectsParameters(
            enabled = settings.enabled,
            gains = settings.bandGains,
            bassStrength = settings.bassStrength,
            spatialStrength = settings.spatialStrength,
            reverbStrength = settings.reverbStrength,
        )
    }

    private fun publishStatus() {
        val ready = sessionId > 0
        val supported = ready && pcmChannels in 1..2
        status = SoundEffectsStatus(ready, supported, supported, supported && pcmChannels == 2, supported)
        onStatusChanged(status)
    }

    fun release() {
        released = true
        store.stopObserving(preferenceListener)
        handler.removeCallbacksAndMessages(null)
        audioProcessor.parameters = StereoEffectsParameters()
    }
}
