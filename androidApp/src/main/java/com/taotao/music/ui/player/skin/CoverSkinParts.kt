package com.taotao.music.ui.player.skin

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

// 皮肤控件的公共绘制小件。只放至少两个皮肤共用的几何，
// 单个皮肤私有的画法留在它自己的文件里，避免公共件变成杂物抽屉。

/** 圆形盘底投影：两层错位的半透明圆叠出软阴影，营造唱片悬浮在页面上的厚度感（黑胶 / CD 共用）。 */
internal fun DrawScope.drawSkinDiscShadow() {
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
