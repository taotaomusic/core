package com.taotao.music.data.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 耗电汇总的状态归属与放电速率边界测试。 */
class PowerSummaryTest {
    private val hour = 3_600_000L

    @Test
    fun `后台放电按小时折算`() {
        val summary = summarizePowerSamples(
            listOf(
                sample(at = 0L, battery = 80, appForeground = false),
                sample(at = hour, battery = 79, appForeground = false),
            ),
        )

        assertEquals(1, summary?.backgroundDrop)
        assertEquals(1, summary?.dischargePercent)
        assertEquals(1f, summary?.backgroundDrainPerHour)
        assertEquals(0, summary?.foregroundDrop)
    }

    @Test
    fun `充电区间不计入放电量`() {
        val summary = summarizePowerSamples(
            listOf(
                sample(at = 0L, battery = 80, charging = true),
                sample(at = hour, battery = 90, charging = true),
            ),
        )

        assertEquals(0, summary?.dischargePercent)
        assertNull(summary?.backgroundDrainPerHour)
    }

    @Test
    fun `播放状态变化后按各自区间归属`() {
        val summary = summarizePowerSamples(
            listOf(
                sample(at = 0L, battery = 90, playing = true, appForeground = true),
                sample(at = hour, battery = 88, playing = false, appForeground = true),
                sample(at = 2 * hour, battery = 87, playing = false, appForeground = true),
            ),
        )

        assertEquals(2, summary?.playbackDrop)
        assertEquals(3, summary?.foregroundDrop)
        assertEquals(2f, summary?.playbackDrainPerHour)
        assertEquals(1.5f, summary?.foregroundDrainPerHour)
    }

    @Test
    fun `不足半小时不计算速率`() {
        val summary = summarizePowerSamples(
            listOf(
                sample(at = 0L, battery = 80),
                sample(at = 10 * 60_000L, battery = 79),
            ),
        )

        assertEquals(1, summary?.dischargePercent)
        assertNull(summary?.backgroundDrainPerHour)
    }

    @Test
    fun `单条采样没有趋势`() {
        assertNull(summarizePowerSamples(listOf(sample(at = 0L, battery = 80))))
        assertNull(summarizePowerSamples(emptyList()))
    }

    private fun sample(
        at: Long,
        battery: Int,
        charging: Boolean = false,
        screenOn: Boolean = true,
        appForeground: Boolean = true,
        playing: Boolean = false,
    ) = PowerSample(
        atMillis = at,
        batteryPercent = battery,
        charging = charging,
        screenOn = screenOn,
        appForeground = appForeground,
        playing = playing,
    )
}
