package com.taotao.music.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StereoEffectsProcessorTest {
    @Test
    fun `停用与全零设置均精确旁路`() {
        val input = floatArrayOf(-1f, 0.1234567f, 0f, 1f, -0.000001f, 0.5f)
        val processor = StereoEffectsProcessor(48_000, 2)
        val output = input.copyOf()
        processor.process(output)
        assertContentEquals(input, output)
        processor.updateParameters(StereoEffectsParameters(enabled = true))
        processor.process(output)
        assertContentEquals(input, output)
        processor.updateParameters(StereoEffectsParameters(enabled = false, bassStrength = 1_000))
        processor.process(output)
        assertContentEquals(input, output)
    }

    @Test
    fun `关闭已在运行的音效立即恢复精确旁路`() {
        val processor = StereoEffectsProcessor(48_000, 2)
        processor.updateParameters(StereoEffectsParameters(enabled = true, bassStrength = 1_000, reverbStrength = 500))
        processor.process(tone(60.0, 48_000, 2))
        processor.updateParameters(StereoEffectsParameters())
        val input = tone(730.0, 48_000, 2)
        val output = input.copyOf()
        processor.process(output)
        assertContentEquals(input, output)
    }

    @Test
    fun `分块处理和连续处理结果一致`() {
        val parameters = StereoEffectsParameters(
            enabled = true,
            gains = listOf(300, 200, 100, 0, -100, 200, 100, -100, 200, 100),
            bassStrength = 220,
            spatialStrength = 430,
            reverbStrength = 620,
        )
        val whole = StereoEffectsProcessor(44_100, 2).apply { updateParameters(parameters) }
        val chunked = StereoEffectsProcessor(44_100, 2).apply { updateParameters(parameters) }
        val input = tone(347.0, 44_100, 2)
        val expected = input.copyOf().also { whole.process(it) }
        val actual = FloatArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val end = (offset + 514).coerceAtMost(input.size)
            val block = input.copyOfRange(offset, end)
            chunked.process(block)
            block.copyInto(actual, offset)
            offset = end
        }
        assertContentEquals(expected, actual)
    }

    @Test
    fun `各采样率和单声道的极限参数不产生非有限数或越界输出`() {
        for (rate in listOf(8_000, 11_025, 44_100, 48_000, 96_000, 192_000)) {
            for (channels in 1..2) {
                val processor = StereoEffectsProcessor(rate, channels)
                processor.updateParameters(
                    StereoEffectsParameters(
                        enabled = true,
                        gains = listOf(99_999, -99_999, 1_200, -1_200, 1_200, -1_200, 1_200, -1_200, 1_200, -1_200),
                        bassStrength = 99_999,
                        spatialStrength = 99_999,
                        reverbStrength = 99_999,
                    ),
                )
                val buffer = tone(770.0, rate, channels)
                buffer[0] = Float.NaN
                buffer[1] = Float.POSITIVE_INFINITY
                processor.process(buffer)
                assertTrue(buffer.all { it.isFinite() && it in -1f..1f }, "rate=$rate channels=$channels")
            }
        }
    }

    @Test
    fun `满幅信号同时开启所有增强并连续换参数仍保持有限和限幅`() {
        val processor = StereoEffectsProcessor(48_000, 2)
        repeat(24) { step ->
            processor.updateParameters(
                StereoEffectsParameters(
                    enabled = true,
                    gains = List(10) { band -> if ((band + step) % 3 == 0) -1_200 else 1_200 },
                    bassStrength = 1_000,
                    spatialStrength = if (step % 2 == 0) 1_000 else 300,
                    reverbStrength = 1_000,
                ),
            )
            val buffer = FloatArray(1_024 * 2) { index ->
                val frame = step * 1_024 + index / 2
                val signal = sin(2.0 * PI * 61.0 * frame / 48_000.0) * 0.65 +
                    sin(2.0 * PI * 1_003.0 * frame / 48_000.0) * 0.35
                (if (index % 2 == 0) signal else -signal).toFloat()
            }
            processor.process(buffer)
            assertTrue(buffer.all { it.isFinite() && it in -1f..1f }, "step=$step")
        }
    }

    @Test
    fun `低音棚架提升低频相对于中高频的比例`() {
        fun ratio(frequency: Double): Double {
            val input = tone(frequency, 48_000, 1)
            val processor = StereoEffectsProcessor(48_000, 1)
            processor.updateParameters(StereoEffectsParameters(enabled = true, bassStrength = 700))
            val output = input.copyOf().also { processor.process(it) }
            return rms(output, 4_800) / rms(input, 4_800)
        }
        assertTrue(ratio(40.0) > ratio(2_000.0) * 1.7)
    }

    @Test
    fun `十段均衡器能独立改变目标频段`() {
        fun ratio(frequency: Double): Double {
            val input = tone(frequency, 48_000, 1)
            val processor = StereoEffectsProcessor(48_000, 1)
            processor.updateParameters(
                StereoEffectsParameters(enabled = true, gains = List(10) { if (it == 5) 600 else 0 }),
            )
            val output = input.copyOf().also { processor.process(it) }
            return rms(output, 4_800) / rms(input, 4_800)
        }
        assertTrue(ratio(1_000.0) > ratio(100.0) * 1.7)
        assertTrue(ratio(1_000.0) > ratio(8_000.0) * 1.7)
    }

    @Test
    fun `声场展宽保留中置信号并增加侧信号`() {
        fun render(spatial: Int): FloatArray {
            val processor = StereoEffectsProcessor(48_000, 2)
            processor.updateParameters(StereoEffectsParameters(enabled = true, spatialStrength = spatial))
            val buffer = FloatArray(48_000 * 2) { index ->
                val time = (index / 2) / 48_000.0
                val mid = sin(2.0 * PI * 300.0 * time) * 0.08
                val side = sin(2.0 * PI * 1_400.0 * time) * 0.08
                (mid + if (index % 2 == 0) side else -side).toFloat()
            }
            processor.process(buffer)
            return buffer
        }
        val plain = render(0)
        val wide = render(1_000)
        for (frame in 4_800 until 48_000) {
            val offset = frame * 2
            val plainMid = (plain[offset] + plain[offset + 1]) * 0.5
            val wideMid = (wide[offset] + wide[offset + 1]) * 0.5
            val plainSide = (plain[offset] - plain[offset + 1]) * 0.5
            val wideSide = (wide[offset] - wide[offset + 1]) * 0.5
            assertTrue(abs(plainMid - wideMid) < 0.000001)
            assertTrue(abs(plainSide * 2.0 - wideSide) < 0.000001)
        }
    }

    @Test
    fun `单声道仅开启声场时精确旁路`() {
        val processor = StereoEffectsProcessor(44_100, 1)
        processor.updateParameters(StereoEffectsParameters(enabled = true, spatialStrength = 1_000))
        val input = tone(440.0, 44_100, 1)
        val output = input.copyOf().also { processor.process(it) }
        assertContentEquals(input, output)
    }

    @Test
    fun `低采样率仅调整超出可用频率的控制点保持旁路`() {
        val processor = StereoEffectsProcessor(8_000, 2)
        processor.updateParameters(
            StereoEffectsParameters(enabled = true, gains = List(10) { if (it >= 8) 1_200 else 0 }),
        )
        val input = tone(440.0, 8_000, 2)
        val output = input.copyOf().also { processor.process(it) }
        assertContentEquals(input, output)
    }

    @Test
    fun `混响产生尾音且重置后完全清空`() {
        val processor = StereoEffectsProcessor(48_000, 2)
        processor.updateParameters(StereoEffectsParameters(enabled = true, reverbStrength = 1_000))
        processor.process(FloatArray(4_800 * 2))
        val impulse = FloatArray(4_800 * 2).apply { this[0] = 1f; this[1] = 1f }
        processor.process(impulse)
        assertTrue(impulse.drop(2_000).any { abs(it) > 0.00001f })
        processor.reset()
        val silence = FloatArray(48_000 * 2)
        processor.process(silence)
        assertTrue(silence.all { it == 0f })
    }

    @Test
    fun `启用与调整时保留平滑过渡`() {
        val processor = StereoEffectsProcessor(48_000, 2)
        processor.updateParameters(StereoEffectsParameters(enabled = true, spatialStrength = 1_000))
        val initial = FloatArray(2_000 * 2) { if (it % 2 == 0) 0.1f else -0.1f }
        processor.process(initial)
        assertTrue(abs(initial[0] - 0.1f) < 0.001f)
        assertTrue(abs(initial.last() + 0.2f) < 0.0001f)
        processor.updateParameters(StereoEffectsParameters(enabled = true, spatialStrength = 200))
        val updated = floatArrayOf(0.1f, -0.1f)
        processor.process(updated)
        assertTrue(abs(updated[0] - 0.2f) < 0.001f)
    }

    @Test
    fun `指定帧数不修改剩余样本并检查无效参数`() {
        val processor = StereoEffectsProcessor(48_000, 2)
        processor.updateParameters(StereoEffectsParameters(enabled = true, spatialStrength = 1_000))
        val samples = floatArrayOf(0.1f, -0.1f, 0.4f, 0.5f, 0.6f)
        processor.process(samples, 1)
        assertEquals(0.4f, samples[2])
        assertEquals(0.5f, samples[3])
        assertEquals(0.6f, samples[4])
        assertFailsWith<IllegalArgumentException> { processor.process(samples, 3) }
        assertFailsWith<IllegalArgumentException> { processor.process(samples, -1) }
        assertFailsWith<IllegalArgumentException> { StereoEffectsProcessor(48_000, 6) }
        assertFailsWith<IllegalArgumentException> { StereoEffectsProcessor(0, 2) }
    }

    private fun tone(frequency: Double, rate: Int, channels: Int): FloatArray = FloatArray(rate * channels) { index ->
        (sin(2.0 * PI * frequency * (index / channels) / rate) * 0.15).toFloat()
    }

    private fun rms(samples: FloatArray, start: Int): Double {
        var sum = 0.0
        for (index in start until samples.size) sum += samples[index].toDouble() * samples[index]
        return sqrt(sum / (samples.size - start))
    }
}
