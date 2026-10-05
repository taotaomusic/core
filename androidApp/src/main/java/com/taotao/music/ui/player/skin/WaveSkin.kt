package com.taotao.music.ui.player.skin

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.sin

/**
 * 声波皮肤：圆形封面悬在中央，左右各一列随播放律动的「频谱条」。
 *
 * 播放时频谱条以错开的相位做往复伸缩（用一根相位轴驱动全部条，避免各自
 * 起一个无限动画）；暂停 / reduce-motion 时相位轴归零静止，条收敛到基准长度。
 * 封面不旋转，[rotationDegrees] 被刻意忽略（见 [CoverSkin] 的语义说明）。
 */
@Composable
internal fun WaveSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    // 相位轴：0 → 2π 匀速循环；降级（暂停 / reduce-motion）时目标值并回 0，起止相同即静止。
    val transition = rememberInfiniteTransition(label = "waveBars")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion || !isPlaying) 0f else (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(DURATION_WAVE_CYCLE, easing = LinearEasing)),
        label = "wavePhase",
    )
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 第一层：频谱条（画在封面底下，条从封面两侧伸出）。
        Canvas(Modifier.fillMaxSize()) { drawWaveBars(wavePhase, fallbackColor, isPlaying) }
        // 第二层：圆形封面。
        val coverFraction = 0.54f
        if (coverUri.isNullOrBlank()) {
            val glyph = with(LocalDensity.current) { (discSize * coverFraction * 0.5f).toSp() }
            Box(
                Modifier
                    .fillMaxSize(coverFraction)
                    .clip(CircleShape)
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
                modifier = Modifier
                    .fillMaxSize(coverFraction)
                    .clip(CircleShape),
            )
        }
        // 第三层：封面发丝描边（固定层）。
        Canvas(Modifier.fillMaxSize()) { drawWaveRing(coverFraction) }
    }
}

/** 频谱条往复一个周期的时长（毫秒）。 */
private const val DURATION_WAVE_CYCLE = 1600

/** 封面发丝描边。 */
private fun DrawScope.drawWaveRing(coverFraction: Float) {
    val d = size.minDimension
    drawCircle(
        Color.White.copy(alpha = 0.55f),
        radius = d / 2f * coverFraction,
        center = center,
        style = Stroke(width = d * 0.005f),
    )
}

/**
 * 左右两列频谱条：每列 5 根横向圆头短棒，长度按 sin(相位 + 条序偏移) 伸缩。
 * 降级（暂停）时 extraLen 收敛到近零，只剩基准长度的静态条。
 */
private fun DrawScope.drawWaveBars(phase: Float, tint: Color, playing: Boolean) {
    val d = size.minDimension
    val barCount = 5
    val thickness = d * 0.014f
    val gap = d * 0.022f
    val columnHeight = barCount * thickness + (barCount - 1) * gap
    val top = center.y - columnHeight / 2f
    val baseLen = d * 0.08f
    val extraLen = if (playing) d * 0.10f else d * 0.012f
    for (column in 0..1) {
        val x = if (column == 0) center.x - d * 0.30f else center.x + d * 0.30f
        for (i in 0 until barCount) {
            val barPhase = phase + i * 0.9f + column * (PI / 2f).toFloat()
            val wave = (sin(barPhase) + 1f) / 2f
            val len = baseLen + extraLen * wave
            val cy = top + i * (thickness + gap) + thickness / 2f
            drawLine(
                tint.copy(alpha = 0.30f + 0.45f * wave),
                start = Offset(x - len / 2f, cy),
                end = Offset(x + len / 2f, cy),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
            )
        }
    }
}
