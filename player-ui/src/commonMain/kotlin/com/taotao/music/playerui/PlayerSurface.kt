package com.taotao.music.playerui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.ApplePlayButton
import com.taotao.music.playerui.theme.AppleStyleSlider
import com.taotao.music.playerui.theme.TaotaoElevation
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoWash
import com.taotao.music.playerui.theme.taotaoShadowColors

/**
 * 公共封面插槽；图片加载仍由 Android、Windows 和 Web 分别实现。
 *
 * [shape] **同时作用于阴影和裁切，必须与 [content] 实际画出的形状一致**。
 *
 * 此前这里固定用 `TaotaoShapes.artwork`（20dp 圆角矩形）投影，而封面内容画的是圆形，
 * 于是圆形封面背后会露出一圈**直角矩形的阴影带**，看起来像一块方块鬼影。
 * 截图实测：矩形阴影比圆形封面每边多出约 30dp，四个角最明显。
 * 所以默认值改成 [CircleShape] —— 封面在三个平台上本来就是圆的。
 *
 * 阴影颜色随亮暗主题走：此前这里把阴影写死为纯黑 50% 透明，
 * 而 [SharedMiniPlayer] 用的是 0.1f，同一套界面里两处阴影相差 5 倍。
 */
@Composable
fun PlayerArtworkSlot(
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable () -> Unit,
) {
    val shadow = taotaoShadowColors()
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = TaotaoElevation.overlay,
                shape = shape,
                ambientColor = shadow.ambient,
                spotColor = shadow.spot,
            )
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 三端一致的歌曲标题与歌手、专辑信息区域。
 *
 * 「歌手 · 专辑」信息行拆成歌手段与「 · 专辑」段两段 [Text]，各自支持点击跳转
 * （Android 播放详情页由此进入歌手主页 / 专辑页）。可点必须同时满足**双门槛**：
 * 1. 调用方传入了对应回调（[onArtistClick] / [onAlbumClick] 非 null）；
 * 2. 歌曲带对应的音源内 ID（[Song.artistId] / [Song.albumId]，目前只有酷我搜索结果下发，
 *    其他来源为 null）。
 * 缺任何一个都渲染纯文本，外观与不可点时完全一致；可点时也只给文字本身加轻微按压反馈，
 * 不加下划线等额外暗示。
 *
 * 视觉与旧版单 Text 等价：专辑名为空白时「 · 专辑」段整体不渲染（连分隔符一起消失）；
 * 空间不足时从行尾开始省略 —— 整块文本先让位给行尾内容（weight），块内歌手段先测量、
 * 专辑段吃剩余宽度，拼接超宽的截断点与旧单 Text 完全相同（若两段等权分配，
 * 短歌手名旁的长专辑名会在明明还有空间时提前省略）。
 */
@Composable
fun PlayerSongHeader(
    song: Song,
    modifier: Modifier = Modifier,
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    metadataTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            titleTrailingContent?.invoke(this)
        }
        Row(
            modifier = Modifier.padding(top = TaotaoSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 「歌手 · 专辑」两段文本放进一块可收缩区域：这块区域用 weight 排在行尾内容
            // （音质标签等）之后测量，行尾内容先测量、永远不被挤压；块内歌手段先测量、
            // 专辑段吃剩余宽度，整行超宽时的省略位置与旧版单 Text 完全相同。
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 双门槛（回调 + 音源内歌手 ID，酷我搜索独有）：缺任何一个都保持纯文本。
                val artistClickable = onArtistClick != null && song.artistId != null
                Text(
                    text = song.artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (artistClickable) {
                        Modifier.clickable(
                            onClickLabel = "查看歌手主页",
                            onClick = { onArtistClick?.invoke() },
                        )
                    } else {
                        Modifier
                    },
                )
                // 「 · 专辑」段：专辑名为空白时整段不渲染（连分隔符一起消失，与旧版一致）；
                // 可点同样过双门槛（回调 + song.albumId）。
                if (song.album.isNotBlank()) {
                    val albumClickable = onAlbumClick != null && song.albumId != null
                    Text(
                        text = " · ${song.album}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (albumClickable) {
                            Modifier.clickable(
                                onClickLabel = "查看专辑主页",
                                onClick = { onAlbumClick?.invoke() },
                            )
                        } else {
                            Modifier
                        },
                    )
                }
            }
            metadataTrailingContent?.invoke(this)
        }
    }
}

/**
 * 三端共用的播放详情主体。
 *
 * 平台只负责提供 [PlayerUiState]、[PlayerActions] 和可选插槽，组件不直接读取播放器对象。
 * [onArtistClick] / [onAlbumClick] 原样透传给 [PlayerSongHeader]（歌手 / 专辑名点击跳转，
 * 组件内还有「歌曲带对应音源 ID」的第二道门槛）。
 */
@Composable
fun PlayerPlaybackDetails(
    state: PlayerUiState,
    actions: PlayerActions,
    positionLabel: String,
    durationLabel: String,
    modifier: Modifier = Modifier,
    capabilities: PlayerCapabilities = PlayerCapabilities(),
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    metadataTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    quickActions: (@Composable RowScope.() -> Unit)? = null,
    controlLeadingContent: (@Composable () -> Unit)? = null,
    controlTrailingContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = TaotaoSpacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerSongHeader(
                song = state.song,
                modifier = Modifier.weight(1f),
                titleTrailingContent = titleTrailingContent,
                metadataTrailingContent = metadataTrailingContent,
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
            )
            if (headerActions != null) {
                Row(modifier = Modifier.padding(start = TaotaoSpacing.md)) {
                    headerActions()
                }
            }
        }

        // 快捷操作行：下载、分享这类低频动作放在进度条上方居中一排，
        // 不进顶栏 —— 顶栏按钮多了会把歌名和歌手压到只显示一两个字。
        if (quickActions != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = TaotaoSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxl, Alignment.CenterHorizontally),
            ) {
                quickActions()
            }
        }

        if (capabilities.showProgress) {
            PlayerProgress(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                refrainStartMs = state.refrainStartMs,
                refrainEndMs = state.refrainEndMs,
                onSeek = actions.onSeek,
                onSeekFinished = actions.onSeekFinished,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xxs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = positionLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = durationLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            val refrainStart = state.refrainStartMs
            val refrainEnd = state.refrainEndMs
            // 标签与轨道标记同条件：区间完整且归一化起点落在轨道内。webApp 试听窗外的高潮
            // 区间不可见也不显示标签——标签是跳转入口，点了跳不过去的就不该出现。
            if (refrainStart != null && refrainEnd != null && refrainEnd > refrainStart && state.durationMs > 0L &&
                refrainStart.toFloat() / state.durationMs in 0f..1f
            ) {
                // 可点的跳转标签：手动拖动很难精确停在高潮起点，点它直接跳过去。
                // onSeek 与 onSeekFinished 连调：安卓端 onSeek 只暂存目标位置，onSeekFinished 才真正 seek。
                Row(
                    modifier = Modifier
                        .padding(top = TaotaoSpacing.xxs)
                        .clip(TaotaoShapes.badge)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = TaotaoWash.tint))
                        .clickable(onClickLabel = "跳到高潮起点") {
                            actions.onSeek(refrainStart)
                            actions.onSeekFinished()
                        }
                        .padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.tightVertical),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.iconLabelGap),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(TaotaoSizes.iconXs),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "高潮 ${formatPlayerTime(refrainStart)}–${formatPlayerTime(refrainEnd)}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(TaotaoSpacing.xxl))

        PlayerTransportControls(
            state = state,
            actions = actions,
            capabilities = capabilities,
            leadingContent = controlLeadingContent,
            trailingContent = controlTrailingContent,
        )
    }
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = (totalSeconds / 60L).toString().padStart(2, '0')
    val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
    return "$minutes:$seconds"
}

/** 三端共用的播放进度条，拖动结束事件由平台层提交给实际播放器。 */
@Composable
fun PlayerProgress(
    positionMs: Long,
    durationMs: Long,
    refrainStartMs: Long? = null,
    refrainEndMs: Long? = null,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 高潮区间换算成轨道归一化坐标后交给滑块自绘：它是轨道的底层属性，
    // 已播段从上面扫过，而不是盖在播放进度上的一块贴片（详见 AppleStyleSlider 的轨道绘制）。
    val start = (refrainStartMs ?: -1L).toFloat() / durationMs.coerceAtLeast(1L)
    val end = (refrainEndMs ?: -1L).toFloat() / durationMs.coerceAtLeast(1L)
    val hasHighlight = start in 0f..1f && end > start
    AppleStyleSlider(
        progress = normalizedPlayerProgress(positionMs, durationMs),
        onProgressChange = { onSeek(playerPositionForProgress(it, durationMs)) },
        onProgressChangeFinished = onSeekFinished,
        enabled = durationMs > 0L,
        highlightStart = start.takeIf { hasHighlight },
        highlightEnd = end.takeIf { hasHighlight },
        modifier = modifier,
    )
}

/** 三端共用的循环、上一首、播放、下一首控制区。 */
@Composable
fun PlayerTransportControls(
    state: PlayerUiState,
    actions: PlayerActions,
    capabilities: PlayerCapabilities = PlayerCapabilities(),
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        leadingContent?.invoke()
        if (capabilities.showRepeat) {
            IconButton(onClick = actions.onToggleRepeat) {
                Icon(
                    imageVector = if (state.repeatMode == PlayerRepeatMode.ONE) {
                        Icons.Default.RepeatOne
                    } else {
                        Icons.Default.Repeat
                    },
                    contentDescription = when (state.repeatMode) {
                        PlayerRepeatMode.OFF -> "关闭循环"
                        PlayerRepeatMode.ALL -> "列表循环"
                        PlayerRepeatMode.ONE -> "单曲循环"
                    },
                    tint = if (state.repeatMode == PlayerRepeatMode.OFF) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(TaotaoSizes.iconMd),
                )
            }
        }
        if (capabilities.showPreviousNext) {
            IconButton(onClick = { actions.onPrevious?.invoke() }) {
                Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(TaotaoSizes.iconButton))
            }
        }
        ApplePlayButton(
            isPlaying = state.isPlaying,
            onClick = actions.onTogglePlaying,
            playIcon = Icons.Default.PlayArrow,
            pauseIcon = Icons.Default.Pause,
            modifier = Modifier.padding(horizontal = TaotaoSpacing.xs),
            enabled = !state.isBuffering,
        )
        if (capabilities.showPreviousNext) {
            IconButton(onClick = { actions.onNext?.invoke() }) {
                Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(TaotaoSizes.iconButton))
            }
        }
        trailingContent?.invoke()
    }

    if (state.isBuffering) {
        Spacer(Modifier.height(TaotaoSpacing.md))
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
    }
    state.errorMessage?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = TaotaoSpacing.xs),
        )
    }
}
