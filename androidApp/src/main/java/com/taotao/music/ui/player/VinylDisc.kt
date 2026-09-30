package com.taotao.music.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion

/**
 * 黑胶唱片机拟物控件。
 *
 * 结构从下到上分四层：盘底投影、随 [rotationDegrees] 旋转的黑胶盘体（底色 + 声槽纹路）、
 * 圆形专辑封面（与盘体同角度旋转，相当于贴在唱片正中的封贴）、以及不旋转的固定层
 * （盘面高光 + 唱臂）。高光画在固定层是刻意的：光源不该跟着唱片转，否则反光会看起来
 * 像贴纸在打转。
 *
 * 旋转角度由调用方持有（详情页里带暂停/前后台/翻页停止条件的 [androidx.compose.animation.core.Animatable]），
 * 本组件只负责按角度渲染；唱臂的起落由 [isPlaying] 驱动，播放时搭在盘面上、暂停时抬起。
 */
@Composable
internal fun VinylDisc(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    // 唱臂起落动画：约 42° 的抬臂幅度，用缓出曲线模拟机械臂的阻尼感。
    val armAngle by animateFloatAsState(
        targetValue = if (isPlaying) TONEARM_PLAYING_DEG else TONEARM_LIFTED_DEG,
        animationSpec = if (reduceMotion) snap() else tween(DURATION_ARM_DROP, easing = FastOutSlowInEasing),
        label = "tonearmAngle",
    )
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 第一层：盘底软投影（固定，不随盘转）。
        Canvas(Modifier.fillMaxSize()) { drawDiscShadow() }
        // 第二层：黑胶盘体，与封面共用同一个旋转角度。
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotationDegrees }) {
            drawVinylPlate()
        }
        // 第三层：圆形封面（黑胶正中的封贴），无图时退回纯色音符占位。
        if (coverUri.isNullOrBlank()) {
            val glyph = with(LocalDensity.current) { (discSize * COVER_FRACTION / 2f).toSp() }
            Box(
                Modifier
                    .fillMaxSize(COVER_FRACTION)
                    .graphicsLayer { rotationZ = rotationDegrees }
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
                    .fillMaxSize(COVER_FRACTION)
                    .graphicsLayer { rotationZ = rotationDegrees }
                    .clip(CircleShape),
            )
        }
        // 第四层：盘面高光 + 唱臂（固定层，光源与唱臂不随盘旋转）。
        Canvas(Modifier.fillMaxSize()) {
            drawVinylSheen()
            drawTonearm(armAngle)
        }
    }
}

/** 封面直径占整个唱片机的比例：黑胶盘要比封面大一圈，剩下的环带就是声槽区。 */
private const val COVER_FRACTION = 0.68f

/** 唱臂起落动画时长（毫秒）。 */
private const val DURATION_ARM_DROP = 600

/**
 * 唱臂几何（以控件边长为 1 的归一化坐标，y 轴向下）：
 * - 臂根（转轴）固定在右上角 (0.94, 0.07)；
 * - 播放时唱针落在盘面 0.82R、方位角 55° 处，即 (0.735, 0.164)；
 * - 反推得臂向角 atan2(0.094, -0.205) ≈ 155.4°，臂长 ≈ 0.226；
 * - 暂停抬臂时再顺时针转 42°，唱针落到 (0.725, 0.003)，距盘心 0.546R，刚好离开盘缘。
 */
private const val TONEARM_PLAYING_DEG = 155.4f
private const val TONEARM_LIFTED_DEG = 197.4f

/** 盘底投影：两层错位的半透明圆叠出软阴影，营造唱片悬浮在页面上的厚度感。 */
private fun DrawScope.drawDiscShadow() {
    val d = size.minDimension
    drawCircle(
        Color.Black.copy(alpha = 0.10f),
        radius = d * 0.506f,
        center = center + Offset(0f, d * 0.012f),
    )
    drawCircle(
        Color.Black.copy(alpha = 0.08f),
        radius = d * 0.512f,
        center = center + Offset(0f, d * 0.026f),
    )
}

/** 黑胶盘体：径向渐变底色 + 一圈圈声槽细纹，每 5 圈一道稍亮的「音轨分隔」环。 */
private fun DrawScope.drawVinylPlate() {
    val d = size.minDimension
    val r = d / 2f
    // 底色：封面区压暗，声槽区带一点深灰层次，盘缘留一道受光的亮边。
    drawCircle(
        Brush.radialGradient(
            0.00f to Color(0xFF0B0B0D),
            COVER_FRACTION to Color(0xFF0B0B0D),
            (COVER_FRACTION + 0.05f) to Color(0xFF17171C),
            0.96f to Color(0xFF101014),
            1.00f to Color(0xFF26262E),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
    // 声槽：从封面外缘一直刻到接近盘缘的同心细线。
    val grooveInner = COVER_FRACTION + 0.02f
    val grooveOuter = 0.955f
    val grooveCount = 22
    for (i in 0..grooveCount) {
        val fraction = grooveInner + (grooveOuter - grooveInner) * i / grooveCount
        drawCircle(
            Color.White.copy(alpha = if (i % 5 == 0) 0.07f else 0.025f),
            radius = r * fraction,
            center = center,
            style = Stroke(width = d * 0.0022f),
        )
    }
}

/**
 * 盘面高光：两道扇形反光（左上强、右下弱）叠在声槽上模拟黑胶的镜面反光。
 * 裁剪成「盘缘 − 封面」的圆环，只给黑胶上光，不冲淡封面的颜色。
 * 画在固定层所以不随盘旋转 —— 光源方向恒定。
 */
private fun DrawScope.drawVinylSheen() {
    val d = size.minDimension
    val r = d / 2f
    val grooveRing = Path().apply {
        // EvenOdd 规则让内外两个圆构成圆环，高光只落在声槽环带内。
        fillType = PathFillType.EvenOdd
        addOval(Rect(center, r))
        addOval(Rect(center, r * COVER_FRACTION))
    }
    clipPath(grooveRing) {
        drawCircle(
            Brush.sweepGradient(
                0.00f to Color.Transparent,
                0.10f to Color.Transparent,
                0.16f to Color.White.copy(alpha = 0.05f),
                0.22f to Color.Transparent,
                0.55f to Color.Transparent,
                0.62f to Color.White.copy(alpha = 0.10f),
                0.70f to Color.Transparent,
                1.00f to Color.Transparent,
                center = center,
            ),
            radius = r,
            center = center,
        )
    }
    // 封面压环：一圈深色描边把封面「压」进盘体，内侧补一根发丝高光做出倒角。
    drawCircle(
        Color(0xFF0A0A0C),
        radius = r * COVER_FRACTION,
        center = center,
        style = Stroke(width = d * 0.008f),
    )
    drawCircle(
        Color.White.copy(alpha = 0.06f),
        radius = r * (COVER_FRACTION - 0.025f),
        center = center,
        style = Stroke(width = d * 0.003f),
    )
}

/** 唱臂：转轴底座 + 金属主杆 + 尾端配重锤 + 唱头与唱针，整体绕臂根旋转。 */
private fun DrawScope.drawTonearm(angleDegrees: Float) {
    val d = size.minDimension
    val pivot = Offset(size.width * 0.94f, size.height * 0.07f)
    val len = d * 0.226f
    val armW = d * 0.026f
    val headH = armW * 1.6f
    rotate(angleDegrees, pivot) {
        // 臂身软阴影：先画一层向局部下方偏移的深色剪影，再叠亮色臂身，形成立体落差。
        val shadow = Offset(0f, d * 0.010f)
        drawLine(
            Color.Black.copy(alpha = 0.25f),
            start = pivot + shadow,
            end = pivot + Offset(len * 0.80f, 0f) + shadow,
            strokeWidth = armW,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            Color.Black.copy(alpha = 0.25f),
            topLeft = pivot + Offset(len * 0.78f, -headH / 2f) + shadow,
            size = Size(len * 0.24f, headH),
            cornerRadius = CornerRadius(headH * 0.5f),
        )
        // 尾端配重锤：短而粗的深色段，伸在转轴背面。
        drawLine(
            Color(0xFF3C3C44),
            start = pivot - Offset(len * 0.20f, 0f),
            end = pivot,
            strokeWidth = armW * 1.35f,
            cap = StrokeCap.Round,
        )
        // 主臂：浅金属渐变 + 一根发丝高光线。
        drawLine(
            Brush.linearGradient(
                colors = listOf(Color(0xFFF3F3F6), Color(0xFFC2C2CA)),
                start = pivot,
                end = pivot + Offset(len * 0.80f, 0f),
            ),
            start = pivot,
            end = pivot + Offset(len * 0.80f, 0f),
            strokeWidth = armW,
            cap = StrokeCap.Round,
        )
        drawLine(
            Color.White.copy(alpha = 0.5f),
            start = pivot + Offset(0f, -armW * 0.22f),
            end = pivot + Offset(len * 0.78f, -armW * 0.22f),
            strokeWidth = armW * 0.16f,
            cap = StrokeCap.Round,
        )
        // 弯头关节连接唱头。
        drawCircle(Color(0xFFD9D9DF), radius = armW * 0.75f, center = pivot + Offset(len * 0.80f, 0f))
        // 唱头壳 + 深色针尖（唱针朝盘面方向探出）。
        drawRoundRect(
            Color(0xFFE9E9EE),
            topLeft = pivot + Offset(len * 0.80f, -headH / 2f),
            size = Size(len * 0.22f, headH),
            cornerRadius = CornerRadius(headH * 0.4f),
        )
        drawLine(
            Color(0xFF141418),
            start = pivot + Offset(len * 1.005f, 0f),
            end = pivot + Offset(len * 0.96f, headH * 0.8f),
            strokeWidth = d * 0.005f,
            cap = StrokeCap.Round,
        )
    }
    // 转轴底座画在臂身之下：径向渐变的圆座 + 深色轴心 + 一点受光高光。
    drawCircle(
        Brush.radialGradient(
            colors = listOf(Color(0xFF5A5A64), Color(0xFF2B2B32)),
            center = pivot,
            radius = d * 0.052f,
        ),
        radius = d * 0.052f,
        center = pivot,
    )
    drawCircle(
        Color(0xFF17171B),
        radius = d * 0.052f,
        center = pivot,
        style = Stroke(width = d * 0.004f),
    )
    drawCircle(Color(0xFF1D1D23), radius = d * 0.020f, center = pivot)
    drawCircle(
        Color.White.copy(alpha = 0.30f),
        radius = d * 0.009f,
        center = pivot - Offset(d * 0.016f, d * 0.016f),
    )
}
