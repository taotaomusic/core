package com.taotao.music.playerui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * MaterialTheme.colorScheme → Miuix [Colors] 的单向映射桥。
 *
 * ## 为什么要有这层
 *
 * Miuix 组件读自己的 [MiuixTheme] 局部变量（LocalColors），与 MaterialTheme 的
 * colorScheme 互不相通。不包桥接直接用 Miuix 组件会拿到 HyperOS 默认灰蓝配色，
 * 和全应用的珊瑚色系割裂。这层把当前生效的 MaterialTheme 配色逐字段翻译成
 * Miuix 的 Colors，保证「采用 Miuix 的页面」和「其他页面」观感同源。
 *
 * ## 用法
 *
 * 只包裹使用了 Miuix 组件的子树（通常在页面根部），不要全局替换 PlayerTheme：
 *
 * ```
 * TaotaoMiuixTheme {
 *     SuperDialog(...) { ... }
 * }
 * ```
 *
 * 注意：MiuixTheme 会覆盖 LocalIndication / LocalOverscrollFactory，
 * 包裹范围内的 Material3 组件按压反馈也会变成 Miuix 的效果——这是预期行为
 * （采用库的页面整套交互统一），混合布局时要知道有这层影响。
 *
 * ## 映射范围（首版，2026-10）
 *
 * 只翻译「品牌与语义」字段：主色/次色族、容器族、背景/表面族、分割线、滑条。
 * `disabled*` 长尾字段保留 Miuix 工厂默认值——它们是 Miuix 组件内部调过的
 * 灰阶，凭空推导容易错；等页面实际采用后再按观感收敛。M3 没有 variant 概念，
 * `primaryVariant` / `secondaryVariant` 先用主值顶位，Miuix 组件里它们只做
 * 微差色，肉眼不可分。
 */
@Composable
fun TaotaoMiuixTheme(content: @Composable () -> Unit) {
    MiuixTheme(colors = taotaoMiuixColors()) {
        content()
    }
}

/** 当前 MaterialTheme 配色的 Miuix 翻译结果，配色对象变更时自动重算。 */
@Composable
private fun taotaoMiuixColors(): Colors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { translateToMiuix(scheme) }
}

/**
 * light/dark 工厂只决定「未映射字段（disabled 族等）的兜底色」；语义字段
 * 全部显式传入，因此按当前背景明暗选工厂，避免暗色主题拿到亮色兜底。
 */
private fun translateToMiuix(scheme: ColorScheme): Colors {
    val base = if (scheme.background.luminance() < 0.5f) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        primaryVariant = scheme.primary,
        onPrimaryVariant = scheme.onPrimary,
        primaryContainer = scheme.primaryContainer,
        onPrimaryContainer = scheme.onPrimaryContainer,
        secondary = scheme.secondary,
        onSecondary = scheme.onSecondary,
        secondaryVariant = scheme.secondary,
        onSecondaryVariant = scheme.onSecondary,
        secondaryContainer = scheme.secondaryContainer,
        onSecondaryContainer = scheme.onSecondaryContainer,
        secondaryContainerVariant = scheme.secondaryContainer,
        onSecondaryContainerVariant = scheme.onSecondaryContainer,
        tertiaryContainer = scheme.tertiaryContainer,
        onTertiaryContainer = scheme.onTertiaryContainer,
        tertiaryContainerVariant = scheme.tertiaryContainer,
        background = scheme.background,
        onBackground = scheme.onBackground,
        onBackgroundVariant = scheme.onSurfaceVariant,
        surface = scheme.surface,
        onSurface = scheme.onSurface,
        surfaceVariant = scheme.surfaceVariant,
        onSurfaceSecondary = scheme.onSurfaceVariant,
        onSurfaceVariantSummary = scheme.onSurfaceVariant,
        onSurfaceVariantActions = scheme.onSurfaceVariant,
        surfaceContainer = scheme.surfaceContainer,
        onSurfaceContainer = scheme.onSurface,
        onSurfaceContainerVariant = scheme.onSurfaceVariant,
        surfaceContainerHigh = scheme.surfaceContainerHigh,
        surfaceContainerHighest = scheme.surfaceContainerHighest,
        onSurfaceContainerHighest = scheme.onSurface,
        outline = scheme.outline,
        dividerLine = scheme.outlineVariant,
        windowDimming = scheme.scrim,
        sliderKeyPoint = scheme.primary,
        sliderKeyPointForeground = scheme.onPrimary,
        sliderBackground = scheme.surfaceVariant,
    )
}
