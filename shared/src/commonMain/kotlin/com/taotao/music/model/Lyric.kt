package com.taotao.music.model

/** 一行歌词及其出现时间。纯文本歌词的 [timeMs] 恒为 0。 */
data class LyricLine(val timeMs: Int, val text: String)

/**
 * 解析后的歌词。
 *
 * [synced] 为 false 表示歌词没有时间轴（服务端返回纯文本），
 * 此时界面只做分行展示，不做高亮和点击跳转。
 */
data class Lyric(val lines: List<LyricLine>, val synced: Boolean) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * 给定播放进度，返回当前应高亮的行下标；进度早于第一行时返回 -1。
     * 行已按时间升序排列，用二分查找避免每帧线性扫描。
     */
    fun indexAt(positionMs: Int): Int {
        if (!synced || lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (lines[middle].timeMs <= positionMs) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    companion object {
        val EMPTY = Lyric(emptyList(), synced = false)
    }
}

/** LRC 歌词解析。 */
object LyricParser {

    /** `[mm:ss.xx]`、`[mm:ss.xxx]`、`[mm:ss]`，也兼容用冒号分隔毫秒的 `[mm:ss:xx]`。 */
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    /** `[ti:标题]` 这类元信息标签，展示时要去掉。 */
    private val METADATA = Regex("""\[[a-zA-Z]+:[^\]]*\]""")

    /** `[offset:-500]` 表示整体提前 500 毫秒，正数为延后。 */
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String?): Lyric {
        if (raw.isNullOrBlank()) return Lyric.EMPTY
        val offsetMs = OFFSET.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val lines = mutableListOf<LyricLine>()
        var sawTimestamp = false

        for (rawLine in raw.lineSequence()) {
            val stamps = TIMESTAMP.findAll(rawLine).toList()
            if (stamps.isEmpty()) continue
            sawTimestamp = true
            val text = rawLine.replace(TIMESTAMP, "").trim()
            // 只有时间没有文字的行是间奏标记，保留会在界面上留出空白行。
            if (text.isEmpty()) continue
            // 一行可以挂多个时间戳（副歌复用），每个时间戳都生成一行。
            for (stamp in stamps) {
                lines += LyricLine(timeMsOf(stamp.groupValues, offsetMs), text)
            }
        }

        if (!sawTimestamp) {
            // 纯文本歌词：去掉元信息标签后按行保留，不做时间同步。
            val plain = raw.lineSequence()
                .map { it.replace(METADATA, "").trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(0, it) }
                .toList()
            return Lyric(plain, synced = false)
        }
        return Lyric(lines.sortedBy(LyricLine::timeMs), synced = true)
    }

    private fun timeMsOf(groups: List<String>, offsetMs: Int): Int {
        val minutes = groups[1].toIntOrNull() ?: 0
        val seconds = groups[2].toIntOrNull() ?: 0
        val fraction = groups[3]
        // 小数位数决定单位：一位是十分之一秒，两位是百分之一秒，三位是毫秒。
        val fractionMs = when (fraction.length) {
            0 -> 0
            1 -> (fraction.toIntOrNull() ?: 0) * 100
            2 -> (fraction.toIntOrNull() ?: 0) * 10
            else -> fraction.take(3).toIntOrNull() ?: 0
        }
        return (minutes * 60_000 + seconds * 1_000 + fractionMs + offsetMs).coerceAtLeast(0)
    }
}
