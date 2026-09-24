package com.taotao.music.playerui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 三端共用的圆角规范。
 *
 * ## 为什么需要它
 *
 * 引入本文件之前，全项目散落着 **12 种** `RoundedCornerShape` 字面量
 * （4 / 6 / 8 / 10 / 12 / 14 / 16 / 18 / 20 / 22 / 24 / 32 dp），彼此之间没有比例关系 ——
 * 14dp 出现 9 次、12dp 8 次、16dp 6 次、18dp 与 22dp 各 4 次。
 * 同一屏里出现 12 和 14 两种圆角，肉眼说不出哪里不对，但就是「不整齐」。
 *
 * 现在收敛为 **5 档**，比例约为 1.4 倍递进：`6 → 10 → 14 → 20 → 28`。
 *
 * ## 与旧 [AppleStyleTheme] 的关系
 *
 * 旧的四语义名（`ArtworkShape` / `CardShape` / `ButtonShape` / `FullRadius`）保留为
 * `@Deprecated` 别名并指向本对象，**调用点无需一次性改完**。
 * 数值有轻微收敛，这是有意的：
 *
 * | 旧名 | 旧值 | 新值 | 说明 |
 * | --- | --- | --- | --- |
 * | `ButtonShape` | 12dp | 14dp（[medium]） | 对齐到刻度中位数，也是全项目实际用得最多的一档 |
 * | `ArtworkShape` | 20dp | 20dp（[large]） | 不变 |
 * | `CardShape` | 32dp | 20dp（[large]） | 32dp 在刻度之外；全应用实际卡片圆角集中在 16–24dp，取 20dp 最贴合 |
 * | `FullRadius` | 50 | 50（[pill]） | 不变 |
 */
object TaotaoShapes {
    /**
     * 圆角档位（dp），**唯一数据源**：下面的形状实例全部由它构造，
     * 保证刻度声明与实例不会各自漂移。新增档位前先问：这五级为什么不够用。
     */
    private val radii: List<Dp> = listOf(6.dp, 10.dp, 14.dp, 20.dp, 28.dp)

    /** 极小元素：徽标、标签、进度条。 */
    val extraSmall = RoundedCornerShape(radii[0])

    /** 小元素：输入框、小按钮。 */
    val small = RoundedCornerShape(radii[1])

    /** 中元素：列表行、按钮、迷你播放器。全项目使用最多的一档。 */
    val medium = RoundedCornerShape(radii[2])

    /** 大元素：封面、卡片。 */
    val large = RoundedCornerShape(radii[3])

    /** 特大元素：页面级容器、底部弹层。 */
    val extraLarge = RoundedCornerShape(radii[4])

    /** 胶囊形：药丸按钮、筛选 chip。 */
    val pill = RoundedCornerShape(50)

    /** 供单元测试校验刻度单调性与档位数量。 */
    val radiusSteps: List<Dp> get() = radii

    // ---- 语义别名：组件里用这些名字，比 extraSmall / medium 更能表达意图 ----

    /** 徽标、标签。 */
    val badge = extraSmall

    /** 列表行、按钮、迷你播放器。 */
    val button = medium

    /** 专辑封面。 */
    val artwork = large

    /** 卡片、面板。 */
    val card = large

    /**
     * 接入 [androidx.compose.material3.MaterialTheme] 的形状实例。
     *
     * 五个槽位全部指向本项目刻度，**不使用 Material 3 的默认值** ——
     * 默认值（4 / 8 / 12 / 16 / 28 dp）会引入第六套圆角语言。
     */
    val material: Shapes = Shapes(
        extraSmall = extraSmall,
        small = small,
        medium = medium,
        large = large,
        extraLarge = extraLarge,
    )
}

/**
 * 描边宽度。
 *
 * 分隔线用 [hairline]：在 1x 到 3x 屏上都渲染为 1 物理像素左右，
 * 写成 1.dp 在高密度屏上会显得过重。
 */
object TaotaoStroke {
    /** 分隔线、卡片描边。 */
    val hairline = 0.5.dp

    /** 进度条轨道、选中框。 */
    val thin = 1.dp

    /** 行内进度圈：直径只有 20dp 左右，用 1dp 会细到看不见，3dp 又糊成一团。 */
    val medium = 2.dp

    /** 进度条本体、强调描边。 */
    val thick = 3.dp
}
