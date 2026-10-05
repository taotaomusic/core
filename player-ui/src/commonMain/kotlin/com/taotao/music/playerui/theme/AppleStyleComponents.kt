package com.taotao.music.playerui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 滑块轨道厚度与滑块直径。
 *
 * 刻意不进 TaotaoSizes：两者必须成比例才好看（滑块要明显大于轨道），
 * 单独挪动任何一个都会让进度条失衡，所以成对定义、只在本文件使用。
 * 按压时的放大倍率同属这一组比例，一并放在这里。
 */
private val SliderTrackHeight = 4.dp
private val SliderTrackHeightPressed = 7.dp
private val SliderThumbSize = 12.dp
private const val SliderThumbScalePressed = 1.35f

/** 自绘轨道所在画布的固定高度：只要容得下按压变粗后的线宽即可，画布本身不参与按压动画（避免每帧重布局）。 */
private val SliderTrackCanvasHeight = 16.dp

/** 高潮段默认的透明度：亮到能一眼认出，又不抢已播进度的视觉层级；播放头进入段内时升为实色。 */
private const val SliderHighlightIdleAlpha = 0.55f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleStyleSlider(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onProgressChangeFinished: () -> Unit = {},
    /** 可选强调段（如高潮区间）的归一化起点，与 [highlightEnd] 成对传入；无效区间将被忽略。 */
    highlightStart: Float? = null,
    /** 可选强调段的归一化终点（0..1，允许略超 1，绘制时会夹到轨道末端）。 */
    highlightEnd: Float? = null,
) {
    // iOS 式按压反馈：按住拖动期间整条轨道变粗、拇指放大，松手回弹。
    // onValueChange 首次触发即视为按下，onValueChangeFinished（拖动结束和点击跳转都会调）即视为松开。
    var pressed by remember { mutableStateOf(false) }
    Slider(
        value = progress,
        onValueChange = {
            pressed = true
            onProgressChange(it)
        },
        onValueChangeFinished = {
            pressed = false
            onProgressChangeFinished()
        },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        thumb = {
            // Float 与 Dp 两个动画的 spec 类型不同，写成两处相同参数的 spring 而不是共用一个变量。
            val thumbScale by animateFloatAsState(
                targetValue = if (pressed) SliderThumbScalePressed else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "thumbScale",
            )
            Box(
                modifier = Modifier
                    .size(SliderThumbSize)
                    .scale(thumbScale)
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape)
            )
        },
        track = { sliderState ->
            val trackHeight by animateDpAsState(
                targetValue = if (pressed) SliderTrackHeightPressed else SliderTrackHeight,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "trackHeight",
            )
            // 播放头/拇指落在强调段内时该段点亮为实色，拖动或播放经过高潮段会有即时的「进入」反馈。
            val value = sliderState.value
            val inHighlight = highlightStart != null && highlightEnd != null &&
                value >= highlightStart && value <= highlightEnd
            val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
            val activeColor = MaterialTheme.colorScheme.onSurface
            val highlightColor = MaterialTheme.colorScheme.primary.copy(
                alpha = if (inHighlight) 1f else SliderHighlightIdleAlpha,
            )
            Canvas(modifier = Modifier.fillMaxWidth().height(SliderTrackCanvasHeight)) {
                val y = size.height / 2
                val stroke = trackHeight.toPx()
                val half = stroke / 2f
                // 宽度为 0 的首帧测量（动画容器、列表预测量）下 coerceIn 的上限不能小于下限，先夹一道。
                val trackEnd = (size.width - half).coerceAtLeast(half)
                // 层序自下而上：底轨 → 强调段 → 已播段。已播段从强调段上面扫过，
                // 强调段只预告尚未播到的部分——它是轨道的属性，而不是贴在进度上的补丁。
                drawSegment(inactiveColor, Offset(half, y), Offset(trackEnd, y), stroke)
                val startRatio = highlightStart
                val endRatio = highlightEnd
                if (startRatio != null && endRatio != null && startRatio in 0f..1f && endRatio > startRatio) {
                    drawSegment(
                        highlightColor,
                        Offset(size.width * startRatio, y),
                        Offset(size.width * endRatio.coerceAtMost(1f), y),
                        stroke,
                    )
                }
                drawSegment(activeColor, Offset(half, y), Offset((size.width * value).coerceIn(half, trackEnd), y), stroke)
            }
        }
    )
}

private fun DrawScope.drawSegment(color: Color, from: Offset, to: Offset, strokePx: Float) {
    drawLine(color = color, start = from, end = to, strokeWidth = strokePx, cap = StrokeCap.Round)
}

@Composable
fun ApplePlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    playIcon: ImageVector,
    pauseIcon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = TaotaoSizes.playButton,
    iconSize: Dp = TaotaoSizes.playButtonIcon,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) pauseIcon else playIcon,
            contentDescription = if (isPlaying) "暂停" else "播放",
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

