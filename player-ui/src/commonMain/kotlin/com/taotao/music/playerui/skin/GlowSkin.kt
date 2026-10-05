package com.taotao.music.playerui.skin

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import com.taotao.music.playerui.theme.LocalReduceMotion

/**
 * 光晕皮肤：圆形封面悬停在以歌曲主题色晕开的柔光中央，配一圈发丝描边与底部的调色点。
 *
 * 不旋转：[rotationDegrees] 被刻意忽略（见 [CoverSkin] 的语义说明），播放态改由
 * 两处表达 —— 播放时外层光晕整体提亮约一档，暂停时回落；底部三颗调色点在播放时
 * 呼吸闪烁。光晕从封面外缘向外发散并在控件边界内淡出（模板约束：不得画出
 * [discSize] 正方形之外），因此即使亮色封面也不会把页面背景染花。
 * 封面加载走 [imageLoader] 插槽。
 */
@Composable
internal fun GlowSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    imageLoader: CoverImageLoader,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 第一层：主题色光晕（固定层）。播放时更亮更大，暂停时收敛。
        Canvas(Modifier.fillMaxSize()) { drawGlow(fallbackColor, isPlaying) }
        // 第二层：圆形封面，占控件 0.58，外圈一圈发丝描边。
        val coverFraction = 0.58f
        CoverSkinImageSlot(
            url = coverUri,
            fallbackColor = fallbackColor,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxSize(coverFraction)
                .clip(CircleShape),
        )
        // 第三层：封面描边 + 底部调色点（固定层）。
        Canvas(Modifier.fillMaxSize()) { drawCoverRing(coverFraction) }
        PaletteDots(
            isPlaying = isPlaying,
            dotSize = discSize * 0.024f,
            spacing = discSize * 0.012f,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = discSize * 0.055f),
        )
    }
}

/**
 * 主题色光晕：大范围柔光 + 一圈更浓的内晕，两层径向渐变叠出呼吸感。
 * 播放时 [isPlaying] 让外晕强度从 0.34 提到 0.5、内晕从 0.26 提到 0.42。
 */
private fun DrawScope.drawGlow(tint: Color, isPlaying: Boolean) {
    val d = size.minDimension
    val r = d / 2f
    val outerStrength = if (isPlaying) 0.50f else 0.34f
    val innerStrength = if (isPlaying) 0.42f else 0.26f
    // 外晕：从约封面外缘处开始向外淡出到全透明。
    drawCircle(
        Brush.radialGradient(
            0.30f to tint.copy(alpha = outerStrength),
            0.72f to tint.copy(alpha = outerStrength * 0.45f),
            1.00f to Color.Transparent,
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
    // 内晕：更贴近封面的一圈浓光，让封面看起来像发光体本身。
    drawCircle(
        Brush.radialGradient(
            0.50f to tint.copy(alpha = innerStrength),
            0.62f to Color.Transparent,
            center = center + Offset(0f, d * 0.01f),
            radius = r * 0.9f,
        ),
        radius = r * 0.9f,
        center = center + Offset(0f, d * 0.01f),
    )
}

/** 封面发丝描边：一圈近白细线 + 外圈更淡的柔边，把封面从光晕里「托」出来。 */
private fun DrawScope.drawCoverRing(coverFraction: Float) {
    val d = size.minDimension
    val r = d / 2f
    drawCircle(
        Color.White.copy(alpha = 0.85f),
        radius = r * coverFraction,
        center = center,
        style = Stroke(width = d * 0.008f),
    )
    drawCircle(
        Color.White.copy(alpha = 0.18f),
        radius = r * (coverFraction + 0.018f),
        center = center,
        style = Stroke(width = d * 0.018f),
    )
}

/** 底部三颗调色点：皮肤包的通用点缀行；播放时呼吸闪烁，暂停时收敛为暗点。 */
@Composable
private fun PaletteDots(
    isPlaying: Boolean,
    dotSize: Dp,
    spacing: Dp,
    modifier: Modifier = Modifier,
) {
    // 呼吸透明度：降级（暂停 / reduce-motion）时不换 spec，
    // 目标值并回起点 0.45f，起止相同即静止为暗点。
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "glowDots")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = if (reduceMotion || !isPlaying) 0.45f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowDotPulse",
    )
    val colors = listOf(
        Color(0xFFFF7A66),
        Color(0xFFFFC46B),
        Color(0xFFB388FF),
    )
    Row(modifier.graphicsLayer { alpha = pulse }, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        for (color in colors) {
            Box(
                Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
