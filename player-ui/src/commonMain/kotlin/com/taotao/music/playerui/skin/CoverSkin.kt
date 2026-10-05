package com.taotao.music.playerui.skin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

/**
 * 封面皮肤模板：接入端唯一的绘制入口，按 [skin] 分派给具体皮肤控件。
 *
 * 所有皮肤共享同一份输入，实现新皮肤时不得收窄这些语义：
 * - [coverUri] / [fallbackColor]：封面图地址，以及无图 / 加载失败时的兜底色（歌曲主题色）；
 * - [isPlaying]：播放态。皮肤用它表达「正在播放」（唱臂落下、卷轴转动、光环提亮等），
 *   允许某个皮肤不用它，但必须在自己的注释里写明播放态由什么表达；
 * - [rotationDegrees]：调用方持有的封面旋转角（见 [rememberCoverRotationState]）。
 *   旋转类皮肤直接用它转盘面；不旋转的皮肤可以忽略；
 * - [discSize]：等边正方形边长，皮肤的全部几何都以它为基准等比缩放，
 *   且不得画出这个正方形之外（光晕这类向外发散的元素要在边界内淡出），
 *   这样接入端才能用同一个尺寸预算喂给所有皮肤；
 * - [imageLoader]：封面图加载插槽（本框架唯一平台差异点，见 [CoverImageLoader]）。
 *
 * 接入端组合示例（Android 详情页 / Web 分享播放器都长这样）：
 * ```
 * CoverSkin(
 *     skin = skin, coverUri = song.coverUri, fallbackColor = Color(song.color),
 *     isPlaying = isPlaying, rotationDegrees = rotationState.degrees,
 *     discSize = size, imageLoader = 各端提供的加载器,
 * )
 * ```
 */
@Composable
public fun CoverSkin(
    skin: CoverSkinId,
    coverUri: String?,
    fallbackColor: Color,
    isPlaying: Boolean,
    rotationDegrees: Float,
    discSize: Dp,
    imageLoader: CoverImageLoader,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(discSize)) {
        when (skin) {
            CoverSkinId.VINYL -> VinylSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.CD -> CdSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.CASSETTE -> CassetteSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.GLOW -> GlowSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.CARD -> CardSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.WAVE -> WaveSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.TIDAL -> TidalSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.FAN -> FanSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
            CoverSkinId.NEON_RING -> NeonRingSkin(coverUri, fallbackColor, isPlaying, rotationDegrees, discSize, imageLoader)
        }
    }
}

/**
 * 封面图加载插槽：皮肤框架里唯一的平台差异点。
 *
 * Android 注入 coil 实现（还能读本地 `file:` 封面，离线歌曲的封面在应用私有目录里）；
 * Web 注入 `fetch` + Skia 解码实现；皮肤本体完全不感知加载方式。
 * 实现必须保证无图（[CoverImageLoader.Content] 的 url 为 null / 空）时用
 * [fallbackColor] 画兜底（推荐直接复用 [CoverFallbackArt]），且画满传入的 modifier。
 */
public interface CoverImageLoader {
    /** 皮肤内部通过 [com.taotao.music.playerui.skin.CoverSkinImageSlot] 调用，接入端不要直接使用。 */
    @Composable
    public fun Content(url: String?, fallbackColor: Color, modifier: Modifier)
}

/**
 * 无图兜底：主题色圆底 + 居中音符，字号随容器宽度等比缩放。
 * 各端的 [CoverImageLoader] 实现与各皮肤共用这一个兜底画法，保证观感一致。
 */
@Composable
public fun CoverFallbackArt(fallbackColor: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(fallbackColor), contentAlignment = Alignment.Center) {
        // 显式取 Density 做 Dp→Sp 换算，不依赖容器作用域的隐式 Density 实现。
        val glyph = with(LocalDensity.current) { (maxWidth / 2f).toSp() }
        Text("♫", color = Color.White, fontSize = glyph)
    }
}

/**
 * 封面贴图层：按 [modifier] 给出的形状与占比摆放 [CoverImageLoader] 的内容。
 * 皮肤一律通过它取封面图，不直接 import 各端加载器。
 */
@Composable
internal fun CoverSkinImageSlot(
    url: String?,
    fallbackColor: Color,
    imageLoader: CoverImageLoader,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        imageLoader.Content(url, fallbackColor, Modifier.fillMaxSize())
    }
}
