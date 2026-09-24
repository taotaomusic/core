package com.taotao.music.playerui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设计 token 的回归护栏。
 *
 * ## 为什么值得写
 *
 * 引入 token 之前，项目里积累了 22 种字号和 12 种圆角 —— 不是一次写坏的，
 * 而是每次新页面「就近写一个差不多的数」慢慢长出来的。
 * 这组测试的作用不是验证数值本身对不对，而是**在下一次有人想加第 23 种字号时把他拦住**。
 *
 * 断言全部针对「结构」（档位数量、单调性、取值集合），不针对具体数值 ——
 * 调整刻度不应该让测试变红，破坏刻度结构才应该。
 */
class TaotaoTokensTest {

    /** 全部 15 个 Material 槽位。新增槽位时这里也要加，否则会漏检。 */
    private val typographySlots: List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to TaotaoTypography.displayLarge,
        "displayMedium" to TaotaoTypography.displayMedium,
        "displaySmall" to TaotaoTypography.displaySmall,
        "headlineLarge" to TaotaoTypography.headlineLarge,
        "headlineMedium" to TaotaoTypography.headlineMedium,
        "headlineSmall" to TaotaoTypography.headlineSmall,
        "titleLarge" to TaotaoTypography.titleLarge,
        "titleMedium" to TaotaoTypography.titleMedium,
        "titleSmall" to TaotaoTypography.titleSmall,
        "bodyLarge" to TaotaoTypography.bodyLarge,
        "bodyMedium" to TaotaoTypography.bodyMedium,
        "bodySmall" to TaotaoTypography.bodySmall,
        "labelLarge" to TaotaoTypography.labelLarge,
        "labelMedium" to TaotaoTypography.labelMedium,
        "labelSmall" to TaotaoTypography.labelSmall,
    )

    // ---- 排版 ----

    @Test
    fun typeScaleStaysConverged() {
        val sizes = TaotaoTypeScale.sizes
        assertTrue(
            sizes.size <= 12,
            "排版档位不应超过 12 级，当前 ${sizes.size} 级：$sizes。新增字号前先确认现有档位为什么不够用。",
        )
        assertStrictlyIncreasingInts(sizes, "排版档位")
    }

    @Test
    fun typographySlotsComeFromDeclaredScale() {
        val declared = TaotaoTypeScale.sizes.toSet()
        typographySlots.forEach { (name, style) ->
            val size = style.fontSize.value
            assertTrue(
                size.toInt().toFloat() == size && size.toInt() in declared,
                "$name 的字号 ${size}sp 不在 TaotaoTypeScale.sizes 里，请改用已有档位。",
            )
        }
    }

    @Test
    fun typographyUsesNoLetterSpacing() {
        typographySlots.forEach { (name, style) ->
            assertEquals(
                0f,
                style.letterSpacing.value,
                "$name 带了正字距。中文字形是满宽方块，正字距会让排版显得松散；见 TaotaoTypography 的说明。",
            )
        }
    }

    @Test
    fun typographyUsesOnlySafeCjkWeights() {
        typographySlots.forEach { (name, style) ->
            val weight = style.fontWeight
            assertTrue(
                weight == FontWeight.Normal || weight == FontWeight.Medium || weight == FontWeight.Bold,
                "$name 使用了 $weight。中文字体只稳定提供 400/500/700，" +
                    "600 会被不同 ROM 吸附到 500 或 700，导致同一套主题在不同设备上字重不一致。",
            )
        }
    }

    @Test
    fun typographyLineHeightExceedsFontSize() {
        typographySlots.forEach { (name, style) ->
            assertTrue(
                style.lineHeight.value > style.fontSize.value,
                "$name 的行距 ${style.lineHeight} 不大于字号 ${style.fontSize}。中文行距不足会显得拥挤。",
            )
        }
    }

    @Test
    fun typographyIsNotMaterialDefault() {
        val default = Typography()
        assertTrue(
            TaotaoTypography.displayLarge.fontSize != default.displayLarge.fontSize,
            "displayLarge 仍是 Material 默认的 57sp —— 说明 TaotaoTypography 没有真正接入主题。",
        )
        assertTrue(
            TaotaoTypography.bodyLarge.letterSpacing != default.bodyLarge.letterSpacing,
            "bodyLarge 的字距与 Material 默认相同 —— 中文排版约束没有生效。",
        )
    }

    @Test
    fun typographyNeverExceedsMaterialDefaults() {
        // 收敛只做「压缩」：任何槽位都不应比 Material 默认更大。
        // 页面布局是按默认字号排好的，字号一旦被放大就会撑开或换行 —— 那是回归不是优化。
        val default = Typography()
        val defaultSlots: List<TextStyle> = listOf(
            default.displayLarge,
            default.displayMedium,
            default.displaySmall,
            default.headlineLarge,
            default.headlineMedium,
            default.headlineSmall,
            default.titleLarge,
            default.titleMedium,
            default.titleSmall,
            default.bodyLarge,
            default.bodyMedium,
            default.bodySmall,
            default.labelLarge,
            default.labelMedium,
            default.labelSmall,
        )
        typographySlots.zip(defaultSlots).forEach { (slot, materialDefault) ->
            val (name, style) = slot
            assertTrue(
                style.fontSize.value <= materialDefault.fontSize.value,
                "$name 被放大到 ${style.fontSize}（Material 默认 ${materialDefault.fontSize}）。" +
                    "排版收敛只能压缩不能放大，否则会撑开按默认字号排好的页面。",
            )
        }
    }

    // ---- 形状 ----

    @Test
    fun shapeRadiiStayConverged() {
        val radii = TaotaoShapes.radiusSteps
        assertEquals(5, radii.size, "圆角只允许五档，当前：$radii")
        assertStrictlyIncreasingDps(radii, "圆角档位")
    }

    // ---- 间距 ----

    @Test
    fun spacingFollowsFourDpGrid() {
        val steps = TaotaoSpacing.spacingSteps
        assertStrictlyIncreasingDps(steps, "间距档位")
        steps.filter { it.value > 0f }.forEach { step ->
            assertEquals(
                0f,
                step.value % 4f,
                "间距 ${step} 不是 4dp 的整数倍。4dp 是密度无关的最小栅格，偏离它会破坏对齐。",
            )
        }
    }

    // ---- 尺寸 ----

    /**
     * 图标刻度必须「一眼能分辨」。
     *
     * 引入 token 之前，图标尺寸出现过 16 / 18 / 19 / 24 / 30 / 34 / 36 dp 七种写法，
     * 其中 18 与 19、34 与 36 只差 1–2dp —— 真机上根本分不出来，纯粹是漂移。
     * 所以这里要求相邻档位至少差 2dp，逼着后来者合并档位，而不是再插一档近似的。
     */
    @Test
    fun iconScaleStepsStayDistinguishable() {
        val steps = TaotaoSizes.iconScale
        assertTrue(steps.size <= 4, "图标只允许四档，当前 ${steps.size} 档：$steps")
        assertStrictlyIncreasingDps(steps, "图标尺寸档位")
        steps.zipWithNext().forEach { (small, large) ->
            assertTrue(
                large.value - small.value >= 2f,
                "图标档位 $small 与 $large 只差 ${large.value - small.value}dp，真机上无法分辨；" +
                    "应当合并成一档，而不是保留两个近似值。",
            )
        }
    }

    /**
     * 封面刻度对应三个真实场景，中间不再插值。
     *
     * 引入 token 之前，「列表行封面」这一个语义角色被写成 42 / 48 / 52 dp 三种尺寸，
     * 同一屏里出现两个就会显得不整齐。这条断言把三档与语义名的绑定关系固定下来。
     */
    @Test
    fun artworkScaleCoversThreeRealRoles() {
        val steps = TaotaoSizes.artworkScale
        assertEquals(3, steps.size, "封面只允许三档（列表行 / 网格 / 播放页），当前：$steps")
        assertStrictlyIncreasingDps(steps, "封面尺寸档位")
        assertEquals(TaotaoSizes.artworkRow, steps[0], "第一档必须是列表行封面。")
        assertEquals(TaotaoSizes.artworkGrid, steps[1], "第二档必须是网格封面。")
        assertEquals(TaotaoSizes.artworkHero, steps[2], "第三档必须是播放页大封面。")
    }

    /**
     * 触达区不能小于图标本身。
     *
     * `iconButton` 刻意**不在** [TaotaoSizes.iconScale] 里 —— 它描述的是触达区域，
     * 而不是图形大小，混进同一个刻度会让「图标该多大」失去唯一答案。
     * 这条断言把两者的关系单独固定下来。
     */
    @Test
    fun iconButtonIsLargerThanAnyIcon() {
        val largest = TaotaoSizes.iconScale.last()
        assertTrue(
            TaotaoSizes.iconButton > largest,
            "图标按钮触达区 ${TaotaoSizes.iconButton} 不大于最大图标 $largest，点击会难以命中。",
        )
    }

    /**
     * 播放控件自成一套比例：圆 > 圆内图标 > 页面标准图标。
     *
     * 这三个尺寸都不在 [TaotaoSizes.iconScale] 里，因为播放控件是**一整块**在缩放 ——
     * 把圆的直径塞进图标刻度，会让「图标该多大」多出一个毫无关系的候选值。
     * 这里固定的是它们彼此的嵌套关系，而不是具体数值。
     */
    @Test
    fun playControlScaleNests() {
        assertTrue(
            TaotaoSizes.playButtonIcon > TaotaoSizes.iconMd,
            "圆内播放图标 ${TaotaoSizes.playButtonIcon} 不大于标准图标 ${TaotaoSizes.iconMd}，" +
                "主操作会比页面图标还小。",
        )
        assertTrue(
            TaotaoSizes.playButton > TaotaoSizes.playButtonIcon,
            "播放按钮的圆 ${TaotaoSizes.playButton} 不大于圆内图标 ${TaotaoSizes.playButtonIcon}，" +
                "图标会顶到圆边。",
        )
    }

    /**
     * 脱离刻度的「角色尺寸」必须比同屏的普通图标更大。
     *
     * 进度圈与内容状态图示都不属于 [TaotaoSizes.iconScale]（它们不是图标），
     * 但如果比图标还小，就会被读成「又一个图标」，而不是「这里在加载 / 这里什么都没有」。
     */
    @Test
    fun offScaleRolesOutgrowPlainIcons() {
        assertTrue(
            TaotaoSizes.progressInline > TaotaoSizes.iconScale[1],
            "行内进度圈 ${TaotaoSizes.progressInline} 不比普通图标大，加载态会被误读成一个图标。",
        )
        val largestIcon = TaotaoSizes.iconScale.last()
        assertTrue(
            TaotaoSizes.stateIcon > largestIcon,
            "内容状态图示 ${TaotaoSizes.stateIcon} 不大于最大图标 $largestIcon，整块留白会显得局促。",
        )
        assertTrue(
            TaotaoSizes.stateIcon > TaotaoSizes.progressInline,
            "内容状态图示 ${TaotaoSizes.stateIcon} 不大于行内进度圈 ${TaotaoSizes.progressInline}，" +
                "两者会互相冒充，调用点就分不清该用哪个。",
        )
    }

    // ---- 描边 ----

    /**
     * 描边档位必须有序且不重复。
     *
     * 引入 [TaotaoStroke] 之前，分隔线、进度条、选中框各自写 `1.dp` / `2.dp` / `3.dp`，
     * 同一个「细线」在不同页面粗细不同。这条断言把档位数量固定下来。
     */
    @Test
    fun strokeScaleStaysOrdered() {
        val strokes = listOf(
            TaotaoStroke.hairline,
            TaotaoStroke.thin,
            TaotaoStroke.medium,
            TaotaoStroke.thick,
        )
        assertEquals(4, strokes.size, "描边只允许四档（发丝 / 细 / 中 / 粗），当前：$strokes")
        assertStrictlyIncreasingDps(strokes, "描边档位")
    }

    // ---- 层次 ----

    @Test
    fun elevationLevelsStayOrdered() {
        val levels = TaotaoElevation.levels
        assertEquals(
            4,
            levels.size,
            "层次只允许四级（平铺 / 卡片 / 悬浮 / 弹层），超过四级用户就分辨不出高低了。当前：$levels",
        )
        assertStrictlyIncreasingDps(levels, "层次档位")
        assertEquals(0.dp, levels.first(), "最低一级必须是 0dp，代表真正平铺的内容。")
    }

    // ---- 断言辅助 ----

    private fun assertStrictlyIncreasingDps(values: List<Dp>, label: String) {
        values.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher > lower, "$label 必须严格递增，但 $lower 之后是 $higher。")
        }
    }

    private fun assertStrictlyIncreasingInts(values: List<Int>, label: String) {
        values.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher > lower, "$label 必须严格递增，但 $lower 之后是 $higher。")
        }
    }
}
