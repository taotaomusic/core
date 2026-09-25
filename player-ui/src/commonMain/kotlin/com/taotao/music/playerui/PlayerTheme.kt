package com.taotao.music.playerui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoTypography

/** 三端播放详情共用的品牌色，平台外围页面可以继续保留自己的主题扩展。 */
val PlayerCoral = Color(0xFFFA5E5B)
val PlayerCoralDark = Color(0xFFFC7773)
val PlayerBackground = Color(0xFFFFF9F7)
val PlayerBackgroundDark = Color(0xFF1A1615)
private val playerLightColors = lightColorScheme(
    primary = PlayerCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8D0),
    onPrimaryContainer = Color(0xFF3A1512),
    secondary = PlayerCoral,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6E1),
    onSecondaryContainer = Color(0xFF3A1512),
    background = PlayerBackground,
    onBackground = Color(0xFF231E1D),
    surface = Color.White,
    onSurface = Color(0xFF231E1D),
    surfaceVariant = Color(0xFFF3E7E4),
    onSurfaceVariant = Color(0xFF8A8280),
    outlineVariant = Color(0xFFEADFDC),
    // surfaceContainer 一族必须覆盖：M3 基线默认是带紫调的灰（亮 #ECE6F0），
    // 弹窗、下拉菜单、底部抽屉都从这族取底色，不覆盖就会和应用暖色脱离。
    // 层级越高越深，surfaceContainerHigh 直接复用 surfaceVariant，保持 token 数量不膨胀。
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6EFEC),
    surfaceContainer = Color(0xFFF3E9E6),
    surfaceContainerHigh = Color(0xFFF3E7E4),
    surfaceContainerHighest = Color(0xFFEDE0DC),
    surfaceDim = Color(0xFFE9DCD8),
    surfaceBright = Color.White,
)

private val playerDarkColors = darkColorScheme(
    primary = PlayerCoralDark,
    onPrimary = Color(0xFF3A1512),
    primaryContainer = Color(0xFF4A2622),
    onPrimaryContainer = Color(0xFFFFEDEA),
    secondary = PlayerCoralDark,
    onSecondary = Color(0xFF3A1512),
    secondaryContainer = Color(0xFF493538),
    onSecondaryContainer = Color(0xFFFFEDEA),
    background = PlayerBackgroundDark,
    onBackground = Color(0xFFF2EAE8),
    surface = Color(0xFF262120),
    onSurface = Color(0xFFF2EAE8),
    surfaceVariant = Color(0xFF322C2B),
    onSurfaceVariant = Color(0xFF9E9694),
    outlineVariant = Color(0xFF3B3433),
    // 同亮色：M3 基线暗色容器是带蓝紫调的灰黑（#2B2930），压在暖色应用里会发冷。
    // 层级越高越亮，surfaceContainerHigh 复用 surfaceVariant。
    surfaceContainerLowest = Color(0xFF141110),
    surfaceContainerLow = Color(0xFF211D1C),
    surfaceContainer = Color(0xFF292422),
    surfaceContainerHigh = Color(0xFF322C2B),
    surfaceContainerHighest = Color(0xFF3B3433),
    surfaceDim = Color(0xFF1A1615),
    surfaceBright = Color(0xFF3B3433),
)

/**
 * Android、Windows 与 Web 共用的完整品牌主题。
 *
 * 排版与形状默认取本项目的 [TaotaoTypography] 与 [TaotaoShapes]，
 * **不再使用 Material 3 的默认值**：默认排版是为拉丁字母设计的（正字距、行距偏紧、
 * 最大字号到 57sp），默认形状会引入第六套圆角语言。详见两个 token 文件的说明。
 *
 * 两个参数保留可覆盖，是为了让单端做局部实验时不必改公共主题；正式页面一律用默认值。
 */
@Composable
fun TaotaoPlayerTheme(
    darkTheme: Boolean,
    typography: Typography = TaotaoTypography,
    shapes: Shapes = TaotaoShapes.material,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) playerDarkColors else playerLightColors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
