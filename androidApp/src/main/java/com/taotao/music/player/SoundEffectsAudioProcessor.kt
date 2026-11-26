@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taotao.music.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.taotao.music.audio.StereoEffectsParameters
import com.taotao.music.audio.StereoEffectsProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/** 解码后的 PCM 处理入口；开关和滑块变化无需重建播放器。 */
class SoundEffectsAudioProcessor(private val onFormatChanged: (Int) -> Unit) : BaseAudioProcessor() {
    @Volatile var parameters = StereoEffectsParameters()
    private var appliedParameters: StereoEffectsParameters? = null
    private var engine: StereoEffectsProcessor? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount in 1..2 && inputAudioFormat.sampleRate >= 8_000
        onFormatChanged(if (supported) inputAudioFormat.channelCount else 0)
        return if (supported) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush() {
        val format = inputAudioFormat
        engine = if (format.encoding == C.ENCODING_PCM_16BIT && format.channelCount in 1..2 && format.sampleRate >= 8_000) {
            StereoEffectsProcessor(format.sampleRate, format.channelCount)
        } else null
        scratch = if (engine != null) FloatArray(BLOCK_FRAMES * format.channelCount) else FloatArray(0)
        appliedParameters = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val processor = engine ?: return
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        require(inputBuffer.remaining() % (channels * 2) == 0) { "PCM 缓冲区必须包含完整帧" }
        inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val current = parameters
        if (appliedParameters !== current) {
            processor.updateParameters(current)
            appliedParameters = current
        }
        while (inputBuffer.hasRemaining()) {
            val frames = min(BLOCK_FRAMES, inputBuffer.remaining() / (channels * 2))
            val samples = frames * channels
            for (index in 0 until samples) scratch[index] = inputBuffer.short / 32768f
            processor.process(scratch, frames)
            for (index in 0 until samples) {
                output.putShort((scratch[index] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        output.flip()
    }

    override fun onReset() {
        engine = null
        scratch = FloatArray(0)
        appliedParameters = null
        onFormatChanged(0)
    }

    private companion object { const val BLOCK_FRAMES = 1_024 }
}
