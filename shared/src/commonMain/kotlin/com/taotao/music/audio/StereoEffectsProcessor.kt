package com.taotao.music.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 独立于平台的 PCM 音效参数；增益单位是毫贝，强度范围为 0..1000。 */
data class StereoEffectsParameters(
    val enabled: Boolean = false,
    val gains: List<Int> = List(10) { 0 },
    val bassStrength: Int = 0,
    val spatialStrength: Int = 0,
    val reverbStrength: Int = 0,
)

/**
 * 自研 PCM 处理链：十段均衡 → 低频棚架 → 中侧声场 → 小型 Schroeder 混响。
 *
 * 只在音频线程调用。参数更新和重置可以分配内存，逐帧处理不创建对象。
 * 停用或全零参数会精确旁路；启用与正在使用的参数变化通过约 20 ms 斜坡过渡。
 * 声场展宽只处理立体声，不能从单声道恢复原本不存在的空间信息。
 */
class StereoEffectsProcessor(val sampleRate: Int, val channelCount: Int) {
    init {
        require(sampleRate in 1_000..384_000) { "不支持的 PCM 采样率：$sampleRate" }
        require(channelCount == 1 || channelCount == 2) { "PCM 音效仅支持单声道和立体声" }
    }

    private val transitionFrames = max(1, sampleRate / 50)
    private val filters = Array(FREQUENCIES.size + 1) { Biquad() }
    private val reverbLeft = Reverb(sampleRate, 0)
    private val reverbRight = Reverb(sampleRate, 23)
    private val activation = Ramp(0.0)
    private val preamp = Ramp(1.0)
    private val width = Ramp(1.0)
    private val wet = Ramp(0.0)
    private var bypassed = true
    private var reverbWasActive = false

    /** 十段标准倍频程控制点；低采样率下超过可用频率的控制点自动旁路。 */
    fun updateParameters(parameters: StereoEffectsParameters) {
        val gains = IntArray(FREQUENCIES.size) { index ->
            if (FREQUENCIES[index] < sampleRate * 0.45) {
                (parameters.gains.getOrNull(index) ?: 0).coerceIn(-1_200, 1_200)
            } else {
                0
            }
        }
        val bass = parameters.bassStrength.coerceIn(0, 1_000)
        val spatial = if (channelCount == 2) parameters.spatialStrength.coerceIn(0, 1_000) else 0
        val reverb = parameters.reverbStrength.coerceIn(0, 1_000)
        val active = parameters.enabled && (gains.any { it != 0 } || bass != 0 || spatial != 0 || reverb != 0)
        if (!active) {
            bypassed = true
            reset()
            return
        }
        if (bypassed) {
            reset()
            activation.jumpTo(0.0)
            activation.moveTo(1.0, transitionFrames)
        }
        bypassed = false
        for (index in FREQUENCIES.indices) {
            val frequency = FREQUENCIES[index].toDouble()
            filters[index].setCoefficients(
                if (frequency < sampleRate * 0.45) peaking(frequency, gains[index] / 100.0) else IDENTITY,
                transitionFrames,
            )
        }
        val bassDb = bass * 0.009
        filters.last().setCoefficients(lowShelf(120.0.coerceAtMost(sampleRate * 0.2), bassDb), transitionFrames)
        // 为相邻均衡频段叠加以及低音棚架保留余量，最终再以联动限幅防止左右声道削波。
        val boostDb = max(0, gains.maxOrNull() ?: 0) / 100.0 + bassDb
        val headroomDb = if (boostDb > 0.0) boostDb + 3.0 else 0.0
        preamp.moveTo(10.0.pow(-headroomDb / 20.0), transitionFrames)
        width.moveTo(1.0 + spatial / 1_000.0, transitionFrames)
        wet.moveTo(reverb * 0.0003, transitionFrames)
    }

    /** 原地处理交错 PCM；末尾不足一帧的数据与 frameCount 之外的数据保持不变。 */
    fun process(samples: FloatArray, frameCount: Int = samples.size / channelCount) {
        require(frameCount >= 0 && frameCount <= samples.size / channelCount) { "PCM 帧数越界" }
        if (bypassed || frameCount == 0) return
        var offset = 0
        repeat(frameCount) {
            val dryLeft = samples[offset].finiteOrZero()
            val dryRight = if (channelCount == 2) samples[offset + 1].finiteOrZero() else dryLeft
            val gain = preamp.next()
            var left = dryLeft * gain
            var right = dryRight * gain
            for (filter in filters) {
                filter.advance()
                left = filter.process(left, 0)
                if (channelCount == 2) right = filter.process(right, 1)
            }
            val stereoWidth = width.next()
            if (channelCount == 2) {
                val mid = (left + right) * 0.5
                val side = (left - right) * 0.5 * stereoWidth
                left = mid + side
                right = mid - side
            }
            val reverbMix = wet.next()
            if (reverbMix > 0.0) {
                reverbWasActive = true
                val input = if (channelCount == 2) (left + right) * 0.5 else left
                left = left * (1.0 - reverbMix) + reverbLeft.process(input) * reverbMix
                if (channelCount == 2) right = right * (1.0 - reverbMix) + reverbRight.process(input) * reverbMix
            } else if (reverbWasActive) {
                reverbLeft.reset()
                reverbRight.reset()
                reverbWasActive = false
            }
            // 联动限幅保留左右声道比例；异常输入不能污染 IIR 或混响的后续状态。
            if (!left.isFinite() || !right.isFinite()) {
                reset()
                left = 0.0
                right = 0.0
            }
            val peak = max(1.0, max(abs(left), abs(right)))
            val mix = activation.next()
            samples[offset] = (dryLeft + (left / peak - dryLeft) * mix).coerceIn(-1.0, 1.0).toFloat()
            if (channelCount == 2) {
                samples[offset + 1] = (dryRight + (right / peak - dryRight) * mix).coerceIn(-1.0, 1.0).toFloat()
            }
            offset += channelCount
        }
    }

    /** 切歌、拖动进度或刷新音轨时清掉滤波历史，避免上一段混响进入新音轨。 */
    fun reset() {
        for (filter in filters) filter.reset()
        reverbLeft.reset()
        reverbRight.reset()
        reverbWasActive = false
        activation.finish()
        preamp.finish()
        width.finish()
        wet.finish()
    }

    private fun peaking(frequency: Double, gainDb: Double): DoubleArray {
        if (gainDb == 0.0) return IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val alpha = sin(omega) / (2.0 * 1.4)
        val divisor = 1.0 + alpha / a
        return doubleArrayOf(
            (1.0 + alpha * a) / divisor,
            -2.0 * cos(omega) / divisor,
            (1.0 - alpha * a) / divisor,
            -2.0 * cos(omega) / divisor,
            (1.0 - alpha / a) / divisor,
        )
    }

    private fun lowShelf(frequency: Double, gainDb: Double): DoubleArray {
        if (gainDb == 0.0) return IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val cosine = cos(omega)
        val beta = sqrt(2.0 * a) * sin(omega)
        val divisor = (a + 1.0) + (a - 1.0) * cosine + beta
        return doubleArrayOf(
            a * ((a + 1.0) - (a - 1.0) * cosine + beta) / divisor,
            2.0 * a * ((a - 1.0) - (a + 1.0) * cosine) / divisor,
            a * ((a + 1.0) - (a - 1.0) * cosine - beta) / divisor,
            -2.0 * ((a - 1.0) + (a + 1.0) * cosine) / divisor,
            ((a + 1.0) + (a - 1.0) * cosine - beta) / divisor,
        )
    }

    private class Biquad {
        private val coefficients = IDENTITY.copyOf()
        private val target = IDENTITY.copyOf()
        private val steps = DoubleArray(5)
        private val z1 = DoubleArray(2)
        private val z2 = DoubleArray(2)
        private var remaining = 0

        fun setCoefficients(values: DoubleArray, frames: Int) {
            for (index in coefficients.indices) {
                target[index] = values[index]
                steps[index] = (target[index] - coefficients[index]) / frames
            }
            remaining = frames
        }

        fun advance() {
            if (remaining == 0) return
            remaining--
            for (index in coefficients.indices) {
                coefficients[index] = if (remaining == 0) target[index] else coefficients[index] + steps[index]
            }
        }

        fun process(input: Double, channel: Int): Double {
            val output = coefficients[0] * input + z1[channel]
            z1[channel] = coefficients[1] * input - coefficients[3] * output + z2[channel]
            z2[channel] = coefficients[2] * input - coefficients[4] * output
            return output
        }

        fun reset() {
            z1.fill(0.0)
            z2.fill(0.0)
            remaining = 0
            target.copyInto(coefficients)
        }
    }

    private class Ramp(initial: Double) {
        private var value = initial
        private var target = initial
        private var step = 0.0
        private var remaining = 0

        fun moveTo(next: Double, frames: Int) {
            if (next == target) return
            target = next
            remaining = frames
            step = (target - value) / frames
        }

        fun jumpTo(next: Double) {
            target = next
            finish()
        }

        fun finish() {
            value = target
            remaining = 0
        }

        fun next(): Double {
            if (remaining > 0) {
                remaining--
                value = if (remaining == 0) target else value + step
            }
            return value
        }
    }

    /** 四条阻尼梳状线加两级全通扩散；延时按采样率缩放，左右采用不同长度。 */
    private class Reverb(sampleRate: Int, spread: Int) {
        private val combs = intArrayOf(1_116, 1_188, 1_277, 1_356).map { delay ->
            Comb(max(1, ((delay + spread) * sampleRate.toLong() / 44_100).toInt()))
        }.toTypedArray()
        private val diffusers = intArrayOf(225, 556).map { delay ->
            AllPass(max(1, ((delay + spread) * sampleRate.toLong() / 44_100).toInt()))
        }.toTypedArray()

        fun process(input: Double): Double {
            var output = 0.0
            for (comb in combs) output += comb.process(input)
            output *= 0.07
            for (diffuser in diffusers) output = diffuser.process(output)
            return output
        }

        fun reset() {
            for (comb in combs) comb.reset()
            for (diffuser in diffusers) diffuser.reset()
        }
    }

    private class Comb(length: Int) {
        private val buffer = DoubleArray(length)
        private var position = 0
        private var damped = 0.0

        fun process(input: Double): Double {
            val output = buffer[position]
            damped = output * 0.7 + damped * 0.3
            buffer[position] = input + damped * 0.72
            position++
            if (position == buffer.size) position = 0
            return output
        }

        fun reset() {
            buffer.fill(0.0)
            position = 0
            damped = 0.0
        }
    }

    private class AllPass(length: Int) {
        private val buffer = DoubleArray(length)
        private var position = 0

        fun process(input: Double): Double {
            val delayed = buffer[position]
            val output = delayed - input * 0.5
            buffer[position] = input + output * 0.5
            position++
            if (position == buffer.size) position = 0
            return output
        }

        fun reset() {
            buffer.fill(0.0)
            position = 0
        }
    }

    private companion object {
        val FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        fun Float.finiteOrZero(): Double = if (isFinite()) toDouble() else 0.0
    }
}
