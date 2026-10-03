package com.taotao.music.playerui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.ApplePlayButton
import com.taotao.music.playerui.theme.AppleStyleSlider
import com.taotao.music.playerui.theme.TaotaoElevation
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
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
 * 阴影颜色随亮暗主题走：此前这里写死 `Color.Black.copy(alpha = 0.5f)`，
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

/** 三端一致的歌曲标题与歌手、专辑信息区域。 */
@Composable
fun PlayerSongHeader(
    song: Song,
    modifier: Modifier = Modifier,
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    metadataTrailingContent: (@Composable RowScope.() -> Unit)? = null,
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
            Text(
                text = buildString {
                    append(song.artist)
                    song.album.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            metadataTrailingContent?.invoke(this)
        }
    }
}

/**
 * 三端共用的播放详情主体。
 *
 * 平台只负责提供 [PlayerUiState]、[PlayerActions] 和可选插槽，组件不直接读取播放器对象。
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = durationLabel,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            val refrainStart = state.refrainStartMs
            val refrainEnd = state.refrainEndMs
            if (durationMs > 0L && refrainStart != null && refrainEnd != null && refrainEnd > refrainStart) {
                Text(
                    text = "高潮 ${formatPlayerTime(refrainStart)}–${formatPlayerTime(refrainEnd)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                )
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
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
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
    val progress = normalizedPlayerProgress(positionMs, durationMs)
    Box(modifier) {
        AppleStyleSlider(
            progress = progress,
            onProgressChange = { onSeek(playerPositionForProgress(it, durationMs)) },
            onProgressChangeFinished = onSeekFinished,
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        val start = (refrainStartMs ?: -1L).toFloat() / durationMs.coerceAtLeast(1L)
        val end = (refrainEndMs ?: -1L).toFloat() / durationMs.coerceAtLeast(1L)
        if (start in 0f..1f && end > start) {
            // 高潮区间标记：用主题强调色在轨道上叠一段圆头胶囊，和 4dp 轨道同高、随明暗主题走。
            // 此前写死 #FF6B6B 且比轨道还粗（5dp），既不随主题变、又像把轨道染了色；
            // 现在改成与轨道同高、圆头收尾的强调色段，读起来是「标记」而不是「变色的轨道」。
            val refrainColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.matchParentSize()) {
                val y = size.height / 2
                drawLine(
                    color = refrainColor,
                    start = Offset(size.width * start, y),
                    end = Offset(size.width * end.coerceAtMost(1f), y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
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
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
