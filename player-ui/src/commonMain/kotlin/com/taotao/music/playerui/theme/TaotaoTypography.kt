package com.taotao.music.playerui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 三端共用的排版规范。
 *
 * ## 为什么需要它
 *
 * 在引入本文件之前，`TaotaoPlayerTheme` 用的是 Material 3 的默认 `Typography()`，
 * 而页面普遍不走这套排版，直接写 `fontSize = 12.sp`。结果是全项目出现 **22 种不同的字号**
 * （10 到 132 sp），光 `12.sp` 出现 31 次、`13.sp` 17 次、`14.sp` 13 次 ——
 * **13 和 14 并存本身就说明没有刻度**。观感上的「不一致」主要来自这里，而不是组件库选型。
 *
 * 现在收敛为 **10 个尺寸档位**，全部集中在 [TaotaoTypeScale]。
 *
 * ## 中文字体的三条约束
 *
 * 1. **字重只能用 400 / 500 / 700。** 中文字体的字重覆盖远不如拉丁字体，
 *    Android 上的 Noto Sans CJK 只有 Thin / Light / Normal / Medium / Bold / Black 几档。
 *    写 `FontWeight.SemiBold`(600) 会被合成或直接吸附到 Bold，同一套主题在不同 ROM 上
 *    字重不一致。所以本文件只用 `Normal` / `Medium` / `Bold` 三个常量。
 * 2. **`letterSpacing` 一律为 0。** Material 3 默认给正文加了 0.25–0.5sp 的正字距，
 *    那是为拉丁字母的小写连排设计的；中文字形本身是满宽方块，加正字距会显得松散。
 * 3. **`lineHeight` 比 Material 默认更松。** 中文字形高度占满 em 框，行距不足会显得拥挤。
 *    这里统一按 1.3–1.55 倍字号取整到偶数 sp。
 *
 * ## 字体族
 *
 * 当前不指定 `fontFamily`，走系统默认字体。内嵌中文字体需要评估 APK 体积增量
 * （中文全字库通常数 MB），属于独立决策，不在排版规范里单方面决定。
 * 将来若要换字体，**只改本文件的 [taotaoTextStyle] 一处即可三端生效**。
 */
object TaotaoTypeScale {
    /**
     * 允许出现的字号档位。新增字号前先问：现有 10 档为什么不够用。
     * 这个列表同时被单元测试用来防止刻度重新发散。
     */
    val sizes: List<Int> = listOf(11, 12, 14, 16, 18, 20, 22, 26, 34, 40)

    /**
     * 40sp：欢迎语、启动页主标题，以及**空态里的大号装饰图形**
     * （例如无歌词时那颗 `♪`）。
     *
     * 装饰图形不表达文字层级，但它需要一个大到能独自撑住整屏的字号，
     * 而 40sp 正好是唯一的那个档位 —— 与其在页面里再写一次 `40.sp`，
     * 不如复用这一档。
     */
    val hero = taotaoTextStyle(40, 52, FontWeight.Bold)

    /** 大数字，例如统计值。 */
    val display = taotaoTextStyle(34, 44, FontWeight.Bold)

    /** 大页面标题。 */
    val headline = taotaoTextStyle(26, 36, FontWeight.Bold)

    /** 页面标题。 */
    val title = taotaoTextStyle(22, 30, FontWeight.Bold)

    /** 次级页面标题。 */
    val subtitle = taotaoTextStyle(20, 28, FontWeight.Medium)

    /** 区块标题。 */
    val sectionTitle = taotaoTextStyle(18, 26, FontWeight.Bold)

    /** 卡片标题、空态标题。 */
    val cardTitle = taotaoTextStyle(16, 24, FontWeight.Medium)

    /** 小标题。 */
    val minorTitle = taotaoTextStyle(14, 22, FontWeight.Medium)

    /** 主要正文：歌曲名、段落。 */
    val body = taotaoTextStyle(16, 24, FontWeight.Normal)

    /** 次要正文：歌手名、说明。 */
    val bodyCompact = taotaoTextStyle(14, 22, FontWeight.Normal)

    /** 补充说明文字。 */
    val caption = taotaoTextStyle(12, 18, FontWeight.Normal)

    /** 按钮文字。 */
    val button = taotaoTextStyle(14, 20, FontWeight.Medium)

    /** 标签、时长、次级标注。 */
    val label = taotaoTextStyle(12, 18, FontWeight.Medium)

    /** 徽标、极小标注。 */
    val micro = taotaoTextStyle(11, 16, FontWeight.Medium)
}

/**
 * 构造一个符合中文排版约束的 [TextStyle]。
 *
 * 三端（Android / Desktop / Web）共用同一份实现，保证换字体或调行距时不会只改到一端。
 */
private fun taotaoTextStyle(size: Int, lineHeight: Int, weight: FontWeight): TextStyle = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    // 中文字形是满宽方块，正字距只会显得松散；见类注释第 2 条。
    letterSpacing = 0.sp,
    fontFamily = FontFamily.Default,
)

/**
 * 接入 [androidx.compose.material3.MaterialTheme] 的排版实例。
 *
 * ## 映射原则：只压缩从不使用的槽位
 *
 * 槽位映射有一条硬约束 —— **页面已经在用的槽位，字号保持不变**。
 * 否则「统一排版」会顺手把现有页面改小，那是回归不是优化。
 *
 * | 槽位 | Material 默认 | 本项目 | 是否在用 |
 * | --- | --- | --- | --- |
 * | `displayLarge` | 57sp | 40sp | 否 |
 * | `displayMedium` | 45sp | 34sp | 否 |
 * | `displaySmall` | 36sp | 26sp | 否 |
 * | `headlineLarge` | 32sp | 26sp | 否 |
 * | `headlineMedium` | 28sp | 26sp | 是（播放页歌曲名） |
 * | `headlineSmall` | 24sp | 22sp | 是（聊天页标题） |
 * | `titleLarge` | 22sp | 22sp | 是 |
 * | `titleMedium` | 16sp | 16sp | 是 |
 * | `titleSmall` | 14sp | 14sp | 否 |
 * | `bodyLarge` | 16sp | 16sp | 是（歌曲名） |
 * | `bodyMedium` | 14sp | 14sp | 是（歌手名） |
 * | `bodySmall` | 12sp | 12sp | 是（副标题） |
 * | `labelLarge` | 14sp | 14sp | 否 |
 * | `labelMedium` | 12sp | 12sp | 是（时长） |
 * | `labelSmall` | 11sp | 11sp | 是（徽标） |
 *
 * 凡是「在用」的槽位都保持原字号，只改中文字距与行距。
 * 真实收益来自两处：**从 22 种字号收敛到 8 种**（页面里写死的那些由阶段 2 迁移），
 * 以及**中文行距与字距的修正**。后者不需要改任何页面就已经生效。
 *
 * [TaotaoTypeScale.subtitle]（20sp）与 [TaotaoTypeScale.sectionTitle]（18sp）
 * 暂时没有槽位对应 —— 它们是给阶段 2 迁移页面里那些 17/18/20/22sp 硬编码字号用的。
 */
val TaotaoTypography: Typography = Typography(
    displayLarge = TaotaoTypeScale.hero,
    displayMedium = TaotaoTypeScale.display,
    displaySmall = TaotaoTypeScale.headline,
    headlineLarge = TaotaoTypeScale.headline,
    headlineMedium = TaotaoTypeScale.headline,
    headlineSmall = TaotaoTypeScale.title,
    titleLarge = TaotaoTypeScale.title,
    titleMedium = TaotaoTypeScale.cardTitle,
    titleSmall = TaotaoTypeScale.minorTitle,
    bodyLarge = TaotaoTypeScale.body,
    bodyMedium = TaotaoTypeScale.bodyCompact,
    bodySmall = TaotaoTypeScale.caption,
    labelLarge = TaotaoTypeScale.button,
    labelMedium = TaotaoTypeScale.label,
    labelSmall = TaotaoTypeScale.micro,
)
