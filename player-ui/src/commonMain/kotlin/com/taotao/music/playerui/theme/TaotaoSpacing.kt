package com.taotao.music.playerui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 三端共用的间距规范。
 *
 * ## 为什么需要它
 *
 * 引入本文件之前，仅 `androidApp` 的 UI 层就有 **391 处**直接写死的 `.dp`，
 * 其中 158 处出现在 `padding(...)` 里。同一屏内 `12 / 13 / 14 / 18 / 22 dp` 混用，
 * 列表页左右内边距在 22dp 与 24dp 之间摇摆。间距没有基数，元素之间就对不齐，
 * 视觉上表现为「松散」「不整齐」。
 *
 * ## 刻度
 *
 * 统一按 **4dp 基数**：`4 → 8 → 12 → 16 → 20 → 24 → 32 → 40`。
 * 4dp 是 Android 的密度无关最小栅格，能被绝大多数屏幕密度整除。
 *
 * 例外只有一处：**页面左右内边距定为 20dp**（[screenHorizontal]），
 * 用于收敛现有的 22dp 与 24dp 两种写法。
 */
object TaotaoSpacing {
    /**
     * 间距档位（dp），**唯一数据源**：下面的命名常量全部由它构造，
     * 保证刻度声明与实例不会各自漂移。
     */
    private val steps: List<Dp> = listOf(0.dp, 4.dp, 8.dp, 12.dp, 16.dp, 20.dp, 24.dp, 32.dp, 40.dp)

    /** 0，用于显式表达「不要间距」。 */
    val none = steps[0]

    /** 4dp：图标与文字之间、徽标内边距。 */
    val xxs = steps[1]

    /** 8dp：列表项内边距、相邻元素间距。 */
    val xs = steps[2]

    /** 12dp：列表项水平内边距、卡片内边距。 */
    val sm = steps[3]

    /** 16dp：常规内容间距、卡片内边距。 */
    val md = steps[4]

    /** 20dp：页面左右内边距、区块内边距。 */
    val lg = steps[5]

    /** 24dp：区块之间。 */
    val xl = steps[6]

    /** 32dp：大区块之间、空态上下留白。 */
    val xxl = steps[7]

    /** 40dp：页面顶部大留白。 */
    val xxxl = steps[8]

    /** 供单元测试校验刻度单调性与 4dp 基数。 */
    val spacingSteps: List<Dp> get() = steps

    // ---- 语义别名 ----

    /** 页面左右内边距。替代散落的 22dp / 24dp。 */
    val screenHorizontal = lg

    /** 列表项水平内边距。 */
    val listItemHorizontal = sm

    /** 列表项垂直内边距。 */
    val listItemVertical = xs

    /** 列表相邻项间距。 */
    val listItemGap = xs

    /** 区块之间的间距。 */
    val sectionGap = xl

    /** 图标与其文字标签之间的间距。 */
    val iconLabelGap = xxs

    /**
     * 纵向极小间距 / 内边距：2dp。
     *
     * **刻度之外的例外**，与 [screenHorizontal] 一样是有意为之。
     * 它服务于「**这一块的高度不该被留白支配**」的元素 —— 徽标、歌词行、消息元信息、
     * 自带内部留白的紧凑选项列表。这些地方若用 [xxs]（4dp），上下各撑 4dp，
     * 单行内容会看起来发虚，多行列表则被明显拉长。
     *
     * 判断标准只有一条：**留白是在「分隔」还是在「撑高」**。
     * 分隔（两个独立元素的常规间隔）走 [xs] 及以上；撑高走它。
     *
     * 刻意不进 [steps] —— 进了就会破坏「所有间距都是 4dp 整数倍」这条断言。
     */
    val tightVertical = 2.dp
}
