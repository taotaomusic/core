package com.taotao.music.ui.player.skin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/**
 * 磁带机皮肤：方形带壳 + 中央标签窗（封面画在窗内）+ 双卷轴 + 观察窗。
 *
 * 播放态的表意：两根卷轴随 [rotationDegrees] 之外额外做匀速转动 —— 用
 * [rememberInfiniteTransition] 单独驱动（时长随 [isPlaying] 切换快慢只是视觉修辞，
 * 暂停时卷轴立即停转），同时带壳底部压一条「正在播放」的珊瑚色指示灯带；
 * 暂停时灯带熄灭、卷轴静止，观察窗里还能看到静止的磁带余量斜线。
 *
 * 几何全部以 [discSize] 为基准的归一化系数计算：带壳占满控件，圆角由带壳倒角
 * 表达；封面窗占宽约 62%、高约 30%，双卷轴压在窗的左右两端。
 */
@Composable
internal fun CassetteSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    // 卷轴匀速自转：暂停立即归零静止；reduce-motion 时直接固定不动画。
    val reelTransition = rememberInfiniteTransition(label = "cassetteReels")
    val reelAngle by reelTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = if (reduceMotion || !isPlaying) {
            snap()
        } else {
            infiniteRepeatable(tween(DURATION_REEL_TURN, easing = LinearEasing))
        },
        label = "cassetteReelAngle",
    )
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 带壳本体：暖灰塑料壳 + 倒角高光，由 Canvas 一次性画完背景层。
        Canvas(Modifier.fillMaxSize()) { drawCassetteShell() }
        // 中央标签窗：封面缩略画进窗口，无图退回主题色 + 音符。
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(LABEL_WINDOW_WIDTH)
                .padding(top = discSize * LABEL_WINDOW_TOP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .size(width = Dp.Unspecified, height = discSize * LABEL_WINDOW_HEIGHT)
                    .clip(RoundedCornerShape(discSize * 0.02f))
                    .background(Color(0xFF141418)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverUri.isNullOrBlank()) {
                    val glyph = with(LocalDensity.current) { (discSize * 0.11f).toSp() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(discSize * 0.015f)
                            .clip(RoundedCornerShape(discSize * 0.012f))
                            .background(fallbackColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("♫", color = Color.White, fontSize = glyph)
                    }
                } else {
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                            .data(coverUri)
                            .crossfade(AnimationDurations.FADE)
                            .build(),
                        contentDescription = "专辑封面",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(discSize * 0.015f)
                            .clip(RoundedCornerShape(discSize * 0.012f)),
                    )
                }
            }
        }
        // 卷轴与观察窗层：卷轴压在标签窗下方左右两侧，角度由 reelAngle 驱动。
        Canvas(Modifier.fillMaxSize()) {
            drawCassetteReels(reelAngle)
            drawCassetteWindow()
        }
        // 播放指示灯带 + 壳底文字区（固定层）。
        Canvas(Modifier.fillMaxSize()) { drawCassetteBottom(isPlaying) }
    }
}

/** 标签窗相对带壳的宽度占比。 */
private const val LABEL_WINDOW_WIDTH = 0.62f

/** 标签窗顶边相对控件边长的下移比例（避开壳顶的螺钉区）。 */
private const val LABEL_WINDOW_TOP = 0.16f

/** 标签窗高度相对控件边长的比例。 */
private const val LABEL_WINDOW_HEIGHT = 0.30f

/** 卷轴转一圈的时长（毫秒）：播放时两根卷轴的匀速自转周期。 */
private const val DURATION_REEL_TURN = 2400

/** 带壳：暖灰塑料底 + 边缘受光 + 四角螺钉，全部归一化画法。 */
private fun DrawScope.drawCassetteShell() {
    val d = size.minDimension
    // 壳底：上亮下暗的线性渐变模拟塑料注塑面的受光。
    drawRoundRect(
        Brush.verticalGradient(
            0.00f to Color(0xFF3A3A42),
            0.55f to Color(0xFF2C2C33),
            1.00f to Color(0xFF232329),
        ),
        topLeft = Offset(d * 0.03f, d * 0.10f),
        size = Size(d * 0.94f, d * 0.80f),
        cornerRadius = CornerRadius(d * 0.045f),
    )
    // 壳缘受光线：顶部一道浅色描边圆角矩形，只描边不填充。
    drawRoundRect(
        Color.White.copy(alpha = 0.10f),
        topLeft = Offset(d * 0.03f, d * 0.10f),
        size = Size(d * 0.94f, d * 0.80f),
        cornerRadius = CornerRadius(d * 0.045f),
        style = Stroke(width = d * 0.006f),
    )
    // 四角螺钉：小圆点 + 中心一字槽，位置贴近四角。
    val screwOffset = d * 0.075f
    val screws = listOf(
        Offset(screwOffset, d * 0.10f + screwOffset),
        Offset(d - screwOffset, d * 0.10f + screwOffset),
        Offset(screwOffset, d * 0.90f - screwOffset),
        Offset(d - screwOffset, d * 0.90f - screwOffset),
    )
    for (screw in screws) {
        drawCircle(Color(0xFF1B1B20), radius = d * 0.016f, center = screw)
        drawCircle(
            Color.White.copy(alpha = 0.16f),
            radius = d * 0.016f,
            center = screw,
            style = Stroke(width = d * 0.003f),
        )
        drawLine(
            Color.White.copy(alpha = 0.22f),
            start = screw - Offset(d * 0.009f, 0f),
            end = screw + Offset(d * 0.009f, 0f),
            strokeWidth = d * 0.003f,
            cap = StrokeCap.Round,
        )
    }
}

/** 双卷轴：标签窗下方左右两根，随 [reelAngleDegrees] 匀速转动，齿毂造型 + 缠带余量。 */
private fun DrawScope.drawCassetteReels(reelAngleDegrees: Float) {
    val d = size.minDimension
    val reelY = d * 0.545f
    val spacing = d * 0.145f
    for (side in 0..1) {
        val cx = center.x + (if (side == 0) -spacing else spacing)
        val pivot = Offset(cx, reelY)
        rotate(reelAngleDegrees, pivot) {
            // 缠带环：深棕色磁带卷。
            drawCircle(Color(0xFF3B2E28), radius = d * 0.078f, center = pivot)
            // 齿毂：浅灰环 + 三根辐条（转动时辐条扫过就是「卷带中」的表意）。
            drawCircle(Color(0xFFE9E9EE), radius = d * 0.042f, center = pivot)
            drawCircle(Color(0xFFB9B9C2), radius = d * 0.042f, center = pivot, style = Stroke(width = d * 0.004f))
            for (i in 0 until 3) {
                rotate(i * 120f, pivot) {
                    drawLine(
                        Color(0xFF6E6E78),
                        start = pivot,
                        end = pivot + Offset(0f, -d * 0.034f),
                        strokeWidth = d * 0.008f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            // 毂心白环（真磁带的卷轴孔）。
            drawCircle(Color.White, radius = d * 0.014f, center = pivot)
        }
    }
    // 观察窗：两轴之间的横向浅窗，透出磁带走带与余量斜线（静态，不随转角变化）。
    val windowTop = d * 0.60f
    drawRoundRect(
        Color(0xFF191920),
        topLeft = Offset(center.x - d * 0.10f, windowTop),
        size = Size(d * 0.20f, d * 0.05f),
        cornerRadius = CornerRadius(d * 0.012f),
    )
    drawLine(
        Color(0xFF5A463C).copy(alpha = 0.8f),
        start = Offset(center.x - d * 0.085f, windowTop + d * 0.038f),
        end = Offset(center.x + d * 0.085f, windowTop + d * 0.016f),
        strokeWidth = d * 0.006f,
    )
}

/** 壳底：播放时点亮的珊瑚色指示灯带，暂停时只留暗槽。 */
private fun DrawScope.drawCassetteBottom(isPlaying: Boolean) {
    val d = size.minDimension
    val barWidth = d * 0.28f
    val barTop = d * 0.78f
    val topLeft = Offset(center.x - barWidth / 2f, barTop)
    // 灯槽：先画常驻的暗槽，播放时叠一层发光的珊瑚色。
    drawRoundRect(
        Color(0xFF17171B),
        topLeft = topLeft,
        size = Size(barWidth, d * 0.028f),
        cornerRadius = CornerRadius(d * 0.014f),
    )
    if (isPlaying) {
        drawRoundRect(
            Color(0xFFFF7A66),
            topLeft = topLeft,
            size = Size(barWidth, d * 0.028f),
            cornerRadius = CornerRadius(d * 0.014f),
        )
        drawRoundRect(
            Color.White.copy(alpha = 0.35f),
            topLeft = topLeft + Offset(0f, d * 0.004f),
            size = Size(barWidth, d * 0.008f),
            cornerRadius = CornerRadius(d * 0.004f),
        )
    }
}
