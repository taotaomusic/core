package com.taotao.music.web

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import com.taotao.music.model.Song
import com.taotao.music.playerui.PlayerActions
import com.taotao.music.playerui.PlayerArtworkSlot
import com.taotao.music.playerui.PlayerCapabilities
import com.taotao.music.playerui.PlayerCompactLayout
import com.taotao.music.playerui.PlayerRepeatMode
import com.taotao.music.playerui.PlayerUiState
import com.taotao.music.playerui.SharedContentState
import com.taotao.music.playerui.SharedContentStateType
import com.taotao.music.playerui.TaotaoPlayerTheme
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoTypography
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.Response
import kotlin.js.JsString
import org.jetbrains.compose.resources.Font
import org.jetbrains.skia.Image as SkiaImage
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import taotaomusic.webapp.generated.resources.Res
import taotaomusic.webapp.generated.resources.noto_sans_sc_regular

/**
 * 分享页封面尺寸：窄屏与宽屏两档。
 *
 * 刻意不进 TaotaoSizes：分享页是「一整屏只有一张封面」的页面，
 * 封面必须比列表和播放页都大，这是这一屏的构图决定。
 */
private val ArtworkSizeCompact = 248.dp
private val ArtworkSizeWide = 300.dp

/** 窄屏断点：宽度低于它时封面取紧凑档。 */
private val CompactWidthBreakpoint = 520.dp

/** 正文最大宽度。再宽下去单行文字会超过舒适阅读长度。 */
private val ShareContentMaxWidth = 480.dp

/** 「下载完整版」按钮的固定高度。 */
private val DownloadButtonHeight = 52.dp

/** 封面缺失时那颗 ♫ 占位符的字号：要撑满整个圆形底衬。 */
private val FallbackGlyphSize = 96.sp

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        val dark = window.matchMedia("(prefers-color-scheme: dark)").matches
        val webFontFamily = FontFamily(Font(Res.font.noto_sans_sc_regular))
        TaotaoPlayerTheme(
            darkTheme = dark,
            // 用项目排版 token 而不是 Material 默认值，否则 Web 端字号会与 Android / Windows 分叉。
            // withFontFamily 负责把内置中文字体盖到 token 的字号/行距/字重之上。
            typography = TaotaoTypography.withFontFamily(webFontFamily),
        ) {
            SharePlayerApp()
        }
    }
}

@Composable
private fun SharePlayerApp() {
    var share by remember { mutableStateOf<ShareSong?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val token = remember {
        window.location.pathname
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() && it != "s" }
    }

    LaunchedEffect(token) {
        if (token == null) {
            share = demoShareSong()
            loading = false
            return@LaunchedEffect
        }
        runCatching {
            val response: Response = window.fetch("/api/v1/public/shares/$token").await()
            check(response.ok) { "分享链接不存在或已失效" }
            val payloadText: JsString = response.text().await()
            val payload = payloadText.toString()
            parseShareSong(payload)
        }.onSuccess {
            share = it
        }.onFailure {
            loadError = it.message ?: "歌曲信息加载失败"
        }
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            loading -> SharedContentState(
                type = SharedContentStateType.LOADING,
                title = "正在加载歌曲…",
                modifier = Modifier.fillMaxSize(),
            )
            loadError != null -> SharedContentState(
                type = SharedContentStateType.ERROR,
                title = "歌曲加载失败",
                description = loadError,
                modifier = Modifier.fillMaxSize(),
            )
            share != null -> SharePlayerPage(requireNotNull(share))
        }
    }
}

@Composable
private fun SharePlayerPage(share: ShareSong) {
    var audioState by remember(share.previewUrl) {
        mutableStateOf(
            WebAudioState(
                durationMs = share.previewDurationSeconds * 1_000L,
                isBuffering = share.previewUrl.isNotBlank(),
            ),
        )
    }
    val controller = remember(share.previewUrl) {
        WebAudioController(share.previewDurationSeconds) { audioState = it }
    }
    DisposableEffect(controller, share.previewUrl) {
        if (share.previewUrl.isNotBlank()) {
            controller.load(share.previewUrl)
        }
        onDispose(controller::release)
    }
    val state = PlayerUiState(
        song = share.song,
        isPlaying = audioState.isPlaying,
        isBuffering = audioState.isBuffering,
        positionMs = audioState.positionMs,
        durationMs = audioState.durationMs,
        repeatMode = PlayerRepeatMode.ONE,
        errorMessage = audioState.errorMessage,
    )

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val artworkSize = if (maxWidth < CompactWidthBreakpoint) ArtworkSizeCompact else ArtworkSizeWide
        Column(
            modifier = Modifier
                .widthIn(max = ShareContentMaxWidth)
                .fillMaxWidth()
                .padding(TaotaoSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "桃桃音乐",
                color = MaterialTheme.colorScheme.primary,
                // 18sp / Bold 正是 titleLarge 档位，改走 token 而不是写字面量。
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(TaotaoSpacing.xl))
            PlayerArtworkSlot(size = artworkSize) {
                RemoteArtwork(share.song.coverUri, Color(share.song.color))
            }
            Spacer(Modifier.height(TaotaoSpacing.xl))
            PlayerCompactLayout(
                state = state,
                actions = PlayerActions(
                    onTogglePlaying = controller::togglePlaying,
                    onSeek = controller::seekTo,
                    onToggleRepeat = {},
                ),
                positionLabel = formatTime(audioState.positionMs),
                durationLabel = formatTime(audioState.durationMs),
                capabilities = PlayerCapabilities(showPreviousNext = false, showRepeat = false),
            )
            Spacer(Modifier.height(TaotaoSpacing.xl))
            Button(
                onClick = { window.location.href = share.appDownloadUrl },
                modifier = Modifier.fillMaxWidth().height(DownloadButtonHeight),
                // 8dp 不在圆角刻度里（刻度是 6/10/14/20/28），对齐到 small。
                shape = TaotaoShapes.small,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(TaotaoSizes.iconSm))
                Text("下载桃桃音乐，完整播放", modifier = Modifier.padding(start = TaotaoSpacing.xs))
            }
            Text(
                text = "标准音质 · 最多 60 秒试听",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = TaotaoSpacing.sm),
            )
        }
    }
}

@Composable
private fun RemoteArtwork(url: String?, fallbackColor: Color) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = url?.takeIf(String::isNotBlank)?.let { runCatching { loadImageBitmap(it) }.getOrNull() }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = "专辑封面",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(fallbackColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("♫", color = Color.White, fontSize = FallbackGlyphSize)
        }
    }
}

private suspend fun loadImageBitmap(url: String): ImageBitmap {
    val response: Response = window.fetch(url).await()
    check(response.ok) { "专辑封面加载失败" }
    val buffer: ArrayBuffer = response.arrayBuffer().await()
    val source = Int8Array(buffer)
    val bytes = ByteArray(source.length) { index -> int8At(source, index).toByte() }
    return SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}

@JsFun("(array, index) => array[index]")
private external fun int8At(array: Int8Array, index: Int): Int

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L).toInt()
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Canvas/Wasm 不会稳定继承浏览器系统字体，所有 Material3 文本样式显式使用内置中文字体。 */
private fun Typography.withFontFamily(fontFamily: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily),
)

private fun demoShareSong() = ShareSong(
    song = Song(
        title = "桃桃音乐试听",
        artist = "分享歌曲",
        album = "浏览器预览",
        duration = "01:00",
        color = 0xFFFA5E5B,
    ),
    previewUrl = "",
    appDownloadUrl = "/download",
    previewDurationSeconds = 60,
)
