package com.taotao.music.ui.player.skin

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 扇形卡叠皮肤：主封面是一张圆角方卡，背后藏着两张同尺寸的主题色卡片，
 * 播放时向两侧展开成扇形，暂停时收拢对齐。
 *
 * 播放态即扇叶开合：展开角度由 [isPlaying] 经展开系数驱动（reduce-motion 时直接跳变）。
 * 不旋转封面本体，[rotationDegrees] 被刻意忽略（见 [CoverSkin] 的语义说明）。
 */
@Composable
internal fun FanSkin(
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    // 扇叶展开系数：播放 1（各展开 13°），暂停 0（收拢，与封面完全对齐）。
    val spread by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(DURATION_FAN_SPREAD, easing = FastOutSlowInEasing),
        label = "fanSpread",
    )
    Box(modifier.size(discSize), contentAlignment = Alignment.Center) {
        // 背后两张色卡：左右各一张，播放时向外张开成扇形。
        FanBackCard(
            rotation = 13f * spread,
            tint = fallbackColor.copy(alpha = 0.55f),
            corner = discSize * 0.12f,
            cardSize = discSize * 0.74f,
            lift = -discSize * 0.02f,
        )
        FanBackCard(
            rotation = -13f * spread,
            tint = fallbackColor.copy(alpha = 0.75f),
            corner = discSize * 0.12f,
            cardSize = discSize * 0.74f,
            lift = -discSize * 0.02f,
        )
        // 主封面卡：圆角方卡，圆角约 12% 边长。
        val cardSide = discSize * 0.78f
        Box(
            Modifier
                .size(cardSide)
                .clip(RoundedCornerShape(discSize * 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
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
}

/** 扇叶展开动画时长（毫秒）。 */
private const val DURATION_FAN_SPREAD = 450

/** 背后的扇叶色卡：先上移一点再绕自身中心旋转，收拢时完全藏到主封面后面。 */
@Composable
private fun FanBackCard(
    rotation: Float,
    tint: Color,
    corner: Dp,
    cardSize: Dp,
    lift: Dp,
) {
    Box(
        Modifier
            .offset(y = lift)
            .size(cardSize)
            .graphicsLayer { rotationZ = rotation }
            .clip(RoundedCornerShape(corner))
            .background(tint),
    )
}
