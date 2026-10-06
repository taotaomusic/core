package com.taotao.music.playerui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 三端共用的**语义色**规范。
 *
 * ## 为什么需要它
 *
 * 视觉走查（2026-10）发现的两类系统性问题都源于「token 层缺位」：
 *
 * 1. **无封面占位色靠注释人肉对齐** —— `0xFFFFB4A2` 在专辑页、歌手页、搜索页复制了 4 处，
 *    注释写着「颜色沿用歌曲行封面的占位色」，但没有任何机制保证下一处复制不走样。
 * 2. **alpha 修饰散装化** —— `onSurface` / `onSurfaceVariant` 的 `copy(alpha = ...)` 在
 *    7 个文件出现 30 余次，透明度档位多达 14 种（0.08 到 0.8），正是当年「22 种字号」
 *    的故事在颜色上重演。同一屏里「灰得不一样」是人感知「不精致」的第一来源。
 *
 * ## 用法规则（页面与组件必须遵守）
 *
 * - **次要文字一律 `MaterialTheme.colorScheme.onSurfaceVariant`**，不要用
 *   `onSurface.copy(alpha = ...)` 自行调灰。亮暗两套配色里 onSurfaceVariant 都已经
 *   调过对比度（见 [com.taotao.music.playerui.PlayerTheme.kt] 的说明）。
 * - **彩底容器上的次要文字**（primaryContainer 等染色底）用 [TaotaoTextAlpha]，
 *   因为 onSurfaceVariant 的灰压在染色底上会发脏。
 * - **背景压色**（chip 淡底、按压底纹、视频遮罩）用 [TaotaoWash] 的具名档位。
 * - **无封面占位**一律 [TaotaoColors.placeholderArtwork]，禁止再复制色值。
 * - **视频浮层文字**一律 [TaotaoColors.videoOn] / [TaotaoColors.videoOnMuted]，
 *   视频封面明暗不受主题控制，白色系必须独立成对。
 *
 * 之前散落的档位 → 新档位的映射写在各常量的文档里，供迁移时对照。
 */
object TaotaoColors {

    /**
     * 无封面时的占位底色：浅珊瑚粉。
     *
     * 收编了 AlbumPage、ArtistPage、SearchPage 共 4 处裸写的 `0xFFFFB4A2`。
     * 取自品牌珊瑚色的低饱和近亲，与主色同族但足够浅，叠白色音符字形可读。
     * 若要调色，只改这里 —— 全部占位封面一起变。
     */
    val placeholderArtwork = Color(0xFFFFB4A2)

    /**
     * 视频浮层上的主文字：纯白。
     *
     * MV 播放页此前用 `Color.White` / `Color.White.copy(alpha = 0.3~0.85)` 共 13 处，
     * 对比度完全靠运气。现在文字只允许两档：主信息用本值，次要信息用 [videoOnMuted]。
     */
    val videoOn = Color.White

    /**
     * 视频浮层上的次要文字：70% 白。
     *
     * 收编了 MV 页 0.65 / 0.75 两档近似的次要白（真机上不可分辨，收敛为一档）。
     * 比 0.85 以下的值都暗，但压在纯黑遮罩上仍满足对比度。
     */
    val videoOnMuted = Color.White.copy(alpha = 0.7f)
}

/**
 * 背景压色档位（透明度只用于**背景与遮罩**，文字灰度见 [TaotaoTextAlpha]）。
 *
 * 收敛前散落 14 档，收敛后 4 档，映射关系：
 *
 * | 旧值 | 去向 |
 * | --- | --- |
 * | 0.08 / 0.12 / 0.18 | [TaotaoWash.subtle] 或 [TaotaoWash.tint]（就近归档） |
 * | 0.22 / 0.25 / 0.32 | [TaotaoWash.selected] |
 * | 0.3 / 0.35 / 0.4（黑遮罩） | [TaotaoWash.scrim] |
 * | 0.45 / 0.5（文字） | 不属于本表：改用 `onSurfaceVariant`，见文件头规则 |
 *
 * 相邻档位至少相差 0.08 —— 真机上 0.05 以内的透明度差分辨不出来，
 * 保留两个近似档只会让调用点不知道该用哪个。
 */
object TaotaoWash {
    /**
     * 6%：极淡底纹。按压反馈、卡片内再分块的底、骨架屏底色。
     * 收编 0.08 / 0.12 两档中「只是想比背景深一点」的用法。
     */
    const val subtle = 0.06f

    /**
     * 14%：淡色底，主色与灰色通用。品牌色 chip、高潮区间标签底、不可播徽标的灰淡底。
     * 收编 0.12 / 0.18 两档。
     */
    const val tint = 0.14f

    /** 22%：选中态底色。筛选芯片选中、列表行高亮的底。收编 0.22 / 0.25 / 0.32 三档，取其下限。 */
    const val selected = 0.22f

    /** 35%：黑色遮罩。视频封面上的信息渐变底、纯黑压暗层。收编 0.3 / 0.35 / 0.4 三档。 */
    const val scrim = 0.35f

    /** 供单元测试校验档位单调性与间距。 */
    val levels: List<Float> get() = listOf(subtle, tint, selected, scrim)
}

/**
 * 彩底容器上的**文字**透明度档位。
 *
 * 浅色背景（surface / background）上的次要文字**不许用本表** —— 一律
 * `onSurfaceVariant`，那是配色方案里调过对比度的正牌次要色。
 * 只有压在 primaryContainer 这类染色底上时才用这里，灰色文字压染色底会发脏。
 *
 * 收编前容器上的散装值：0.72（首页促销卡）→ [secondary]；0.35（通用组件水印）→ [faint]。
 */
object TaotaoTextAlpha {
    /** 60%：染色底上的次要文字。收编 0.6 / 0.72 两档。 */
    const val secondary = 0.6f

    /** 45%：染色底上的装饰性文字、水印。收编 0.35 / 0.45 两档。 */
    const val faint = 0.45f

    /** 供单元测试校验档位单调性。 */
    val levels: List<Float> get() = listOf(faint, secondary)
}

/**
 * 三端共用的「未唱到歌词」颜色。
 *
 * 从 Android 端 `ui/theme/Theme.kt` 上收（视觉走查 2026-10：歌词是 player-ui 三端共享的
 * 场景，灰度色却只有 Android 有，Windows / Web 歌词页将来得各抄一遍）。
 *
 * 单独定义而不是用 `onSurfaceVariant`：逐字高亮要求「已唱」与「未唱」有明确落差，
 * 常规次要文字色的对比度不够，滚动时看不出唱到哪了。
 *
 * 深浅按当前主题背景亮度反推，与 androidApp 的 `isDarkTheme()` 同一算法，
 * 保证跟随应用主题而不是系统主题。
 */
val TaotaoLyricDim: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.isLuminanceDark()) {
        Color(0xFF6E6462)
    } else {
        Color(0xFFB9AEAB)
    }

/** 按加权亮度判断颜色是否偏暗（BT.601 加权）；androidApp 侧已委托本实现，本文件是唯一数据源。 */
private fun Color.isLuminanceDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
