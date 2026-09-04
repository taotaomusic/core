package com.taotao.music.player

/** 定时播放的纯计算规则，方便在没有 Android/Media3 的单测里验证边界。 */
internal object SleepTimerPolicy {
    const val MILLIS_PER_MINUTE = 60_000L

    fun durationForMinutes(minutes: Int): Long {
        val maxMinutes = (SleepTimerContract.MAX_DURATION_MS / MILLIS_PER_MINUTE).toInt()
        return minutes.coerceIn(1, maxMinutes).toLong() * MILLIS_PER_MINUTE
    }

    fun remainingAfterTick(remainingMs: Long, elapsedMs: Long, isPlaying: Boolean): Long {
        if (remainingMs <= 0L || !isPlaying) return remainingMs.coerceAtLeast(0L)
        return (remainingMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}
