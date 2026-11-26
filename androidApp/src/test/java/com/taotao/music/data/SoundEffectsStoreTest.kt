package com.taotao.music.data

import com.taotao.music.model.SoundEffectPreset
import com.taotao.music.model.SoundEffectsSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class SoundEffectsStoreTest {
    @Test
    fun `偏好完整往返保留开关预设自定义和低音`() {
        val settings = SoundEffectsSettings(
            enabled = true,
            preset = SoundEffectPreset.POP,
            customGains = listOf(-100, 0, 200, 300, 100),
            bassStrength = 350,
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
        assertEquals(listOf(-600, 600, 0, 0, 0), settings.customGains)
        assertEquals(1000, settings.bassStrength)
    }
}
