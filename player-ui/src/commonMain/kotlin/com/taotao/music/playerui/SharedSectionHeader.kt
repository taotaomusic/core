package com.taotao.music.playerui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.taotao.music.playerui.theme.TaotaoSpacing

/**
 * 标题层级。
 *
 * 收敛自原先四份各自为政的实现（另有一份 `PageTitle` 是死代码，已删）：
 *
 * | 原实现 | 位置 | 原字号 |
 * | --- | --- | --- |
 * | `LibraryPageHeader` | `MineLibraryPages.kt` | 26sp |
 * | `PageTitle`（0 处调用） | `components.kt` | 24sp |
 * | `SectionTitle` | `TaotaoMusicApp.kt` | 21sp |
 * | `CardWithTitle` | `components.kt` | 18sp |
 * | `AccountSectionTitle` | `AccountAndAnnouncementDialogs.kt` | 17sp |
 *
 * 五种字号里只有 18 落在 [com.taotao.music.playerui.theme.TaotaoTypeScale] 上，
 * 其余四个都是页面里随手写的。现在收敛成三档。
 */
enum class SharedSectionLevel {
    /** 页面主标题，通常与返回按钮同级。 */
    PAGE,

    /** 页面内的区块标题。 */
    SECTION,

    /** 卡片或对话框内的分组标签。 */
    CARD,
}

/**
 * 层级 → 标题样式。
 *
 * 抽成**接收 [Typography] 的纯函数**有两个原因：
 * 一是组件仍然走 `MaterialTheme.typography`，能尊重主题覆盖
 * （`webApp` 会用 `TaotaoTypography.withFontFamily(webFontFamily)` 换字体）；
 * 二是纯函数可以在 `commonTest` 里直接断言，不必拉起 Compose 运行时。
 */
internal fun sectionTitleStyle(typography: Typography, level: SharedSectionLevel): TextStyle = when (level) {
    SharedSectionLevel.PAGE -> typography.headlineMedium
    SharedSectionLevel.SECTION -> typography.titleLarge
    SharedSectionLevel.CARD -> typography.titleMedium
}

/** 层级 → 上内边距。[SharedSectionLevel.CARD] 由容器负责留白，所以是 0。 */
internal fun sectionTopPadding(level: SharedSectionLevel): Dp = when (level) {
    SharedSectionLevel.PAGE -> TaotaoSpacing.sm
    SharedSectionLevel.SECTION -> TaotaoSpacing.sm
    SharedSectionLevel.CARD -> TaotaoSpacing.none
}

/**
 * 层级 → 下内边距。
 *
 * PAGE 档刻意比上内边距小：列表页标题若留出等量下边距，会把第一首歌推得太远
 * （原先 `LibraryPageHeader` 就是 top=12 / bottom=6，这里收敛为 12 / 4）。
 */
internal fun sectionBottomPadding(level: SharedSectionLevel): Dp = when (level) {
    SharedSectionLevel.PAGE -> TaotaoSpacing.xxs
    SharedSectionLevel.SECTION -> TaotaoSpacing.sm
    SharedSectionLevel.CARD -> TaotaoSpacing.none
}

/**
 * 三端共用的标题区域。
 *
 * [leading] / [trailing] 是插槽：返回按钮、更多操作等平台相关的内容由调用方注入，
 * 组件本身不引入平台依赖（沿用 [SharedSongRow] 的插槽模式）。
 *
 * **组件会自行 `fillMaxWidth()`** —— 标题区域按惯例占满父容器宽度，
 * 这样 `leading` 与 `trailing` 才能分别贴住两端。需要窄标题时请在外层包一层布局。
 */
@Composable
fun SharedSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    level: SharedSectionLevel = SharedSectionLevel.SECTION,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    /**
     * 右侧操作区，例如列表页的「清空」按钮。
     *
     * 刻意**不带 `RowScope` 接收者**：带了之后调用点无法直接传入一个
     * `(@Composable () -> Unit)?` 类型的可空参数（`trailing = action?.let { ... }`
     * 会因为推断不出接收者而报错），而实际用例里没有一个需要 `Modifier.align`。
     */
    trailing: (@Composable () -> Unit)? = null,
) {
    val topPadding = sectionTopPadding(level)
    val bottomPadding = sectionBottomPadding(level)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f).padding(
                start = if (leading == null) TaotaoSpacing.none else TaotaoSpacing.xs,
            ),
        ) {
            Text(
                text = title,
                style = sectionTitleStyle(MaterialTheme.typography, level),
                fontWeight = FontWeight.Bold,
                color = titleColor,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(TaotaoSpacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
