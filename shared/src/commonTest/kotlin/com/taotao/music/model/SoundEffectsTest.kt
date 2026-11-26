package com.taotao.music.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundEffectsTest {
    @Test
    fun `首次使用默认关闭且没有增强`() {
        val settings = SoundEffectsSettings()
        assertFalse(settings.enabled)
        assertEquals(List(SoundEffectFrequenciesHz.size) { 0 }, settings.bandGains)
        assertEquals(0, settings.bassStrength)
    }

    @Test
    fun `所有预设匹配控制点且增益不过界`() {
        SoundEffectPreset.entries.forEach { preset ->
            assertEquals(SoundEffectFrequenciesHz.size, preset.gains.size)
            assertTrue(preset.gains.all { it in -600..600 })
        }
        assertEquals(SoundEffectPreset.ORIGINAL, SoundEffectPreset.of("已移除的预设"))
    }

    @Test
    fun `场景预设一起配置十段频响与空间效果`() {
        val baseline = SoundEffectsSettings(enabled = false)
        val wide = SoundEffectScene.WIDE.applyTo(baseline)
        assertTrue(wide.enabled)
        assertEquals(500, wide.spatialStrength)
        assertTrue(SoundEffectScene.WIDE.matches(wide))
        assertEquals(200, SoundEffectScene.LIVE.applyTo(baseline).reverbStrength)
        assertEquals(300, SoundEffectScene.LIVE.applyTo(baseline).spatialStrength)
        assertEquals(0, SoundEffectScene.ORIGINAL.applyTo(baseline).reverbStrength)
        assertEquals(0, SoundEffectScene.ORIGINAL.applyTo(baseline).spatialStrength)
    }

    @Test
    fun `损坏的自定义频段补齐且限制增益与低音强度`() {
        val settings = SoundEffectsSettings(
            preset = SoundEffectPreset.CUSTOM,
            customGains = listOf(-9999, 9999),
            bassStrength = 2000,
        ).normalized()
        assertEquals(SoundEffectFrequenciesHz.size, settings.bandGains.size)
        assertEquals(listOf(-600, 600, 0, 0, 0, 0, 0, 0, 0, 0), settings.bandGains)
        assertEquals(1000, settings.bassStrength)
        assertEquals(0, settings.copy(bassStrength = -1).normalized().bassStrength)
        assertEquals(10, settings.copy(customGains = List(10) { 1 }).normalized().customGains.size)
    }

    @Test
    fun `拖动以当前预设为起点切到自定义`() {
        val settings = SoundEffectsSettings(enabled = true, preset = SoundEffectPreset.ROCK)
        val edited = settings.withBandGain(2, 400)
        assertEquals(SoundEffectPreset.CUSTOM, edited.preset)
        assertEquals(settings.bandGains.toMutableList().apply { set(2, 400) }, edited.bandGains)
        assertTrue(edited.enabled)
        assertEquals(settings, settings.withBandGain(-1, 400))
        assertEquals(settings, settings.withBandGain(10, 400))
    }

    @Test
    fun `开关与切换预设都保留自定义曲线和低音强度`() {
        val custom = SoundEffectsSettings(enabled = true, bassStrength = 240).withBandGain(0, 300)
        val restored = custom.copy(preset = SoundEffectPreset.POP, enabled = false)
            .copy(preset = SoundEffectPreset.CUSTOM, enabled = true)
        assertEquals(custom, restored)
    }

    @Test
    fun `中心频率精确命中且两端采用边界增益`() {
        val settings = SoundEffectsSettings(preset = SoundEffectPreset.ROCK)
        SoundEffectFrequenciesHz.forEachIndexed { index, frequency ->
            assertEquals(settings.bandGains[index], settings.gainAt(frequency))
        }
        assertEquals(settings.bandGains.first(), settings.gainAt(0))
        assertEquals(settings.bandGains.first(), settings.gainAt(-1))
        assertEquals(settings.bandGains.last(), settings.gainAt(24000))
    }

    @Test
    fun `设备频段以对数频率插值`() {
        val settings = SoundEffectsSettings(preset = SoundEffectPreset.CUSTOM, customGains = listOf(-600, -600, 600, 0, 0, 0, 0, 0, 0, 0))
        // sqrt(62 * 125) 约为 88 Hz，是对数坐标的中点。
        assertTrue(settings.gainAt(88) in -5..5)
        assertEquals(0, SoundEffectsSettings().gainAt(1000))
        for (frequency in 20..20000 step 37) {
            assertTrue(settings.gainAt(frequency) in -600..600)
        }
    }
}
