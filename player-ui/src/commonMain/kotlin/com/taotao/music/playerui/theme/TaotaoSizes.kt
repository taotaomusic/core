package com.taotao.music.playerui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 三端共用的**组件固有尺寸**规范。
 *
 * ## 为什么它和 [TaotaoSpacing] 分开
 *
 * 间距描述「元素之间留多少空」，尺寸描述「元素本身多大」。两者都被写成裸 `.dp`，
 * 但只有前者该按 4dp 栅格收敛 —— 把一个 132dp 的封面塞进 `TaotaoSpacing` 会让
 * 间距刻度失去意义。所以单独成表。
 *
 * ## 为什么需要它
 *
 * 引入本文件之前，专辑封面在四个调用点用了 **四种尺寸**：
 *
 * | 调用点 | 原尺寸 | 占位音符 |
 * | --- | --- | --- |
 * | `PlaylistPages.kt` 歌单详情行 | 42dp | 20sp |
 * | `TaotaoMusicApp.kt` 播放队列 | 48dp | 24sp |
 * | `components.kt` `SongRow` 歌曲行 | 52dp | 24sp |
 * | `TaotaoMusicApp.kt` 网格卡片 | 112dp | 64sp |
 *
 * 前三者是**同一个语义角色**（列表行封面），却写了三个数。同一屏里出现 42 和 52，
 * 肉眼说不出哪里不对，但就是「不整齐」。现在收敛为 [artworkRow] 一档。
 *
 * 同理，图标尺寸原本散落 16 / 18 / 19 / 24 / 30 / 34 / 36 dp 七种写法，
 * 其中 18 与 19 只差 1dp、34 与 36 只差 2dp，纯属漂移。
 *
 * ## 与刻度对象的关系
 *
 * 与 [TaotaoSpacing] / [TaotaoShapes] 一致：真实数值集中在私有列表里，
 * 下面的常量全部由它构造，保证「声明」与「实例」不会各自漂移。
 */
object TaotaoSizes {
    /**
     * 图标边长刻度（dp），唯一数据源。
     *
     * 只保留四档：`16 → 18 → 24 → 30`。**不要新增 19 / 20 / 22 这类档位** ——
     * 相差 1–2dp 的图标尺寸在真机上无法分辨，只会让后续维护者不知道该用哪个。
     */
    private val icons: List<Dp> = listOf(16.dp, 18.dp, 24.dp, 30.dp)

    /** 16dp：行内极小图标，例如加载指示器、文本框尾部的清除图标。 */
    val iconXs = icons[0]

    /** 18dp：列表行内图标、菜单项前缀图标。收敛原来的 18dp 与 19dp。 */
    val iconSm = icons[1]

    /** 24dp：标准图标，与 Material 默认的 `Icon` 尺寸一致。 */
    val iconMd = icons[2]

    /** 30dp：大号图标，例如头像里的占位人形。 */
    val iconLg = icons[3]

    /**
     * 36dp：图标按钮的可点击区、图标底衬圆，以及播放控件里上/下一首的图标。
     *
     * 不放进 [icons] 刻度 —— 前两者描述的是**触达区域**而不是图形大小，
     * 两件事混在一个刻度里会让「图标该多大」这个问题失去唯一答案。
     *
     * 上/下一首是这条规则里唯一的例外：它的图形恰好也是 36dp，且与圆内的
     * [playButtonIcon] 共同构成播放控件自己的比例。给它单开一个同值 token
     * 只会让人不知道该用哪个，所以在这里显式记下这个例外。
     */
    val iconButton = 36.dp

    /**
     * 20dp：行内加载指示器 —— 按钮里的进度圈、列表底部的「正在加载更多」。
     *
     * 也不放进 [icons]：进度圈表达的是「等待中的占位」，不是图标。
     * 它与 [iconSm]（18dp）只差 2dp，正因为数值接近，才更需要靠语义名区分，
     * 免得调用点随手挑一个。
     */
    val progressInline = 20.dp

    /**
     * 44dp：内容状态（加载 / 空 / 失败）的图示尺寸。
     *
     * 三种状态共用同一个尺寸，**切换状态时内容不会上下跳**。
     * 引入本条目之前它们分别是 36 / 44 / 44 dp —— 光是「加载完成」这一步就会让标题移动 8dp。
     */
    val stateIcon = 44.dp

    /**
     * 封面尺寸刻度（dp），唯一数据源：`48 → 112 → 132`。
     *
     * 三档对应三个真实场景，中间不再插值 —— 若某个新页面觉得 48 太小，
     * 先确认它是不是真的不属于这三类。
     */
    private val artworks: List<Dp> = listOf(48.dp, 112.dp, 132.dp)

    /** 48dp：列表行封面。收敛原来的 42dp / 48dp / 52dp 三种写法。 */
    val artworkRow = artworks[0]

    /** 112dp：网格与卡片封面。 */
    val artworkGrid = artworks[1]

    /** 132dp：播放页大封面。 */
    val artworkHero = artworks[2]

    /** 88dp：品牌标识，用于登录页等非列表场景。 */
    val artworkBrand = 88.dp

    /** 64dp：个人页头像。收敛原来的 62dp。 */
    val avatar = 64.dp

    /**
     * 64dp：播放按钮的圆形底衬。
     *
     * 与 [avatar] 数值相同但**不是同一件事**，所以不共用名字：
     * 头像跟着列表布局走，播放按钮跟着播放控件整体缩放，
     * 两者将来任何一方调整都不该带上另一方。
     */
    val playButton = 64.dp

    /**
     * 32dp：播放按钮圆内的播放 / 暂停图标。
     *
     * 不放进 [icons] 刻度 —— 它属于播放控件自己的一套比例
     * （[playButton] 的圆 → 圆内图标 → [iconButton] 尺寸的上/下一首），
     * 混进页面图标刻度会让「这个图标该多大」两边都说不清。
     */
    val playButtonIcon = 32.dp

    /** 供单元测试校验刻度单调性、栅格对齐与档位数量。 */
    val iconScale: List<Dp> get() = icons

    /** 供单元测试校验封面刻度。 */
    val artworkScale: List<Dp> get() = artworks
}
