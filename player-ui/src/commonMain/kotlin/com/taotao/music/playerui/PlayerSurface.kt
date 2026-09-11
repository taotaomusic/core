package com.taotao.music.playerui

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.AppleStyleTheme
import com.taotao.music.playerui.theme.ApplePlayButton
import com.taotao.music.playerui.theme.AppleStyleSlider

/** 公共封面插槽；图片加载仍由 Android、Windows 和 Web 分别实现。 */
@Composable
fun PlayerArtworkSlot(
    size: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(24.dp, AppleStyleTheme.ArtworkShape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(AppleStyleTheme.ArtworkShape),
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
            modifier = Modifier.padding(top = 4.dp),
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
    controlLeadingContent: (@Composable () -> Unit)? = null,
    controlTrailingContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerSongHeader(
                song = state.song,
                modifier = Modifier.weight(1f),
                titleTrailingContent = titleTrailingContent,
                metadataTrailingContent = metadataTrailingContent,
            )
            if (headerActions != null) {
                Row(modifier = Modifier.padding(start = 16.dp)) {
                    headerActions()
                }
            }
        }

        if (capabilities.showProgress) {
            PlayerProgress(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = actions.onSeek,
                onSeekFinished = actions.onSeekFinished,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = positionLabel,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = durationLabel,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        PlayerTransportControls(
            state = state,
            actions = actions,
            capabilities = capabilities,
            leadingContent = controlLeadingContent,
            trailingContent = controlTrailingContent,
        )
    }
}

/** 三端共用的播放进度条，拖动结束事件由平台层提交给实际播放器。 */
@Composable
fun PlayerProgress(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progress = normalizedPlayerProgress(positionMs, durationMs)
    AppleStyleSlider(
        progress = progress,
        onProgressChange = { onSeek(playerPositionForProgress(it, durationMs)) },
        onProgressChangeFinished = onSeekFinished,
        enabled = durationMs > 0L,
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
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (capabilities.showPreviousNext) {
            IconButton(onClick = { actions.onPrevious?.invoke() }) {
                Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(36.dp))
            }
        }
        ApplePlayButton(
            isPlaying = state.isPlaying,
            onClick = actions.onTogglePlaying,
            playIcon = Icons.Default.PlayArrow,
            pauseIcon = Icons.Default.Pause,
            modifier = Modifier.padding(horizontal = 8.dp),
            enabled = !state.isBuffering,
        )
        if (capabilities.showPreviousNext) {
            IconButton(onClick = { actions.onNext?.invoke() }) {
                Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(36.dp))
            }
        }
        trailingContent?.invoke()
    }

    if (state.isBuffering) {
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
    }
    state.errorMessage?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
