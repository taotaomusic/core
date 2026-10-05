package com.taotao.music.playerui.skin

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.taotao.music.playerui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.sin

/**
 * 潮汐皮肤：圆形封面浮在主题色柔光上，外圈两道「潮环」随播放缓慢涨落。
 *
 * 播放时潮环半径与透明度按同一根相位轴往复（内外两环相位相反，形成呼吸错落）；
 * 暂停 / reduce-motion 时相位轴归零静止，潮环停在中间位置。封面不旋转，
 * [rotationDegrees] 被刻意忽略（见 [CoverSkin] 的语义说明）。
 * 封面加载走 [imageLoader] 插槽。
 */
@Composable
internal fun TidalSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    imageLoader: CoverImageLoader,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    // 相位轴：0 → 2π 匀速循环；降级（暂停 / reduce-motion）时目标值并回 0，起止相同即静止。
    val transition = rememberInfiniteTransition(label = "tidalRings")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion || !isPlaying) 0f else (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(DURATION_TIDAL_CYCLE, easing = LinearEasing)),
        label = "tidalPhase",
    )
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 第一层：主题色柔光（固定层，播放时略提亮）。
        Canvas(Modifier.fillMaxSize()) { drawTidalGlow(fallbackColor, isPlaying) }
        // 第二层：圆形封面（占比与光晕一致，0.58）。
        val coverFraction = 0.58f
        CoverSkinImageSlot(
            url = coverUri,
            fallbackColor = fallbackColor,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxSize(coverFraction)
                .clip(CircleShape),
        )
        // 第三层：潮环 + 封面描边（固定层，随相位涨落）。
        Canvas(Modifier.fillMaxSize()) { drawTidalRings(wavePhase, fallbackColor, isPlaying, coverFraction) }
    }
}

/** 潮环涨落一个周期的时长（毫秒）：刻意比声波慢，做出「潮水」的从容感。 */
private const val DURATION_TIDAL_CYCLE = 2800

/** 主题色柔光：单层径向渐变，播放时从 0.26 提到 0.40。 */
private fun DrawScope.drawTidalGlow(tint: Color, isPlaying: Boolean) {
    val d = size.minDimension
    val r = d / 2f
    val strength = if (isPlaying) 0.40f else 0.26f
    drawCircle(
        Brush.radialGradient(
            0.34f to tint.copy(alpha = strength),
            0.74f to tint.copy(alpha = strength * 0.45f),
            1.00f to Color.Transparent,
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

/**
 * 两道潮环：半径在封面外缘与控件边缘之间往复，外环更淡更细。
 * 降级（暂停）时停在中间位置；最后一笔画封面发丝描边把封面托出来。
 */
private fun DrawScope.drawTidalRings(phase: Float, tint: Color, playing: Boolean, coverFraction: Float) {
    val d = size.minDimension
    val r = d / 2f
    val coverR = r * coverFraction
    for (i in 0..1) {
        val ringPhase = phase + i * PI.toFloat()
        val offset = if (playing) (sin(ringPhase) + 1f) / 2f else 0.5f
        val ringR = coverR + (r - coverR) * (0.30f + 0.45f * offset) * (1f + i * 0.12f)
        drawCircle(
            tint.copy(alpha = if (playing) 0.30f - i * 0.10f else 0.14f - i * 0.05f),
            radius = ringR,
            center = center,
            style = Stroke(width = d * (0.010f - i * 0.003f)),
        )
    }
    drawCircle(
        Color.White.copy(alpha = 0.80f),
        radius = coverR,
        center = center,
        style = Stroke(width = d * 0.006f),
    )
}
