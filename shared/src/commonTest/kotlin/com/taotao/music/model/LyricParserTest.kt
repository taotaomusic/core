package com.taotao.music.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricParserTest {

    @Test
    fun 解析带时间轴的歌词() {
        val lyric = LyricParser.parseLrc(
            """
            [ti:测试歌曲]
            [ar:测试歌手]
            [00:01.00]第一行
            [00:12.34]第二行
            [01:05.5]第三行
            """.trimIndent(),
        )
        assertTrue(lyric.synced)
        assertEquals(listOf(1_000, 12_340, 65_500), lyric.lines.map(LyricLine::timeMs))
        assertEquals(listOf("第一行", "第二行", "第三行"), lyric.lines.map(LyricLine::text))
    }

    @Test
    fun LRC的行时长由下一行起始时间补出() {
        val lyric = LyricParser.parseLrc("[00:01.00]甲\n[00:03.00]乙")
        assertEquals(2_000, lyric.lines[0].durationMs)
        // 最后一行没有下一行可参照，时长保持未知
        assertEquals(0, lyric.lines[1].durationMs)
    }

    @Test
    fun 一行多个时间戳会展开成多行() {
        val lyric = LyricParser.parseLrc("[00:10.00][01:20.00]副歌")
        assertEquals(2, lyric.lines.size)
        assertEquals(10_000, lyric.lines[0].timeMs)
        assertEquals(80_000, lyric.lines[1].timeMs)
        assertTrue(lyric.lines.all { it.text == "副歌" })
    }

    @Test
    fun 三位小数按毫秒解析() {
        assertEquals(12_345, LyricParser.parseLrc("[00:12.345]行").lines[0].timeMs)
    }

    @Test
    fun 冒号分隔的毫秒也能解析() {
        assertEquals(12_340, LyricParser.parseLrc("[00:12:34]行").lines[0].timeMs)
    }

    @Test
    fun offset标签整体平移时间轴() {
        assertEquals(9_500, LyricParser.parseLrc("[offset:-500]\n[00:10.00]行").lines[0].timeMs)
    }

    @Test
    fun offset不会把时间压成负数() {
        assertEquals(0, LyricParser.parseLrc("[offset:-5000]\n[00:01.00]行").lines[0].timeMs)
    }

    @Test
    fun 只有时间没有文字的间奏行被丢弃() {
        val lyric = LyricParser.parseLrc("[00:01.00]\n[00:05.00]有词")
        assertEquals(1, lyric.lines.size)
        assertEquals("有词", lyric.lines[0].text)
    }

    @Test
    fun 纯文本歌词标记为未同步并剔除元信息() {
        val lyric = LyricParser.parseLrc("[ti:标题]\n第一行\n\n第二行")
        assertFalse(lyric.synced)
        assertEquals(listOf("第一行", "第二行"), lyric.lines.map(LyricLine::text))
    }

    @Test
    fun 空输入返回空歌词() {
        assertTrue(LyricParser.parseLrc(null).isEmpty)
        assertTrue(LyricParser.parseLrc("   ").isEmpty)
        assertTrue(LyricParser.parseYrc(null).isEmpty)
        assertTrue(LyricParser.parse(null, null).isEmpty)
    }

    @Test
    fun 时间戳乱序的歌词会被排序() {
        val lyric = LyricParser.parseLrc("[00:20.00]后\n[00:10.00]前")
        assertEquals(listOf("前", "后"), lyric.lines.map(LyricLine::text))
    }

    @Test
    fun 按进度定位当前行() {
        val lyric = LyricParser.parseLrc("[00:00.00]甲\n[00:10.00]乙\n[00:20.00]丙")
        assertEquals(0, lyric.indexAt(0))
        assertEquals(0, lyric.indexAt(9_999))
        assertEquals(1, lyric.indexAt(10_000))
        assertEquals(2, lyric.indexAt(999_999))
    }

    @Test
    fun 进度早于第一行时返回负一() {
        assertEquals(-1, LyricParser.parseLrc("[00:10.00]乙").indexAt(0))
    }

    @Test
    fun 未同步歌词不参与定位() {
        assertEquals(-1, LyricParser.parseLrc("没有时间轴的歌词").indexAt(10_000))
    }

    // —— 逐字歌词（YRC）——

    @Test
    fun 解析逐字歌词的行与字时间() {
        val lyric = LyricParser.parseYrc("[ti:晴天]\n[0,2250]晴(0,160)天(160,160)")
        assertTrue(lyric.synced)
        assertTrue(lyric.hasWords)
        assertEquals(1, lyric.lines.size)
        val line = lyric.lines[0]
        assertEquals(0, line.timeMs)
        assertEquals(2_250, line.durationMs)
        assertEquals("晴天", line.text)
        assertEquals(listOf(LyricWord(0, 160, "晴"), LyricWord(160, 160, "天")), line.words)
    }

    @Test
    fun 逐字单元可以是英文单词() {
        val lyric = LyricParser.parseYrc("[1600,500]Jay(1600,160) (1760,160)Chou(1920,160)")
        assertEquals(listOf("Jay", " ", "Chou"), lyric.lines[0].words.map(LyricWord::text))
        assertEquals("Jay Chou", lyric.lines[0].text)
    }

    @Test
    fun 逐字单元尾部多余数字不影响解析() {
        val lyric = LyricParser.parseYrc("[0,500]甲(0,160,0)乙(160,160,0)")
        assertEquals(2, lyric.lines[0].words.size)
        assertEquals(160, lyric.lines[0].words[1].timeMs)
    }

    @Test
    fun 歌词里字面的左括号不能被丢掉() {
        // 取自线上真实数据（id=645819 雨爱）。左括号自己也是一个带时间的逐字单元，
        // 用「文本 + 时间组」的正则去捕获文本时，文本组必须排除 '(' 才不会吞掉时间组的
        // 左括号，结果字面的 '(' 永远匹配不上，只剩一个孤立的右括号。
        val lyric = LyricParser.parseYrc(
            "[0,3320]雨(0,237)爱(237,237) (474,237)-(711,237) (948,237)杨(1185,237)丞(1422,237)" +
                "琳(1659,237) (1896,237)((2133,237)Rainie(2370,237) (2607,237)Yang(2844,237))(3081,237)",
        )
        assertEquals("雨爱 - 杨丞琳 (Rainie Yang)", lyric.lines[0].text)
        assertEquals(LyricWord(2_133, 237, "("), lyric.lines[0].words.single { it.text == "(" })
        assertEquals(LyricWord(3_081, 237, ")"), lyric.lines[0].words.last())
    }

    @Test
    fun 括号出现在字中间也能保留() {
        val lyric = LyricParser.parseYrc("[0,500]a(b(0,250)c)d(250,250)")
        assertEquals("a(bc)d", lyric.lines[0].text)
    }

    @Test
    fun 优先使用逐字歌词其次退回LRC() {
        val withYrc = LyricParser.parse("[00:01.00]来自LRC", "[0,500]来(0,250)自(250,250)")
        assertTrue(withYrc.hasWords)
        assertEquals("来自", withYrc.lines[0].text)

        val onlyLrc = LyricParser.parse("[00:01.00]来自LRC", "")
        assertFalse(onlyLrc.hasWords)
        assertEquals("来自LRC", onlyLrc.lines[0].text)
    }

    @Test
    fun 逐字进度按字宽累计() {
        // 两个字各 500 毫秒，行从 0 开始
        val lyric = LyricParser.parseYrc("[0,1000]甲(0,500)乙(500,500)")
        assertEquals(0f, lyric.progressOf(0, 0))
        // 第一个字唱到一半：0.5 个字 / 共 2 字 = 0.25
        assertEquals(0.25f, lyric.progressOf(0, 250))
        // 第一个字唱完
        assertEquals(0.5f, lyric.progressOf(0, 500))
        assertEquals(1f, lyric.progressOf(0, 1_000))
        assertEquals(1f, lyric.progressOf(0, 9_999))
    }

    @Test
    fun 长短不一的字按字数而非时长分配宽度() {
        // "Jay" 三个字符 250 毫秒，"周" 一个字符 250 毫秒，总共 4 个字符
        val lyric = LyricParser.parseYrc("[0,500]Jay(0,250)周(250,250)")
        assertEquals(0.75f, lyric.progressOf(0, 250))
    }

    @Test
    fun 无逐字数据时按行时长插值() {
        val lyric = LyricParser.parseLrc("[00:00.00]甲乙丙\n[00:10.00]丁")
        assertEquals(0.5f, lyric.progressOf(0, 5_000))
        assertEquals(1f, lyric.progressOf(0, 10_000))
    }

    @Test
    fun 行时长未知时整行视为已唱完() {
        val lyric = LyricParser.parseLrc("[00:00.00]唯一一行")
        assertEquals(0, lyric.lines[0].durationMs)
        assertEquals(1f, lyric.progressOf(0, 1))
    }

    @Test
    fun 进度未到本行时为零() {
        val lyric = LyricParser.parseYrc("[5000,1000]甲(5000,500)乙(5500,500)")
        assertEquals(0f, lyric.progressOf(0, 0))
        assertEquals(0f, lyric.progressOf(0, 5_000))
    }

    @Test
    fun 逐字歌词的offset同时平移行与字() {
        val lyric = LyricParser.parseYrc("[offset:-500]\n[1000,500]甲(1000,250)乙(1250,250)")
        assertEquals(500, lyric.lines[0].timeMs)
        assertEquals(listOf(500, 750), lyric.lines[0].words.map(LyricWord::timeMs))
    }

    @Test
    fun 越界行下标返回零而不抛异常() {
        val lyric = LyricParser.parseYrc("[0,500]甲(0,500)")
        assertEquals(0f, lyric.progressOf(-1, 100))
        assertEquals(0f, lyric.progressOf(99, 100))
    }
}
