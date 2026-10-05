package com.taotao.music.ui.player.skin

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion

/**
 * 圆角卡片皮肤：封面是一张带投影的圆角方形卡片，圆角与投影随 [discSize] 等比缩放，
 * 底部一排调色点做点缀，播放时呼吸闪烁。
 *
 * 不旋转：[rotationDegrees] 被刻意忽略，播放态由调色点的呼吸表达（暂停时收敛为暗点）。
 * 现代流媒体的默认形态，适合不喜欢拟物感的场景；与光晕一样没有盘面，
 * 所以也没有「转」的意象。
 */
@Composable
internal fun CardSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "cardDots")
    // 调色点呼吸透明度：降级（暂停 / reduce-motion）时不换 spec，
    // 目标值并回起点 0.45f，起止相同即静止为暗点。
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = if (reduceMotion || !isPlaying) 0.45f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cardDotPulse",
    )
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 专辑卡片：圆角方形容器，投影用 Card 的 elevation 表达悬浮。
        Card(
            modifier = Modifier
                .fillMaxWidth(COVER_CARD_FRACTION)
                .aspectRatio(1f),
            shape = RoundedCornerShape(discSize * 0.055f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = discSize * 0.03f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (coverUri.isNullOrBlank()) {
                    val glyph = with(LocalDensity.current) { (discSize * 0.14f).toSp() }
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
        }
        Spacer(Modifier.height(discSize * 0.045f))
        // 调色点：三颗小圆点，与光晕皮肤同款的点缀语言。
        Row(
            modifier = Modifier.graphicsLayer { alpha = pulse },
            horizontalArrangement = Arrangement.spacedBy(discSize * 0.012f),
        ) {
            for (color in listOf(Color(0xFFFF7A66), Color(0xFFFFC46B), Color(0xFFB388FF))) {
                Box(
                    Modifier
                        .size(discSize * 0.024f)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

/** 专辑卡片宽度占控件边长的比例；下方还要留出调色点行的高度。 */
private const val COVER_CARD_FRACTION = 0.80f
