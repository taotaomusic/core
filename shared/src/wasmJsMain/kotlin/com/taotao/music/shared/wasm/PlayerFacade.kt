package com.taotao.music.shared.wasm

import com.taotao.music.model.AudioQuality
import com.taotao.music.model.Lyric
import com.taotao.music.model.LyricLine
import com.taotao.music.model.LyricParser
import com.taotao.music.model.LyricWord
import com.taotao.music.model.labelOfQuality
import kotlin.js.JsExport

/**
 * 播放逻辑 wasm 门面：把 :shared 的纯 Kotlin 播放逻辑以 JS 友好的形态导出给
 * 桌面端（Tauri + React + TS），消除「Android 一份 Kotlin、桌面一份手工 TS 移植」
 * 的双份维护（desktop/src/main/lyrics.ts 正是 LyricParser 的移植副本）。
 *
 * 实现说明：Kotlin/Wasm 的 @JsExport 不允许标注 standalone object（CI 实测
 * 「not applicable to target 'standalone object'」），所以这里用
 * 「顶层 @JsExport 函数 + 私有状态对象」的标准形态；对 TS 侧而言
 * 模块的命名导出就是这些函数，语义与原 object 设计完全一致。
 *
 * 设计约定：
 * - **有状态缓存**：[loadLyric] 解析歌词并缓存 Kotlin 对象，随后的逐帧查询
 *   （[lyricIndexAt] / [lyricProgressOf]）直接操作对象，零 JSON 往返；
 *   JS 单线程，换歌时重调 [loadLyric] 即完成缓存替换。桌面包装层须保证
 *   「先 load 再 query」的时序。
 * - **Long 不出边界**：wasm↔JS 的大整数有精度坑，所有时间字段一律 Int 毫秒；
 * - 字符串 JSON 均做标准转义，TS 侧 JSON.parse 即得。
 *
 * TS 侧调用示例：
 * ```ts
 * const mod = await import("../wasm/taotao-shared-wasm");
 * const lyric = JSON.parse(mod.loadLyric(lrc, yrc));
 * const index = mod.lyricIndexAt(positionMs);
 * ```
 */

/** 当前缓存的歌词；未加载过时为 [Lyric.EMPTY]，查询全部安全返回空态值。 */
private var currentLyric: Lyric = Lyric.EMPTY

/**
 * 解析并缓存歌词，返回完整结构的 JSON 字符串（见 [lyricToJson]）。
 * 优先 YRC（自带行时间与字级时间），解析不出退回 LRC，两者都无效时空结构。
 */
@JsExport
fun loadLyric(lrc: String?, yrc: String?): String {
    currentLyric = LyricParser.parse(lrc, yrc)
    return lyricToJson(currentLyric)
}

/** 行级查找：当前进度落在第几行（二分），早于第一行返回 -1。 */
@JsExport
fun lyricIndexAt(positionMs: Int): Int = currentLyric.indexAt(positionMs)

/** 指定行已唱到的比例 0..1（逐字按字宽插值，无字级按时长线性）；行下标越界返回 0。 */
@JsExport
fun lyricProgressOf(lineIndex: Int, positionMs: Int): Double =
    currentLyric.progressOf(lineIndex, positionMs).toDouble()

/** 音质档位中文名（覆盖上游 0–18 全部档位），未知档位返回「音质 N」。 */
@JsExport
fun qualityLabel(value: Int): String = labelOfQuality(value)

/** 默认音质取值（HQ）。TS 侧初始化音质选择器时用。 */
@JsExport
fun defaultQualityValue(): Int = AudioQuality.Default.value

/** 音质取值是否为无损档；未知取值（枚举外的服务端档位）返回 false。 */
@JsExport
fun isLossless(value: Int): Boolean = AudioQuality.of(value).lossless

/** [Lyric] 的 JSON 序列化：结构与 shared 数据类一一对应，时间字段全部 Int 毫秒。 */
private fun lyricToJson(lyric: Lyric): String = buildString {
    append('{')
    append("\"synced\":").append(lyric.synced).append(',')
    append("\"lines\":[")
    lyric.lines.forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) append(',')
        appendLineJson(line)
    }
    append(']').append('}')
}

private fun StringBuilder.appendLineJson(line: LyricLine) {
    append("{\"timeMs\":").append(line.timeMs)
    append(",\"durationMs\":").append(line.durationMs)
    append(",\"text\":\"").append(escape(line.text)).append("\",\"words\":[")
    line.words.forEachIndexed { wordIndex, word ->
        if (wordIndex > 0) append(',')
        appendWordJson(word)
    }
    append("]}")
}

private fun StringBuilder.appendWordJson(word: LyricWord) {
    append("{\"timeMs\":").append(word.timeMs)
    append(",\"durationMs\":").append(word.durationMs)
    append(",\"text\":\"").append(escape(word.text)).append("\"}")
}

/** 标准 JSON 字符串转义：引号、反斜杠与全部 C0 控制字符。 */
private fun escape(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        }
    }
}
