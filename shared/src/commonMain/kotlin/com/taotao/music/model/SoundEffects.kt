package com.taotao.music.model

import kotlin.math.ln
import kotlin.math.roundToInt

/** 五个听感控制点；平台按实际支持的中心频率插值，增益单位为毫贝（100 mB = 1 dB）。 */
val SoundEffectFrequenciesHz = listOf(60, 230, 910, 3_600, 14_000)

/** 克制的听感预设，避免过大的增益改变歌曲原有的动态。 */
enum class SoundEffectPreset(
    val label: String,
    val description: String,
    val gains: List<Int>,
) {
    ORIGINAL("原声", "保留原本的平衡", listOf(0, 0, 0, 0, 0)),
    POP("流行", "人声与节奏更鲜明", listOf(100, 200, 100, 200, 100)),
    VOCAL("清澈人声", "让歌声靠近一点", listOf(-200, -100, 300, 200, -100)),
    BASS("低音律动", "鼓点饱满有弹性", listOf(400, 200, 0, -100, -100)),
    ROCK("摇滚", "吉他与鼓点更有力", listOf(300, 100, -200, 200, 300)),
    CLASSICAL("古典", "舒展器乐的层次", listOf(100, 0, -100, 100, 200)),
    CUSTOM("自定义", "调成自己的听感", listOf(0, 0, 0, 0, 0));

    companion object {
        fun of(value: String?): SoundEffectPreset = entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

/** 音效是设备偏好，与账号、单首歌曲的播放音质相互独立；默认关闭。 */
data class SoundEffectsSettings(
    val enabled: Boolean = false,
    val preset: SoundEffectPreset = SoundEffectPreset.ORIGINAL,
    val customGains: List<Int> = List(SoundEffectFrequenciesHz.size) { 0 },
    val bassStrength: Int = 0,
) {
    val bandGains: List<Int>
        get() = if (preset == SoundEffectPreset.CUSTOM) normalizedCustomGains() else preset.gains

    fun normalized(): SoundEffectsSettings = copy(
        customGains = normalizedCustomGains(),
        bassStrength = bassStrength.coerceIn(0, 1_000),
    )

    /** 第一次拖动预设时以当前曲线为起点；在预设之间切换不会覆盖已保存的自定义曲线。 */
    fun withBandGain(index: Int, gain: Int): SoundEffectsSettings {
        if (index !in SoundEffectFrequenciesHz.indices) return this
        return copy(
            preset = SoundEffectPreset.CUSTOM,
            customGains = bandGains.toMutableList().apply { set(index, gain.coerceIn(-600, 600)) },
        ).normalized()
    }

    /** Android 不同设备的频段数量不同，按对数频率映射而非假定总是五段。 */
    fun gainAt(frequencyHz: Int): Int {
        val frequencies = SoundEffectFrequenciesHz
        val gains = bandGains
        if (frequencyHz <= frequencies.first()) return gains.first()
        if (frequencyHz >= frequencies.last()) return gains.last()
        val upper = frequencies.indexOfFirst { it >= frequencyHz }
        val lower = upper - 1
        val fraction = ln(frequencyHz.toDouble() / frequencies[lower]) /
            ln(frequencies[upper].toDouble() / frequencies[lower])
        return (gains[lower] + (gains[upper] - gains[lower]) * fraction).roundToInt()
    }

    private fun normalizedCustomGains(): List<Int> = SoundEffectFrequenciesHz.indices.map { index ->
        (customGains.getOrNull(index) ?: 0).coerceIn(-600, 600)
    }
}
