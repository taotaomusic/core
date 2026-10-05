package com.taotao.music.ui.player.skin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.taotao.music.playerui.skin.CoverFallbackArt
import com.taotao.music.playerui.skin.CoverImageLoader
import com.taotao.music.ui.theme.AnimationDurations

/**
 * Android 端封面加载器：皮肤框架 [CoverImageLoader] 插槽的 coil 实现。
 *
 * 选 coil 而不是自绘拉取，是因为详情页的封面可能是本地 `file:` URI
 * （离线下载的歌封面存在应用私有目录），coil 同时处理网络缓存与本地文件。
 * 无图或加载失败时退回共享层的 [CoverFallbackArt]（主题色圆底 + 音符）。
 */
class CoilCoverImageLoader : CoverImageLoader {
    @Composable
    override fun Content(url: String?, fallbackColor: Color, modifier: Modifier) {
        if (url.isNullOrBlank()) {
            CoverFallbackArt(fallbackColor, modifier)
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(AnimationDurations.FADE)
                    .build(),
                contentDescription = "专辑封面",
                modifier = modifier,
            )
        }
    }
}
