package com.taotao.music.playerui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 三端共用的层次规范。
 *
 * ## 为什么需要它
 *
 * 引入本文件之前，**全应用只有 2 处阴影**（`PlayerSurface.kt` 与 `SharedMiniPlayer.kt`），
 * 而且两处的颜色写法还不一致：一处是 `Color.Black.copy(alpha = 0.5f)`，
 * 另一处是 `Color.Black.copy(alpha = 0.1f)` —— 相差 5 倍。
 *
 * 除此之外，卡片、列表、浮层全部落在同一个平面上，Material 3 的 tonal elevation
 * 一次都没用。**这是「看起来平、看起来廉价」最直接的来源**：
 * 用户无法从视觉上判断什么在上面、什么在下面。
 *
 * ## 四级层次
 *
 * | 层级 | 高度 | 用途 |
 * | --- | --- | --- |
 * | [flat] | 0dp | 平铺内容、列表行、页面背景 |
 * | [card] | 1dp | 卡片、面板 —— 靠极轻阴影 + 表面色差区分 |
 * | [raised] | 4dp | 悬浮元素：迷你播放器、浮动按钮 |
 * | [overlay] | 12dp | 弹层：底部抽屉、对话框 |
 *
 * 高度按 3–4 倍递进，让相邻两级在观感上明确可分。**不要新增第五级** ——
 * 层次超过四档，用户就分辨不出高低了。
 */
object TaotaoElevation {
    /** 平铺内容：列表行、页面背景。 */
    val flat = 0.dp

    /** 卡片、面板。 */
    val card = 1.dp

    /** 悬浮元素：迷你播放器、浮动按钮。 */
    val raised = 4.dp

    /** 弹层：底部抽屉、对话框。 */
    val overlay = 12.dp

    /** 全部层级，供单元测试校验递进关系。 */
    val levels: List<Dp> = listOf(flat, card, raised, overlay)
}

/**
 * 当前主题下的阴影颜色组合。
 *
 * 分开给出 `ambient`（环境光，大面积柔和）与 `spot`（直射光，边缘清晰）两个值，
 * 对应 `Modifier.shadow` 的同名参数。
 */
class TaotaoShadowColors(val ambient: Color, val spot: Color)

/**
 * 取当前主题适用的阴影颜色。
 *
 * 亮色下用黑色低透明度；**暗色下阴影几乎不可见**，硬加会在边缘留下一圈脏边，
 * 所以暗色改用更低的透明度并让 Material 3 的 tonal elevation 承担层次表达。
 */
@Composable
fun taotaoShadowColors(): TaotaoShadowColors {
    // 用背景亮度判断明暗，避免为了一个布尔值再往下传一层参数。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        TaotaoShadowColors(
            ambient = Color.Black.copy(alpha = 0.24f),
            spot = Color.Black.copy(alpha = 0.32f),
        )
    } else {
        TaotaoShadowColors(
            ambient = Color.Black.copy(alpha = 0.04f),
            spot = Color.Black.copy(alpha = 0.10f),
        )
    }
}
