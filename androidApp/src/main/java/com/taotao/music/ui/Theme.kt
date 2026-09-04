package com.taotao.music.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 主色：珊瑚红，用于强调按钮、选中态和图标。亮暗两套配色共用。 */
val TaotaoCoral = Color(0xFFFA5E5B)

/** 暗色下的主色略微提亮：原色压在深底上对比度不够，长时间看容易发闷。 */
val TaotaoCoralDark = Color(0xFFFC7773)

/** 亮色底：偏暖的浅色背景，卡片用近白叠在其上形成层次。 */
val TaotaoBackground = Color(0xFFFFF9F7)

/** 暗色底：带一点红调的深灰，纯黑会让珊瑚红显得刺眼。 */
val TaotaoBackgroundDark = Color(0xFF1A1615)

/** 卡片色。亮色用纯白，暗色比背景略亮以保留层次。 */
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceDark = Color(0xFF262120)

/** 次要文字与图标。取代此前散落各处的 `Color.Gray`。 */
private val MutedLight = Color(0xFF8A8280)
private val MutedDark = Color(0xFF9E9694)

/** 骨架屏与弱填充。比背景略深（暗色下略亮），比纯灰更贴主题。 */
private val SkeletonLight = Color(0xFFF3E7E4)
private val SkeletonDark = Color(0xFF322C2B)

private val TaotaoLightColors = lightColorScheme(
    primary = TaotaoCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8D0),
    onPrimaryContainer = Color(0xFF3A1512),
    secondary = TaotaoCoral,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6E1),
    onSecondaryContainer = Color(0xFF3A1512),
    background = TaotaoBackground,
    onBackground = Color(0xFF231E1D),
    surface = SurfaceLight,
    onSurface = Color(0xFF231E1D),
    surfaceVariant = SkeletonLight,
    onSurfaceVariant = MutedLight,
    outlineVariant = Color(0xFFEADFDC),
)

private val TaotaoDarkColors = darkColorScheme(
    primary = TaotaoCoralDark,
    onPrimary = Color(0xFF3A1512),
    primaryContainer = Color(0xFF4A2622),
    onPrimaryContainer = Color(0xFFFFEDEA),
    secondary = TaotaoCoralDark,
    onSecondary = Color(0xFF3A1512),
    secondaryContainer = Color(0xFF493538),
    onSecondaryContainer = Color(0xFFFFEDEA),
    background = TaotaoBackgroundDark,
    onBackground = Color(0xFFF2EAE8),
    surface = SurfaceDark,
    onSurface = Color(0xFFF2EAE8),
    surfaceVariant = SkeletonDark,
    onSurfaceVariant = MutedDark,
    outlineVariant = Color(0xFF3B3433),
)

/**
 * 全局主题：所有页面统一从这里取配色。
 *
 * 抽取的原因是登录页此前直接使用 [MaterialTheme] 默认配色，
 * 与应用内的珊瑚红主题不一致，进入首页时会突然换色。
 *
 * 页面里**不要再写死颜色**，一律走 `MaterialTheme.colorScheme`：
 * 卡片用 `surface`，次要文字用 `onSurfaceVariant`，骨架用 `surfaceVariant`，
 * 强调色用 `primary`。写死 `Color.White` / `Color.Gray` 在暗色下会变成白底白字。
 */
@Composable
fun TaotaoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 系统栏图标的明暗要跟**应用**主题走，不能跟系统走：用户在设置里手动选了深色而
    // 系统仍是浅色时，`enableEdgeToEdge()` 的默认行为会让图标是深色的，压在深色背景上看不见。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).run {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) TaotaoDarkColors else TaotaoLightColors,
        content = {
            CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
                content()
            }
        },
    )
}

/**
 * 未唱到的歌词颜色。
 *
 * 单独定义而不是用 `onSurfaceVariant`：逐字高亮要求「已唱」与「未唱」有明确落差，
 * 常规的次要文字色对比度不够，滚动时看不出唱到哪了。
 */
val LyricDim: Color
    @Composable get() = if (isDarkTheme()) Color(0xFF6E6462) else Color(0xFFB9AEAB)

/** 当前主题是否为暗色。按背景亮度反推，省去再往下传一个参数。 */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminanceIsDark()

private fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f


