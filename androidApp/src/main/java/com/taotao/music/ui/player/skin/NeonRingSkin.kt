package com.taotao.music.ui.player.skin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.ui.theme.AnimationDurations

/**
 * 霓虹环皮肤：深色底板 + 圆角方封面 + 歌曲主题色的发光环。
 *
 * 播放态由光环的亮度表达：播放时双环更亮更粗、底板透出极淡的主题色底光；
 * 暂停时收敛成暗环常亮（亮度按状态直读，不跑无限动画）。
 * 封面不旋转，[rotationDegrees] 被刻意忽略（见 [CoverSkin] 的语义说明）。
 */
@Composable
internal fun NeonRingSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 第一层：深色底板（固定层），把主题色光环衬出来。
        Canvas(Modifier.fillMaxSize()) { drawNeonBackdrop(fallbackColor, isPlaying) }
        // 第二层：圆角方封面（占比 0.60，圆角约 14% 边长），四角留出光环空间。
        val coverFraction = 0.60f
        val coverSide = discSize * coverFraction
        Box(
            Modifier
                .size(coverSide)
                .clip(RoundedCornerShape(discSize * 0.085f)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUri.isNullOrBlank()) {
                val glyph = with(LocalDensity.current) { (discSize * 0.13f).toSp() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(fallbackColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♫", color = Color.White, fontSize = glyph)
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUri)
                        .crossfade(AnimationDurations.FADE)
                        .build(),
                    contentDescription = "专辑封面",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // 第三层：霓虹光环（固定层，画在封面之外的环带上）。
        Canvas(Modifier.fillMaxSize()) { drawNeonHalo(fallbackColor, isPlaying) }
    }
}

/** 深色底板：径向渐变的近黑底，播放时透出一层极淡的主题色底光。 */
private fun DrawScope.drawNeonBackdrop(tint: Color, isPlaying: Boolean) {
    val d = size.minDimension
    val r = d / 2f
    drawCircle(
        Brush.radialGradient(
            0.00f to Color(0xFF17171E),
            0.72f to Color(0xFF101016),
            1.00f to Color(0xFF0C0C11),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
    if (isPlaying) {
        drawCircle(
            Brush.radialGradient(
                0.50f to tint.copy(alpha = 0.10f),
                0.80f to Color.Transparent,
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }
}

/**
 * 霓虹光环：内外双环 + 左上一颗灯管受光亮斑。
 * 播放时亮度系数 0.9（更亮更粗），暂停时 0.55（暗环常亮）。
 */
private fun DrawScope.drawNeonHalo(tint: Color, isPlaying: Boolean) {
    val d = size.minDimension
    val r = d / 2f
    val breath = if (isPlaying) 0.9f else 0.55f
    drawCircle(
        tint.copy(alpha = 0.55f * breath),
        radius = r * 0.90f,
        center = center,
        style = Stroke(width = d * 0.010f * breath),
    )
    drawCircle(
        tint.copy(alpha = 0.18f * breath),
        radius = r * 0.965f,
        center = center,
        style = Stroke(width = d * 0.020f * breath),
    )
    drawCircle(
        Color.White.copy(alpha = 0.50f * breath),
        radius = d * 0.010f,
        center = center + Offset(-r * 0.636f, -r * 0.636f),
    )
}
