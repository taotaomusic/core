package com.taotao.music.playerui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
)

/** Android、Windows 与 Web 共用的完整品牌主题。 */
@Composable
fun TaotaoPlayerTheme(
    darkTheme: Boolean,
    typography: Typography = Typography(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) playerDarkColors else playerLightColors,
        typography = typography,
        content = content,
    )
}
