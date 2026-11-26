package com.taotao.music.data

import com.taotao.music.model.SoundEffectPreset
import com.taotao.music.model.SoundEffectsSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundEffectsStoreTest {
    @Test
    fun `偏好完整往返保留开关预设自定义和低音`() {
        val settings = SoundEffectsSettings(
            enabled = true,
            preset = SoundEffectPreset.POP,
            customGains = listOf(-100, 0, 200, 300, 100, 0, 0, 0, 0, 0),
            bassStrength = 350,
            spatialStrength = 250,
            reverbStrength = 150,
        )
        assertEquals(settings, SoundEffectsStore.decode(SoundEffectsStore.encode(settings)))
    }

    @Test
    fun `缺失与损坏的偏好安全退回默认值`() {
        listOf(null, "", "坏数据", "[]", "{}").forEach { value ->
            assertEquals(SoundEffectsSettings(), SoundEffectsStore.decode(value))
        }
    }

    @Test
    fun `旧预设和越界数值读取时修正`() {
        val settings = SoundEffectsStore.decode(
            """{"enabled":true,"preset":"REMOVED","gains":[-9999,9999],"bass":9000}""",
        )
        assertEquals(SoundEffectPreset.ORIGINAL, settings.preset)
        assertEquals(10, settings.customGains.size)
        assertEquals(-600, settings.customGains.first())
        assertTrue(settings.customGains.all { it in -600..600 })
        assertEquals(1000, settings.bassStrength)
        assertEquals(0, settings.spatialStrength)
        assertEquals(0, settings.reverbStrength)
    }

    @Test
    fun `旧五段偏好按对数频率迁移为十段均衡`() {
        val settings = SoundEffectsStore.decode(
            """{"enabled":true,"preset":"CUSTOM","gains":[300,0,-100,200,100],"bass":250}""",
        )
        assertEquals(10, settings.customGains.size)
        assertTrue(settings.gainAt(60) in 285..315)
        assertTrue(settings.gainAt(230) in -30..30)
        assertTrue(settings.gainAt(910) in -90..-70)
        assertTrue(settings.gainAt(3600) in 160..190)
        assertTrue(settings.gainAt(14000) in 95..115)
        assertEquals(250, settings.bassStrength)
    }
}
