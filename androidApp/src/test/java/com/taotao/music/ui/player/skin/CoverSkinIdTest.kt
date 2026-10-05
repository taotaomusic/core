package com.taotao.music.ui.player.skin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 皮肤注册表回归测试：新增皮肤时枚举值会被顺手改动，
 * 这组用例守住「标签唯一且非空、名称可无损往返、默认值稳定」三条底线。
 */
class CoverSkinIdTest {

    @Test
    fun `每个皮肤的中文标签唯一且非空`() {
        val labels = CoverSkinId.entries.map { it.label }
        assertTrue(labels.all { it.isNotBlank() }, "皮肤标签不允许为空：$labels")
        assertEquals(labels.size, labels.toSet().size, "皮肤标签不允许重复：$labels")
    }

    @Test
    fun `名称解析可以无损往返`() {
        for (skin in CoverSkinId.entries) {
            assertEquals(skin, CoverSkinId.of(skin.name), "皮肤 ${skin.name} 无法经 of() 往返解析")
        }
    }

    @Test
    fun `未知名称与空值回退默认皮肤`() {
        assertEquals(CoverSkinId.DEFAULT, CoverSkinId.of("not-a-skin"))
        assertEquals(CoverSkinId.DEFAULT, CoverSkinId.of(null))
        assertEquals(CoverSkinId.of(null), CoverSkinId.of(""))
    }

    @Test
    fun `默认皮肤是枚举成员`() {
        assertTrue(CoverSkinId.entries.contains(CoverSkinId.DEFAULT))
    }
}
