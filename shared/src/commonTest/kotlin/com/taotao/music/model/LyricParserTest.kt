package com.taotao.music.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricParserTest {

    @Test
    fun 解析带时间轴的歌词() {
        val lyric = LyricParser.parse(
            """
            [ti:测试歌曲]
            [ar:测试歌手]
            [00:01.00]第一行
            [00:12.34]第二行
            [01:05.5]第三行
            """.trimIndent(),
        )
        assertTrue(lyric.synced)
        assertEquals(3, lyric.lines.size)
        assertEquals(LyricLine(1_000, "第一行"), lyric.lines[0])
        // .34 是百分之一秒，应解析为 340 毫秒
        assertEquals(LyricLine(12_340, "第二行"), lyric.lines[1])
        // .5 是十分之一秒，应解析为 500 毫秒
        assertEquals(LyricLine(65_500, "第三行"), lyric.lines[2])
    }

    @Test
    fun 一行多个时间戳会展开成多行() {
        val lyric = LyricParser.parse("[00:10.00][01:20.00]副歌")
        assertEquals(2, lyric.lines.size)
        assertEquals(10_000, lyric.lines[0].timeMs)
        assertEquals(80_000, lyric.lines[1].timeMs)
        assertTrue(lyric.lines.all { it.text == "副歌" })
    }

    @Test
    fun 三位小数按毫秒解析() {
        assertEquals(12_345, LyricParser.parse("[00:12.345]行").lines[0].timeMs)
    }

    @Test
    fun 冒号分隔的毫秒也能解析() {
        assertEquals(12_340, LyricParser.parse("[00:12:34]行").lines[0].timeMs)
    }

    @Test
    fun offset标签整体平移时间轴() {
        val lyric = LyricParser.parse("[offset:-500]\n[00:10.00]行")
        assertEquals(9_500, lyric.lines[0].timeMs)
    }

    @Test
    fun offset不会把时间压成负数() {
        val lyric = LyricParser.parse("[offset:-5000]\n[00:01.00]行")
        assertEquals(0, lyric.lines[0].timeMs)
    }

    @Test
    fun 只有时间没有文字的间奏行被丢弃() {
        val lyric = LyricParser.parse("[00:01.00]\n[00:05.00]有词")
        assertEquals(1, lyric.lines.size)
        assertEquals("有词", lyric.lines[0].text)
    }

    @Test
    fun 纯文本歌词标记为未同步并剔除元信息() {
        val lyric = LyricParser.parse("[ti:标题]\n第一行\n\n第二行")
        assertFalse(lyric.synced)
        assertEquals(listOf("第一行", "第二行"), lyric.lines.map(LyricLine::text))
    }

    @Test
    fun 空输入返回空歌词() {
        assertTrue(LyricParser.parse(null).isEmpty)
        assertTrue(LyricParser.parse("   ").isEmpty)
    }

    @Test
    fun 时间戳乱序的歌词会被排序() {
        val lyric = LyricParser.parse("[00:20.00]后\n[00:10.00]前")
        assertEquals(listOf("前", "后"), lyric.lines.map(LyricLine::text))
    }

    @Test
    fun 按进度定位当前行() {
        val lyric = LyricParser.parse("[00:00.00]甲\n[00:10.00]乙\n[00:20.00]丙")
        assertEquals(0, lyric.indexAt(0))
        assertEquals(0, lyric.indexAt(9_999))
        assertEquals(1, lyric.indexAt(10_000))
        assertEquals(2, lyric.indexAt(999_999))
    }

    @Test
    fun 进度早于第一行时返回负一() {
        val lyric = LyricParser.parse("[00:10.00]乙")
        assertEquals(-1, lyric.indexAt(0))
    }

    @Test
    fun 未同步歌词不参与定位() {
        val lyric = LyricParser.parse("没有时间轴的歌词")
        assertEquals(-1, lyric.indexAt(10_000))
    }
}
