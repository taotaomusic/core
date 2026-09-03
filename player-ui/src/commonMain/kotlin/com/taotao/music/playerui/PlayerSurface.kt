package com.taotao.music.playerui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.model.Song

/** 公共封面插槽；图片加载由 Android、Windows 和未来 Web 各自提供。 */
@Composable
fun PlayerArtworkSlot(
    size: Dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 播放详情中两端一致的歌曲信息区域。 */
@Composable
fun PlayerSongHeader(
    song: Song,
    qualityLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        androidx.compose.material3.Text(
            text = song.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                text = song.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (song.album.isNotBlank()) {
                androidx.compose.material3.Text(
                    text = " · ${song.album}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
            if (!qualityLabel.isNullOrBlank()) {
                androidx.compose.material3.Text(
                    text = qualityLabel,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

/** 两端共用的进度条；时间和实际 seek 由平台页面格式化。 */
@Composable
fun PlayerProgress(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val progress = if (duration > 0L) {
        (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    Slider(
        value = progress,
        onValueChange = { onSeek((it * duration).toLong()) },
        onValueChangeFinished = onSeekFinished,
        enabled = duration > 0L,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 两端共用的上一首、播放、下一首和循环控制。 */
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
                    imageVector = if (state.repeatMode == PlayerRepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
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
                )
            }
        }
        if (capabilities.showPreviousNext) {
            IconButton(onClick = { actions.onPrevious?.invoke() }) {
                Icon(Icons.Default.SkipPrevious, "上一首", modifier = Modifier.size(30.dp))
            }
        }
        FilledIconButton(
            onClick = actions.onTogglePlaying,
            enabled = !state.isBuffering,
            modifier = Modifier.size(60.dp).clip(CircleShape),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = if (state.isPlaying) androidx.compose.material.icons.Icons.Default.Pause else androidx.compose.material.icons.Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "暂停" else "播放",
                modifier = Modifier.size(30.dp),
            )
        }
        if (capabilities.showPreviousNext) {
            IconButton(onClick = { actions.onNext?.invoke() }) {
                Icon(Icons.Default.SkipNext, "下一首", modifier = Modifier.size(30.dp))
            }
        }
        trailingContent?.invoke()
    }
    if (state.isBuffering) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.errorMessage?.let {
        androidx.compose.material3.Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
