package com.taotao.music.web

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import com.taotao.music.playerui.skin.CoverFallbackArt
import com.taotao.music.playerui.skin.CoverImageLoader
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.fetch.Response
import org.jetbrains.skia.Image as SkiaImage

/**
 * Web 端封面加载器：皮肤框架 [CoverImageLoader] 插槽的 fetch + Skia 实现。
 *
 * 逻辑与原分享页 RemoteArtwork 完全一致（fetch → ArrayBuffer → SkiaImage.makeFromEncoded），
 * 只是把展示权交回皮肤框架；无图或加载失败退回共享层的 [CoverFallbackArt]。
 */
class FetchCoverImageLoader : CoverImageLoader {
    @Composable
    override fun Content(url: String?, fallbackColor: Color, modifier: Modifier) {
        if (url.isNullOrBlank()) {
            CoverFallbackArt(fallbackColor, modifier)
        } else {
            var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(url) {
                bitmap = runCatching { loadImageBitmap(url) }.getOrNull()
            }
            val loaded = bitmap
            if (loaded != null) {
                Image(
                    bitmap = loaded,
                    contentDescription = "专辑封面",
                    contentScale = ContentScale.Crop,
                    modifier = modifier,
                )
            } else {
                CoverFallbackArt(fallbackColor, modifier)
            }
        }
    }
}

/** 拉取并解码远程封面（与原分享页实现同源：fetch → bytes → SkiaImage）。 */
private suspend fun loadImageBitmap(url: String): ImageBitmap {
    val response: Response = window.fetch(url).await()
    check(response.ok) { "专辑封面加载失败" }
    val buffer: ArrayBuffer = response.arrayBuffer().await()
    val source = Int8Array(buffer)
    val bytes = ByteArray(source.length) { index -> int8At(source, index).toByte() }
    return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}

/** 与 Kotlin/Wasm 交互层取 Int8Array 元素的既有桥接（原 Main.kt 的 @JsFun）。 */
@JsFun("(array, index) => array[index]")
private external fun int8At(array: Int8Array, index: Int): Int
