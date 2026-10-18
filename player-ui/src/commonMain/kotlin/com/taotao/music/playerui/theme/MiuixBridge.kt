package com.taotao.music.playerui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.LocalContentColor
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
 *
 * ## 除了配色，还要补 Miuix 的 LocalContentColor
 *
 * Miuix 有两套 `MiuixTheme` 重载：`controller` 那套会额外下发
 * `LocalContentColor`，而本项目用的 `colors` 那套**不下发**。缺了它，读该 Local
 * 的组件（如 `InputField` 里用户输入的正文）会退到 `compositionLocalOf` 的默认值
 * `Color.Black`——深色主题下搜索框里打进去的字直接看不见。这里按上游 `controller`
 * 重载的口径补一层，取值口径保持一致（`onBackground`）。
 */
@Composable
fun TaotaoMiuixTheme(content: @Composable () -> Unit) {
    val colors = taotaoMiuixColors()
    MiuixTheme(colors = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            content()
        }
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
        // 搜索输入框（Miuix InputField）的占位文案与前后图标取这一档：上游工厂值是
        // 一枚弱灰（浅色 #A2A2A2），语义正是「胶囊底上的次要前景」，所以跟其余 variant
        // / summary 字段一样落到 onSurfaceVariant —— 用 onSurface 会让占位文案看起来
        // 跟真实输入内容一个强度，失去占位提示。
        onSurfaceContainerHigh = scheme.onSurfaceVariant,
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
