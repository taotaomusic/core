package com.taotao.music.shared.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * wasm 门面回归测试：门面是 Kotlin 逻辑与 TS 消费侧的唯一契约层，
 * 用例守住「JSON 结构稳定、缓存时序正确、空态安全、转义无损」四条底线。
 * 门面编译为顶层 @JsExport 函数（wasm 不允许导出 standalone object），
 * 测试直接调用同名顶层函数 —— 与 JS 侧看到的形态完全一致。
 */
class PlayerFacadeTest {

    @Test
    fun `loadLyric 返回的 JSON 可按契约解析出全部字段`() {
        val json = loadLyric("[00:01.00]第一行\n[00:03.00]第二行", null)
        assertTrue("\"synced\":true" in json, "synced 字段缺失：$json")
        assertTrue("\"timeMs\":1000" in json, "行时间缺失：$json")
        // LRC 没有字级时间，words 必须是空数组（TS 侧据此走整行高亮分支）。
        assertTrue("\"words\":[]" in json, "空 words 应序列化为空数组：$json")
    }

    @Test
    fun `查询函数走缓存 - load 之后才反映新歌`() {
        loadLyric("[00:10.00]甲", null)
        assertEquals(0, lyricIndexAt(10_500))
        loadLyric("[00:30.00]乙", null)
        // 缓存已被新歌词替换：旧歌的 10.5s 现在落在第一行之前，返回 -1。
        assertEquals(-1, lyricIndexAt(10_500))
        assertEquals(0, lyricIndexAt(30_500))
    }

    @Test
    fun `未加载时查询安全返回空态`() {
        // 门面在进程内可能被其他用例加载过，这里刻意 load 一份空歌词复位。
        loadLyric(null, null)
        assertEquals(-1, lyricIndexAt(999))
        assertEquals(0.0, lyricProgressOf(0, 999))
    }

    @Test
    fun `逐字进度按时长线性插值且收敛到 0..1`() {
        loadLyric("[00:00.00]整行", null)
        assertEquals(0.0, lyricProgressOf(0, 0))
        // 无字级数据时按行时长线性：最后一行时长未知，恒为 1。
        assertEquals(1.0, lyricProgressOf(0, 60_000))
    }

    @Test
    fun `JSON 特殊字符转义 - 引号反斜杠与控制字符`() {
        val json = loadLyric("[00:01.00]带\"引号\"与\\与\n换行", null)
        assertTrue("带\\\"引号\\\"与\\\\与\\n换行" in json, "转义不正确：$json")
    }

    @Test
    fun `音质门面 - 档位名 默认值与无损判定`() {
        assertEquals("SQ 无损", qualityLabel(10))
        assertEquals("音质 99", qualityLabel(99))
        assertEquals(8, defaultQualityValue())
        assertTrue(isLossless(10))
        assertTrue(!isLossless(99))
    }
}
