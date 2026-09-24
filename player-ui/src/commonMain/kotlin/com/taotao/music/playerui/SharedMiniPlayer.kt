package com.taotao.music.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.taotao.music.playerui.theme.TaotaoElevation
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.taotaoShadowColors

/**
 * Android 与 Windows 共用的迷你播放器骨架。
 *
 * 播放状态和事件由平台层单向传入；音量、队列、定时器等平台能力通过插槽组合，
 * 避免公共组件直接依赖平台播放器实现。
 *
 * 阴影走 [TaotaoElevation.raised]：它是常驻的贴边栏，不是模态弹层，
 * 层次上只需要比列表「略高一点」。
 */
@Composable
fun SharedMiniPlayer(
    state: PlayerUiState,
    actions: PlayerActions,
    artworkContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val shadow = taotaoShadowColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = TaotaoElevation.raised,
                shape = TaotaoShapes.button,
                ambientColor = shadow.ambient,
                spotColor = shadow.spot,
            )
            .background(MaterialTheme.colorScheme.surface, TaotaoShapes.button)
            .clip(TaotaoShapes.button),
    ) {
        if (state.durationMs > 0L) {
            LinearProgressIndicator(
                progress = { normalizedPlayerProgress(state.positionMs, state.durationMs) },
                modifier = Modifier.fillMaxWidth().height(TaotaoStroke.medium),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(TaotaoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(TaotaoSizes.artworkRow)
                    .clip(TaotaoShapes.button)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                artworkContent()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = TaotaoSpacing.sm),
            ) {
                Text(
                    text = state.song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    // 与 SharedSongRow 的歌曲名保持一致的字重。此前这里用 SemiBold(600)、
                    // 列表行用 Bold(700)，同一首歌在两个位置字重不同；而 600 在中文字体上
                    // 本就可能被吸附到 500 或 700，不同 ROM 表现不一。见 TaotaoTypography 的说明。
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                supportingContent?.invoke(this)
            }
            actions.onPrevious?.let { onPrevious ->
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一首")
                }
            }
            IconButton(
                onClick = actions.onTogglePlaying,
                enabled = !state.isBuffering,
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(TaotaoSizes.iconLg),
                )
            }
            actions.onNext?.let { onNext ->
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首")
                }
            }
            trailingContent?.invoke(this)
        }
    }
}
