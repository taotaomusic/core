package com.taotao.music.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** 定时播放的时长、暂停冻结和到期边界回归测试。 */
class SleepTimerPolicyTest {
    @Test
    fun `分钟输入会限制在一到二十四小时`() {
        assertEquals(60_000L, SleepTimerPolicy.durationForMinutes(0))
        assertEquals(30 * 60_000L, SleepTimerPolicy.durationForMinutes(30))
        assertEquals(24 * 60 * 60_000L, SleepTimerPolicy.durationForMinutes(99 * 60))
    }

    @Test
    fun `暂停时不消耗剩余时间`() {
        assertEquals(
            10_000L,
            SleepTimerPolicy.remainingAfterTick(10_000L, 5_000L, isPlaying = false),
        )
    }

    @Test
    fun `播放到期时剩余时间归零`() {
        assertEquals(
            0L,
            SleepTimerPolicy.remainingAfterTick(2_000L, 3_000L, isPlaying = true),
        )
    }
}
