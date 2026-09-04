package com.taotao.music.player

import com.taotao.music.model.LyricParser
import com.taotao.music.model.Song
import org.json.JSONObject

/**
 * ColorOS / OPlus 原生锁屏歌词使用的媒体元数据协议。
 *
 * 这不是 Android 标准字段，但只是在本应用自己的 MediaSession 中发布完整时间轴，
 * 不依赖 Root、悬浮窗或向系统进程注入代码。系统是否展示仍由当前 ColorOS 版本决定。
 */
object OplusLyricMetadata {
    const val EXTRA_LYRIC_INFO = "lyricInfo"

    private val timedLrc = Regex("[\\[<]\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?[\\]>]")
    private const val MAX_RAW_INPUT_CHARS = 500_000
    private const val MAX_METADATA_LYRIC_CHARS = 200_000

    /** 构造一次整首歌词数据；播放进度由 MediaSession 提供，不逐行重写元数据。 */
    fun build(song: Song, lrc: String?, yrc: String?): String? {
        // MediaMetadata 最终会跨 Binder 发送给 SystemUI，限制体积避免异常歌词撑爆事务上限。
        val parsedWords = LyricParser.parseYrc(yrc?.takeIf { it.length <= MAX_RAW_INPUT_CHARS })
        val lineLyric = lrc
            ?.takeIf { timedLrc.containsMatchIn(it) }
            ?: parsedWords.lines.takeIf { parsedWords.synced && it.isNotEmpty() }
                ?.joinToString("\n") { line -> "${timestamp(line.timeMs)}${line.text}" }
            ?: return null
        if (lineLyric.length > MAX_METADATA_LYRIC_CHARS) return null
        val wordLyric = enhancedWordLyric(parsedWords)
            ?.takeIf { lineLyric.length + it.length <= MAX_METADATA_LYRIC_CHARS }

        return JSONObject()
            .put("songName", song.title)
            .put("artist", song.artist)
            .put("album", song.album)
            .put("songId", song.remoteId?.toString() ?: song.audioUri.orEmpty())
            .put("lyric", lineLyric)
            .apply {
                wordLyric?.let { put("rawLyric", it) }
            }
            .toString()
    }

    /** 把 QQ 音乐 YRC 转成开源 ColorOS 歌词桥使用的增强 LRC 逐字格式。 */
    private fun enhancedWordLyric(lyric: com.taotao.music.model.Lyric): String? {
        val lines = lyric.lines.mapNotNull { line ->
            if (line.words.isEmpty()) return@mapNotNull null
            buildString {
                line.words.forEach { word ->
                    append(timestamp(word.timeMs))
                    append(word.text)
                }
                val lineEnd = maxOf(
                    line.timeMs + line.durationMs,
                    line.words.maxOf { it.timeMs + it.durationMs },
                )
                append(timestamp(lineEnd))
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun timestamp(timeMs: Int): String {
        val safe = timeMs.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = safe / 1_000 % 60
        val millis = safe % 1_000
        return "[%02d:%02d.%03d]".format(minutes, seconds, millis)
    }
}
