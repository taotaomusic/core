package com.taotao.music.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.taotao.music.model.Song
import com.taotao.music.playerui.PlayerActions
import com.taotao.music.playerui.PlayerCapabilities
import com.taotao.music.playerui.PlayerCompactLayout
import com.taotao.music.playerui.PlayerRepeatMode
import com.taotao.music.playerui.PlayerUiState
import com.taotao.music.playerui.SharedContentState
import com.taotao.music.playerui.SharedContentStateType
import com.taotao.music.playerui.TaotaoPlayerTheme
import com.taotao.music.playerui.skin.CoverSkin
import com.taotao.music.playerui.skin.CoverSkinId
import com.taotao.music.playerui.skin.rememberCoverRotationState
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

/**
 * 分享页唤起 App 的深链接 scheme。
 *
 * 安卓端 Manifest 声明了 `taotaomusic://open`（BROWSABLE），参数键与
 * `com.taotao.music.data.parseOpenSongLink` 一一对应，两端必须同步改。
 */
private const val OpenAppScheme = "taotaomusic://open"

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
    // 分享链接标识：从路径尾段取出，用于拉取分享内容（与凭据无关，命名避开安全判据的敏感字样）。
    val shareSlug = remember {
        window.location.pathname
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() && it != "s" }
    }

    LaunchedEffect(shareSlug) {
        if (shareSlug == null) {
            share = demoShareSong()
            loading = false
            return@LaunchedEffect
        }
        runCatching {
            val response: Response = window.fetch("/api/v1/public/shares/$shareSlug").await()
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
    // 封面皮肤：读一次 localStorage（键 cover_skin，与 Android 端 SharedPreferences 互不影响），
    // 点选即换并写回；旋转驱动用共享层实现（Web 页面恒在前台、恒在封面页）。
    var coverSkin by remember { mutableStateOf(CoverSkinId.of(window.localStorage.getItem("cover_skin"))) }
    val coverRotation = rememberCoverRotationState(
        isPlaying = audioState.isPlaying,
        active = true,
        onCoverPage = { true },
        restartKey = share.song.title,
    )
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
        // 高潮区间：透传给共用 PlayerProgress（轨道上叠画标记段 + 「高潮 mm:ss–mm:ss」小字）。
        // 进度条分母是 60 秒试听时长，但 positionMs 与高潮区间本就是同一套整曲毫秒坐标
        // （试听守的正是整曲前 60 秒），原始值落在轨道上的位置天然正确，无需换算；
        // 高潮整体落在试听窗口之外（起点比例 > 1）时由组件内 0..1 收敛规则不画，
        // 文案区仍会显示绝对时间。区间无效（缺一或 end<=start）同样由组件守卫不画。
        refrainStartMs = share.refrainStartMs?.toLong(),
        refrainEndMs = share.refrainEndMs?.toLong(),
        repeatMode = PlayerRepeatMode.ONE,
        errorMessage = audioState.errorMessage,
    )

    // TopCenter：内容在矮视口（手机竖屏）里超出时，Center 会把顶部连同标题一起裁出
    // 可滚范围（居中偏移为负），竖向滚动永远回不到顶；竖排列表顶对齐才是正解。
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val artworkSize = if (maxWidth < CompactWidthBreakpoint) ArtworkSizeCompact else ArtworkSizeWide
        // 手机竖屏的视口高不足以放下整页内容（唱片+皮肤行+进度条+三个按钮），
        // 超出部分会被一屏的 canvas 直接裁掉且浏览器侧滚不动（body overflow:hidden，
        // 且 canvas 的 touch-action:none 本就是给 Compose 自己的滚动让路），
        // 所以竖向滚动必须由 Compose 自己做：Column 包 verticalScroll。
        Column(
            modifier = Modifier
                .widthIn(max = ShareContentMaxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TaotaoSpacing.xl, vertical = TaotaoSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "桃桃音乐",
                color = MaterialTheme.colorScheme.primary,
                // 18sp / Bold 正是 titleLarge 档位，改走 token 而不是写字面量。
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(TaotaoSpacing.xl))
            // 共享皮肤框架接入：方卡类皮肤用矩形插槽（不被裁圆），圆形皮肤用默认圆插槽。
            CoverSkin(
                skin = coverSkin,
                coverUri = share.song.coverUri,
                fallbackColor = Color(share.song.color),
                isPlaying = audioState.isPlaying,
                rotationDegrees = coverRotation.degrees,
                discSize = artworkSize,
                imageLoader = remember { FetchCoverImageLoader() },
                modifier = Modifier,
            )
            Spacer(Modifier.height(TaotaoSpacing.sm))
            // 皮肤切换行：九宫格点选即换（写 localStorage，刷新后保留）。
            SkinPickerRow(
                current = coverSkin,
                imageLoader = remember { FetchCoverImageLoader() },
                onSelect = { coverSkin = it },
            )
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
            // 「在桃桃音乐中打开」只在安卓设备上出现：桌面浏览器没有该 scheme 的接收方，
            // 点了只会弹「未知协议」错误；iOS 本来就没有桃桃音乐。装了 App 的用户
            // 点它即可把这首歌接续到 App 里完整播放，不必再下一个安装包。
            val openAppUrl = remember(share) { if (runningOnAndroid()) share.openAppUrl() else null }
            openAppUrl?.let { url ->
                Spacer(Modifier.height(TaotaoSpacing.sm))
                Button(
                    onClick = { window.location.href = url },
                    modifier = Modifier.fillMaxWidth().height(DownloadButtonHeight),
                    shape = TaotaoShapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(TaotaoSizes.iconSm))
                    Text("打开桃桃音乐，接续完整播放", modifier = Modifier.padding(start = TaotaoSpacing.xs))
                }
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

/**
 * 皮肤切换行：九宫格点选即换。缩略图用真实皮肤控件画无图预览（所见即所选），
 * 选择写 localStorage（键 cover_skin），刷新后保留。
 */
@Composable
private fun SkinPickerRow(
    current: CoverSkinId,
    imageLoader: com.taotao.music.playerui.skin.CoverImageLoader,
    onSelect: (CoverSkinId) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
        for (id in CoverSkinId.entries) {
            IconButton(onClick = { onSelect(id) }) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(if (id.squareArt) RectangleShape else CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = if (id == current) 2.dp else 1.dp,
                            color = if (id == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = if (id.squareArt) RectangleShape else CircleShape,
                        ),
                ) {
                    CoverSkin(
                        skin = id,
                        coverUri = null,
                        fallbackColor = MaterialTheme.colorScheme.primary,
                        isPlaying = false,
                        rotationDegrees = 0f,
                        discSize = 24.dp,
                        imageLoader = imageLoader,
                    )
                }
            }
        }
    }
}

/** 深链接的参数值里会出现标题、封面直链等非 ASCII 字符，逐值走百分号编码。 */
@JsFun("(value) => encodeURIComponent(value)")
private external fun encodeURIComponent(value: String): String

@JsFun("() => navigator.userAgent")
private external fun navigatorUserAgent(): String

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L).toInt()
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** 只有安卓设备才显示「打开」入口：iOS 没有桃桃音乐，桌面浏览器点自定义 scheme 只会报错。 */
private fun runningOnAndroid(): Boolean =
    navigatorUserAgent().contains("Android", ignoreCase = true)

/**
 * 拼接「在桃桃音乐中打开」的深链接。
 *
 * 歌曲缺身份（既无数字 ID 也无 mid）时返回 null，按钮随之隐藏；演示卡没有身份，
 * 走的正是这条路。参数键与安卓端 `parseOpenSongLink` 一一对应，两端必须同步改。
 */
private fun ShareSong.openAppUrl(): String? {
    val id = song.remoteId?.takeIf { it > 0L }
    val mid = song.mid?.takeIf { it.isNotBlank() }
    if (id == null && mid == null) return null
    val parts = mutableListOf<String>()
    fun add(key: String, value: String) {
        parts.add("$key=${encodeURIComponent(value)}")
    }
    id?.let { add("id", it.toString()) }
    mid?.let { add("mid", it) }
    song.type?.let { add("type", it.toString()) }
    add("source", song.source.ifBlank { "tencent" })
    add("title", song.title)
    add("artist", song.artist)
    if (song.album.isNotBlank()) add("album", song.album)
    song.coverUri?.takeIf { it.isNotBlank() }?.let { add("cover", it) }
    if (song.duration.isNotBlank()) add("duration", song.duration)
    if (song.vip) add("vip", "1")
    refrainStartMs?.let { add("rs", it.toString()) }
    refrainEndMs?.let { add("re", it.toString()) }
    return "$OpenAppScheme?${parts.joinToString("&")}"
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
