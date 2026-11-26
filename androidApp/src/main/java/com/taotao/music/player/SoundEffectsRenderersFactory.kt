@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taotao.music.player

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** 关闭音频直通，让解码后的 PCM 一定经过自研音效处理器。 */
class SoundEffectsRenderersFactory(
    context: Context,
    private val processor: SoundEffectsAudioProcessor,
) : DefaultRenderersFactory(context) {
    protected override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(processor))
        .build()
}
