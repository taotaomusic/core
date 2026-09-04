package com.taotao.music.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerTest {
    @Test
    fun `定时播放时长限制在一分钟到一天`() {
        assertNull(sleepTimerDurationMs(0))
        assertNull(sleepTimerDurationMs(-5))
        assertEquals(5 * SLEEP_TIMER_MINUTE_MS, sleepTimerDurationMs(5))
        assertEquals(24 * 60 * SLEEP_TIMER_MINUTE_MS, sleepTimerDurationMs(SLEEP_TIMER_MAX_MINUTES))
        assertNull(sleepTimerDurationMs(SLEEP_TIMER_MAX_MINUTES + 1))
    }

    @Test
    fun `预设包含常用的六档时长`() {
        assertEquals(listOf(5, 10, 15, 30, 60, 90), SLEEP_TIMER_PRESET_MINUTES)
    }

    @Test
    fun `暂停不消耗定时额度且播放到期归零`() {
        assertEquals(10_000L, sleepTimerRemainingAfterTick(10_000L, 5_000L, isPlaying = false))
        assertEquals(0L, sleepTimerRemainingAfterTick(2_000L, 3_000L, isPlaying = true))
        assertEquals(0L, sleepTimerRemainingAfterTick(-1L, 500L, isPlaying = true))
    }

    @Test
    fun `倒计时显示向上取整并按小时格式化`() {
        assertEquals("00:00", formatSleepTimerRemaining(0L))
        assertEquals("00:01", formatSleepTimerRemaining(1L))
        assertEquals("01:00", formatSleepTimerRemaining(60_000L))
        assertEquals("01:01", formatSleepTimerRemaining(60_001L))
        assertEquals("01:01:01", formatSleepTimerRemaining(3_661_000L))
        assertEquals("2562047788015:12:56", formatSleepTimerRemaining(Long.MAX_VALUE))
    }
}
