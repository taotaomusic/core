package com.taotao.music.model

import kotlin.math.ln
import kotlin.math.roundToInt

/** 十段倍频程控制点，增益单位为毫贝（100 mB = 1 dB）。 */
val SoundEffectFrequenciesHz = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

private val legacyFrequencies = listOf(60, 230, 910, 3_600, 14_000)

/** 旧五段曲线按对数频率迁移，保留升级前的听感。 */
fun migrateLegacySoundEffectGains(gains: List<Int>): List<Int> = SoundEffectFrequenciesHz.map {
    interpolateGain(it, legacyFrequencies, List(5) { index -> (gains.getOrNull(index) ?: 0).coerceIn(-600, 600) })
}

/** 克制的听感预设，避免过大的增益改变歌曲原有的动态。 */
enum class SoundEffectPreset(
    val label: String,
    val description: String,
    val gains: List<Int>,
) {
    ORIGINAL("原声", "保留原本的平衡", List(10) { 0 }),
    POP("流行", "人声与节奏更鲜明", migrateLegacySoundEffectGains(listOf(100, 200, 100, 200, 100))),
    VOCAL("清澈人声", "让歌声靠近一点", migrateLegacySoundEffectGains(listOf(-200, -100, 300, 200, -100))),
    BASS("低音律动", "鼓点饱满有弹性", migrateLegacySoundEffectGains(listOf(400, 200, 0, -100, -100))),
    ROCK("摇滚", "吉他与鼓点更有力", migrateLegacySoundEffectGains(listOf(300, 100, -200, 200, 300))),
    CLASSICAL("古典", "舒展器乐的层次", migrateLegacySoundEffectGains(listOf(100, 0, -100, 100, 200))),
    CUSTOM("自定义", "调成自己的听感", List(10) { 0 });

    companion object {
        fun of(value: String?): SoundEffectPreset = entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

/** 场景同时设置均衡、低音、空间和混响，用户可在此基础上继续微调。 */
enum class SoundEffectScene(val label: String, val description: String) {
    ORIGINAL("原声", "所有增强归零"),
    CLEAR_VOCAL("清澈人声", "突出人声与咬字"),
    DEEP_BASS("澎湃低音", "低频更有弹性"),
    WIDE("宽广声场", "耳机立体声展宽"),
    LIVE("现场律动", "展宽与轻混响"),
    WARM("温暖轻柔", "柔和高频与轻低音");

    fun applyTo(settings: SoundEffectsSettings): SoundEffectsSettings {
        val base = settings.copy(enabled = true, bassStrength = 0, spatialStrength = 0, reverbStrength = 0)
        return when (this) {
            ORIGINAL -> base.copy(preset = SoundEffectPreset.ORIGINAL)
            CLEAR_VOCAL -> base.copy(preset = SoundEffectPreset.VOCAL)
            DEEP_BASS -> base.copy(preset = SoundEffectPreset.BASS, bassStrength = 300)
            WIDE -> base.copy(preset = SoundEffectPreset.ORIGINAL, spatialStrength = 500)
            LIVE -> base.copy(preset = SoundEffectPreset.POP, spatialStrength = 300, reverbStrength = 200)
            WARM -> base.copy(preset = SoundEffectPreset.CUSTOM,
                customGains = listOf(100, 200, 150, 50, 0, 0, -50, -100, -200, -250), bassStrength = 100)
        }
    }

    fun matches(settings: SoundEffectsSettings): Boolean {
        val target = applyTo(settings)
        return settings.preset == target.preset && settings.bandGains == target.bandGains &&
            settings.bassStrength == target.bassStrength && settings.spatialStrength == target.spatialStrength &&
            settings.reverbStrength == target.reverbStrength
    }
}

/** 音效是设备偏好，与账号、单首歌曲的播放音质相互独立；默认关闭。 */
data class SoundEffectsSettings(
    val enabled: Boolean = false,
    val preset: SoundEffectPreset = SoundEffectPreset.ORIGINAL,
    val customGains: List<Int> = List(SoundEffectFrequenciesHz.size) { 0 },
    val bassStrength: Int = 0,
    val spatialStrength: Int = 0,
    val reverbStrength: Int = 0,
) {
    val bandGains: List<Int>
        get() = if (preset == SoundEffectPreset.CUSTOM) normalizedCustomGains() else preset.gains

    fun normalized(): SoundEffectsSettings = copy(
        customGains = normalizedCustomGains(),
        bassStrength = bassStrength.coerceIn(0, 1_000),
        spatialStrength = spatialStrength.coerceIn(0, 1_000),
        reverbStrength = reverbStrength.coerceIn(0, 1_000),
    )

    /** 第一次拖动预设时以当前曲线为起点；在预设之间切换不会覆盖已保存的自定义曲线。 */
    fun withBandGain(index: Int, gain: Int): SoundEffectsSettings {
        if (index !in SoundEffectFrequenciesHz.indices) return this
        return copy(
            preset = SoundEffectPreset.CUSTOM,
            customGains = bandGains.toMutableList().apply { set(index, gain.coerceIn(-600, 600)) },
        ).normalized()
    }

    fun gainAt(frequencyHz: Int): Int = interpolateGain(frequencyHz, SoundEffectFrequenciesHz, bandGains)

    private fun normalizedCustomGains(): List<Int> = SoundEffectFrequenciesHz.indices.map { index ->
        (customGains.getOrNull(index) ?: 0).coerceIn(-600, 600)
    }
}

private fun interpolateGain(frequencyHz: Int, frequencies: List<Int>, gains: List<Int>): Int {
    if (frequencyHz <= frequencies.first()) return gains.first()
    if (frequencyHz >= frequencies.last()) return gains.last()
    val upper = frequencies.indexOfFirst { it >= frequencyHz }
    val lower = upper - 1
    val fraction = ln(frequencyHz.toDouble() / frequencies[lower]) /
        ln(frequencies[upper].toDouble() / frequencies[lower])
    return (gains[lower] + (gains[upper] - gains[lower]) * fraction).roundToInt()
}
