package com.taotao.music.model

/** 一个逐字单元。[text] 可能是一个汉字，也可能是一个英文单词。 */
data class LyricWord(val timeMs: Int, val durationMs: Int, val text: String) {
    val endMs: Int get() = timeMs + durationMs
}

/**
 * 一行歌词。
 *
 * [durationMs] 为 0 表示时长未知（普通 LRC 只有起始时间）；
 * [words] 为空表示没有逐字时间轴，界面只能整行高亮。
 */
data class LyricLine(
    val timeMs: Int,
    val text: String,
    val durationMs: Int = 0,
    val words: List<LyricWord> = emptyList(),
)

/**
 * 解析后的歌词。
 *
 * [synced] 为 false 表示没有时间轴（服务端只给到纯文本），
 * 此时界面只做分行展示，不高亮也不可点击跳转。
 */
data class Lyric(val lines: List<LyricLine>, val synced: Boolean) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /** 是否具备逐字时间轴。只要有一行带字级数据就按逐字渲染。 */
    val hasWords: Boolean get() = lines.any { it.words.isNotEmpty() }

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

    /**
     * 某一行已唱到的比例，取值 0..1，用于逐字高亮的裁切宽度。
     *
     * 有逐字数据时按字宽累计：已唱完的字算满，正在唱的字按其自身进度插值，
     * 这样长音的字会平滑推进而不是整字跳变。
     * 没有逐字数据时退化为按行时长线性插值；连行时长都没有则整行直接算已唱完。
     */
    fun progressOf(lineIndex: Int, positionMs: Int): Float {
        val line = lines.getOrNull(lineIndex) ?: return 0f
        if (positionMs <= line.timeMs) return 0f

        val words = line.words
        if (words.isEmpty()) {
            if (line.durationMs <= 0) return 1f
            return ((positionMs - line.timeMs).toFloat() / line.durationMs).coerceIn(0f, 1f)
        }

        val totalChars = words.sumOf { it.text.length }
        if (totalChars == 0) return 1f
        var doneChars = 0f
        for (word in words) {
            when {
                positionMs >= word.endMs -> doneChars += word.text.length
                positionMs <= word.timeMs -> return (doneChars / totalChars).coerceIn(0f, 1f)
                else -> {
                    val within = if (word.durationMs <= 0) 1f
                    else (positionMs - word.timeMs).toFloat() / word.durationMs
                    doneChars += word.text.length * within.coerceIn(0f, 1f)
                    return (doneChars / totalChars).coerceIn(0f, 1f)
                }
            }
        }
        return 1f
    }

    companion object {
        val EMPTY = Lyric(emptyList(), synced = false)
    }
}

/** LRC 与 YRC 歌词解析。 */
object LyricParser {

    /** `[mm:ss.xx]`、`[mm:ss.xxx]`、`[mm:ss]`，也兼容用冒号分隔毫秒的 `[mm:ss:xx]`。 */
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    /** `[ti:标题]` 这类元信息标签，展示时要去掉。 */
    private val METADATA = Regex("""\[[a-zA-Z]+:[^\]]*\]""")

    /** `[offset:-500]` 表示整体提前 500 毫秒，正数为延后。 */
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    /** YRC 行头：`[行起始ms,行时长ms]`。 */
    private val YRC_HEADER = Regex("""^\[(\d+),(\d+)\]""")

    /**
     * YRC 的字级时间组：`(起始ms,时长ms)`，尾部可能还带一个用途不明的数字。
     *
     * 只匹配时间组本身，文本取相邻两个时间组之间的内容 —— 不能用
     * `([^(\n]*)\(…\)` 这种「文本 + 时间组」的写法去捕获文本：文本捕获组必须排除
     * `(` 才不会吞掉时间组的左括号，于是歌词里字面的 `(` 永远匹配不上而被丢掉。
     * 实际数据里就有这种行，例如「雨爱 - 杨丞琳 (Rainie Yang)」会少掉左括号，
     * 只剩一个孤立的右括号。
     */
    private val YRC_TIMING = Regex("""\((\d+),(\d+)(?:,\d+)?\)""")

    /**
     * 优先使用逐字歌词。
     *
     * [yrc] 解析成功就用它，因为它同时含有行时间和字时间；
     * 失败或为空才退回 [lrc]。两者都没有时返回 [Lyric.EMPTY]，界面据此显示「暂无歌词」。
     */
    fun parse(lrc: String?, yrc: String? = null): Lyric {
        val word = parseYrc(yrc)
        if (!word.isEmpty) return word
        return parseLrc(lrc)
    }

    fun parseYrc(raw: String?): Lyric {
        if (raw.isNullOrBlank()) return Lyric.EMPTY
        // yrc 同样可能带 offset 标签，行时间和字时间都要跟着平移。
        val offsetMs = OFFSET.find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val lines = mutableListOf<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val header = YRC_HEADER.find(rawLine) ?: continue
            val body = rawLine.substring(header.value.length)
            val words = wordsOf(body, offsetMs)
            if (words.isEmpty()) continue
            val text = words.joinToString("") { it.text }.trim()
            if (text.isEmpty()) continue
            lines += LyricLine(
                timeMs = ((header.groupValues[1].toIntOrNull() ?: 0) + offsetMs).coerceAtLeast(0),
                text = text,
                durationMs = header.groupValues[2].toIntOrNull() ?: 0,
                words = words,
            )
        }
        return if (lines.isEmpty()) Lyric.EMPTY else Lyric(lines.sortedBy(LyricLine::timeMs), synced = true)
    }

    /** 按时间组切分一行 YRC，每个时间组前面的那段文字就是它对应的字。 */
    private fun wordsOf(body: String, offsetMs: Int): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        var cursor = 0
        for (timing in YRC_TIMING.findAll(body)) {
            val text = body.substring(cursor, timing.range.first)
            cursor = timing.range.last + 1
            if (text.isEmpty()) continue
            val start = timing.groupValues[1].toIntOrNull() ?: continue
            words += LyricWord(
                timeMs = (start + offsetMs).coerceAtLeast(0),
                durationMs = timing.groupValues[2].toIntOrNull() ?: 0,
                text = text,
            )
        }
        return words
    }

    fun parseLrc(raw: String?): Lyric {
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
        val sorted = lines.sortedBy(LyricLine::timeMs)
        // LRC 不含行时长，用下一行的起始时间补出来，逐字高亮的插值需要它。
        return Lyric(
            sorted.mapIndexed { index, line ->
                val next = sorted.getOrNull(index + 1)?.timeMs
                if (next != null && next > line.timeMs) line.copy(durationMs = next - line.timeMs) else line
            },
            synced = true,
        )
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
