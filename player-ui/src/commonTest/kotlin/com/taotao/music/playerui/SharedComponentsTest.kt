package com.taotao.music.playerui

import androidx.compose.material3.Typography
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoTypeScale
import com.taotao.music.playerui.theme.TaotaoTypography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 复合组件层（[SharedSectionHeader] / [SharedCard]）的结构约束。
 *
 * 断言针对**结构与来源**，不针对具体数值：调刻度不会让测试变红，
 * 但组件绕过刻度、或层级之间失去区分度，就会红。
 */
class SharedComponentsTest {

    private val allLevels = SharedSectionLevel.entries

    @Test
    fun sectionHeaderLevelsStayDistinct() {
        val sizes = allLevels.map { sectionTitleStyle(TaotaoTypography, it).fontSize }
        assertEquals(
            sizes.size,
            sizes.distinct().size,
            "三个层级的字号必须互不相同，否则「层级」形同虚设：$sizes",
        )
    }

    @Test
    fun sectionHeaderTitleSizeDecreasesWithLevel() {
        val page = sectionTitleStyle(TaotaoTypography, SharedSectionLevel.PAGE).fontSize!!.value
        val section = sectionTitleStyle(TaotaoTypography, SharedSectionLevel.SECTION).fontSize!!.value
        val card = sectionTitleStyle(TaotaoTypography, SharedSectionLevel.CARD).fontSize!!.value
        assertTrue(page > section, "页面标题必须大于区块标题：$page vs $section")
        assertTrue(section > card, "区块标题必须大于卡片标签：$section vs $card")
    }

    @Test
    fun sectionHeaderStylesComeFromDeclaredScale() {
        val declared = TaotaoTypeScale.sizes.map(Int::toFloat).toSet()
        allLevels.forEach { level ->
            val size = sectionTitleStyle(TaotaoTypography, level).fontSize!!.value
            assertTrue(size in declared, "$level 的字号 $size 不在 TaotaoTypeScale 上")
        }
    }

    @Test
    fun sectionHeaderNeverExceedsMaterialDefaults() {
        val defaults = Typography()
        allLevels.forEach { level ->
            val actual = sectionTitleStyle(TaotaoTypography, level).fontSize!!.value
            val material = sectionTitleStyle(defaults, level).fontSize!!.value
            assertTrue(
                actual <= material,
                "$level 的字号被放大了（$actual > Material 默认 $material），那是回归不是优化",
            )
        }
    }

    @Test
    fun sectionHeaderPaddingFollowsSpacingGrid() {
        val grid = TaotaoSpacing.spacingSteps.toSet()
        allLevels.forEach { level ->
            val top = sectionTopPadding(level)
            val bottom = sectionBottomPadding(level)
            assertTrue(top in grid, "$level 的上内边距 $top 不在 TaotaoSpacing 刻度上")
            assertTrue(bottom in grid, "$level 的下内边距 $bottom 不在 TaotaoSpacing 刻度上")
        }
    }

    @Test
    fun cardLevelLeavesPaddingToItsContainer() {
        assertEquals(
            TaotaoSpacing.none,
            sectionTopPadding(SharedSectionLevel.CARD),
            "CARD 档必须把留白交给容器（SharedCard），否则会出现双份内边距",
        )
        assertEquals(
            TaotaoSpacing.none,
            sectionBottomPadding(SharedSectionLevel.CARD),
            "CARD 档必须把留白交给容器（SharedCard），否则会出现双份内边距",
        )
    }

    /**
     * 页面标题的下内边距必须小于上内边距 —— 列表页标题若上下等量留白，
     * 会把第一首歌推得太远。这条约束原先散落在 `LibraryPageHeader` 的注释里。
     */
    @Test
    fun pageLevelKeepsBottomPaddingTighterThanTop() {
        val top = sectionTopPadding(SharedSectionLevel.PAGE)
        val bottom = sectionBottomPadding(SharedSectionLevel.PAGE)
        assertTrue(bottom < top, "PAGE 档下内边距 $bottom 应小于上内边距 $top")
    }

    /**
     * 语义别名必须指向刻度里的实例，不能各自 new 一个字面量 ——
     * 那样别名会与刻度悄悄漂移。
     */
    @Test
    fun shapeAliasesPointAtDeclaredSteps() {
        assertSame(TaotaoShapes.extraSmall, TaotaoShapes.badge)
        assertSame(TaotaoShapes.medium, TaotaoShapes.button)
        assertSame(TaotaoShapes.large, TaotaoShapes.artwork)
        assertSame(TaotaoShapes.large, TaotaoShapes.card)
    }

    /**
     * 卡片圆角必须落在应用实际使用的区间内。
     * 全应用在用 16–24dp，若 `card` 指向最大的 28dp 档，设置页卡片会突然变得过圆。
     */
    @Test
    fun cardRadiusStaysWithinInUseRange() {
        // card === large === radii[3]，先确认别名指向的就是 large 那一档。
        assertSame(TaotaoShapes.large, TaotaoShapes.card)
        val cardRadius = TaotaoShapes.radiusSteps[3]
        assertTrue(
            cardRadius.value in 16f..24f,
            "卡片圆角 $cardRadius 超出应用在用的 16–24dp 区间",
        )
    }
}
