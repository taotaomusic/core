package com.taotao.music.playerui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 播放器公共组件使用的圆角规格，避免三端各自定义形状。 */
object AppleStyleTheme {
    val ArtworkShape = RoundedCornerShape(20.dp)
    val CardShape = RoundedCornerShape(32.dp)
    val ButtonShape = RoundedCornerShape(12.dp)
    val FullRadius = RoundedCornerShape(50)
}
