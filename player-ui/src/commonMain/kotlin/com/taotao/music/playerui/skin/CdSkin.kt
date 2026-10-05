package com.taotao.music.playerui.skin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.Dp

/**
 * CD 光碟皮肤：整张封面就是碟面，中部留一个小巧的透明中孔 + 压环，
 * 靠盘面的扇形彩虹衍射高光表达「这张碟在转」。
 *
 * 与黑胶的区别：CD 的盘面即封面本体（碟面全幅使用封面），
 * 因此衍射高光用低透明度叠加，避免冲掉封面颜色；中孔采用「深色压环 + 内阴影」
 * 的画法，在任何亮色封面上都能读出孔的存在。旋转复用调用方的 [rotationDegrees]，
 * 播放 / 暂停没有专属拟物动作（碟面始终朝上，暂停时仅由接入端的播放键表意）。
 */
@Composable
internal fun CdSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    imageLoader: CoverImageLoader,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 第一层：盘底软投影（固定，不随盘转），与黑胶共用同一画法保持悬浮感一致。
        Canvas(Modifier.fillMaxSize()) { drawSkinDiscShadow() }
        // 第二层：旋转的碟面 —— 封面全幅铺满 + 彩虹衍射高光一起转。
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotationDegrees }) {
            CoverSkinImageSlot(
                url = coverUri,
                fallbackColor = fallbackColor,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
            )
            // 衍射高光跟碟面一起转：光盘的虹彩来自纹道，本来就该随盘转。
            Canvas(Modifier.fillMaxSize()) { drawCdIridescence() }
        }
        // 第三层：中孔与压环（固定层，孔是碟的几何中心，不存在旋转视觉差）。
        Canvas(Modifier.fillMaxSize()) { drawCdHub() }
    }
}

/** CD 中孔直径占碟面的比例：真盘约 15mm / 120mm，略放大到 0.16 提升触屏可读性。 */
private const val HUB_FRACTION = 0.16f

/** 衍射高光：两道对顶的彩虹扇面，低透明度叠加在封面上，模拟压盘纹路的分光。 */
private fun DrawScope.drawCdIridescence() {
    val d = size.minDimension
    val r = d / 2f
    clipPath(
        Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(center, r))
            addOval(Rect(center, r * HUB_FRACTION))
        },
    ) {
        drawCircle(
            Brush.sweepGradient(
                0.00f to Color.Transparent,
                0.06f to Color(0x33FF80AB),
                0.12f to Color(0x3382B1FF),
                0.18f to Color(0x2E69F0AE),
                0.24f to Color.Transparent,
                0.50f to Color.Transparent,
                0.56f to Color(0x2EFFD54F),
                0.62f to Color(0x33B388FF),
                0.68f to Color.Transparent,
                1.00f to Color.Transparent,
                center = center,
            ),
            radius = r,
            center = center,
        )
    }
}

/** 中孔：外圈金属压环 + 内圈深孔 + 孔缘受光，画在固定层，让孔在任何封面上都读得出来。 */
private fun DrawScope.drawCdHub() {
    val d = size.minDimension
    val r = d / 2f
    // 金属压环：浅灰渐变模拟注塑圈，宽度约为中孔半径的四分之一。
    drawCircle(
        Brush.radialGradient(
            0.00f to Color(0xFFF4F4F7),
            0.55f to Color(0xFFD8D8DE),
            1.00f to Color(0xFFB9B9C2),
            center = center,
            radius = r * HUB_FRACTION * 1.55f,
        ),
        radius = r * HUB_FRACTION * 1.55f,
        center = center,
    )
    // 中孔本体：近黑的深孔。
    drawCircle(Color(0xFF141418), radius = r * HUB_FRACTION, center = center)
    // 孔缘内侧一道受光弧线：用短弧模拟上缘反光，增强「有孔」的立体错觉。
    rotate(-135f, center) {
        drawArc(
            Color.White.copy(alpha = 0.35f),
            startAngle = 0f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(center.x - r * HUB_FRACTION, center.y - r * HUB_FRACTION),
            size = Size(r * HUB_FRACTION * 2f, r * HUB_FRACTION * 2f),
            style = Stroke(width = d * 0.004f, cap = StrokeCap.Round),
        )
    }
}
