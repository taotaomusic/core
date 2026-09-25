package com.taotao.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taotao.music.data.PlaybackHistoryEntry
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.common.DragReorderList
import com.taotao.music.ui.common.SongRow
import com.taotao.music.ui.common.songKeyOf
import com.taotao.music.ui.theme.TaotaoCoral
import com.taotao.music.ui.theme.contentFadeIn
import com.taotao.music.ui.theme.contentFadeOut

/**
 * 三类来源共享固定的正文视口，不能让空态、短列表和长列表反复改变 BottomSheet 高度。
 */
private val PlaybackQueueContentHeight = 360.dp

/** 播放队列面板：展示当前队列并支持直接跳到某一首。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackQueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onItemClick: (Int) -> Unit,
    repeatMode: Int,
    onCycleRepeat: () -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onKeepOnlyCurrent: () -> Unit,
    history: List<PlaybackHistoryEntry>,
    localSongs: List<Song>,
    onPlayHistory: (Int) -> Unit,
    onPlayLocal: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
) {
    var selectedSource by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("播放列表", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (selectedSource) {
                            1 -> "从最近播放重新开始"
                            2 -> "从本地下载中选择"
                            else -> if (queue.isEmpty()) "当前没有歌曲" else "第 ${(currentIndex + 1).coerceAtMost(queue.size)} 首 · 共 ${queue.size} 首"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (selectedSource == 0) {
                    TextButton(onClick = onKeepOnlyCurrent, enabled = queue.size > 1) {
                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(TaotaoSizes.iconSm))
                        Spacer(Modifier.width(TaotaoSpacing.xxs))
                        Text("只留当前")
                    }
                }
            }
            Spacer(Modifier.height(TaotaoSpacing.xxs))
            TabRow(
                selectedTabIndex = selectedSource,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = TaotaoCoral,
            ) {
                listOf("当前 ${queue.size}", "最近 ${history.size}", "本地 ${localSongs.size}").forEachIndexed { index, label ->
                    Tab(
                        selected = selectedSource == index,
                        onClick = { selectedSource = index },
                        text = { Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (selectedSource == index) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
            Spacer(Modifier.height(TaotaoSpacing.xs))
            /**
             * 三类来源共享固定的正文视口，不能让空态、短列表和长列表反复改变 BottomSheet 高度。
             * 顶部标题、Tab 和底部圆角的位置因而保持稳定；歌曲多时只在这块区域内滚动。
             */
            Box(Modifier.fillMaxWidth().height(PlaybackQueueContentHeight)) {
                AnimatedContent(
                    targetState = selectedSource,
                    transitionSpec = { contentFadeIn() togetherWith contentFadeOut() using null },
                    label = "播放队列来源",
                ) { source ->
                    when (source) {
                        1 -> PlaybackSourceList(
                            songs = history.map { it.song },
                            emptyText = "还没有播放记录",
                            onItemClick = { index ->
                                onDismiss()
                                onPlayHistory(index)
                            },
                            onPlayNext = onPlayNext,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                        )
                        2 -> PlaybackSourceList(
                            songs = localSongs,
                            emptyText = "还没有本地歌曲",
                            onItemClick = { index ->
                                onDismiss()
                                onPlayLocal(index)
                            },
                            onPlayNext = onPlayNext,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                        )
                        else -> CurrentPlaybackQueue(
                            queue = queue,
                            currentIndex = currentIndex,
                            repeatMode = repeatMode,
                            onCycleRepeat = onCycleRepeat,
                            onItemClick = onItemClick,
                            onRemoveItem = onRemoveItem,
                            onMoveItem = onMoveItem,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
            }
            Spacer(Modifier.height(TaotaoSpacing.md))
        }
    }
}

@Composable
private fun CurrentPlaybackQueue(
    queue: List<Song>,
    currentIndex: Int,
    repeatMode: Int,
    onCycleRepeat: () -> Unit,
    onItemClick: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clip(TaotaoShapes.card)
                .background(MaterialTheme.colorScheme.surface).clickable(onClick = onCycleRepeat)
                .padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                null,
                tint = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    TaotaoCoral
                },
            )
            Column(Modifier.weight(1f).padding(horizontal = TaotaoSpacing.sm)) {
                Text(repeatModeTitle(repeatMode), fontWeight = FontWeight.Bold)
                Text(repeatModeDescription(repeatMode), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("点击切换", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(TaotaoSpacing.xxs))
        if (queue.isEmpty()) {
            PlaybackQueueEmptyState("队列为空", Modifier.weight(1f))
        } else {
            DragReorderList(
                items = queue,
                identity = ::songKeyOf,
                onMove = onMoveItem,
                modifier = Modifier.fillMaxWidth().weight(1f),
                activeIndex = currentIndex,
            ) { index, song, isActive, rowModifier, dragHandle ->
                SongRow(
                    song = song,
                    active = isActive,
                    subtitle = if (isActive) "正在播放 · ${song.artist}" else song.artist,
                    onClick = { onItemClick(index) },
                    onDelete = { onRemoveItem(index) }.takeUnless { isActive },
                    favorited = isFavorite(song),
                    modifier = rowModifier,
                    onToggleFavorite = onToggleFavorite.takeIf { song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank() }
                        ?.let { callback -> { callback(song) } },
                    dragHandle = dragHandle,
                )
            }
        }
    }
}

/** 最近播放和本地歌曲只是队列来源，点中后会用该来源的顺序替换当前列表。 */
@Composable
private fun PlaybackSourceList(
    songs: List<Song>,
    emptyText: String,
    onItemClick: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 说明区域无论有没有歌曲都保留，避免空态切到有内容时正文又向下移动一次。
        Text(
            "点一首后，将按这个列表的顺序继续播放",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = TaotaoSpacing.xxs),
        )
        if (songs.isEmpty()) {
            PlaybackQueueEmptyState(emptyText, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(songs) { index, item ->
                    SongRow(
                        song = item,
                        downloaded = item.audioUri?.startsWith("file:") == true,
                        onClick = { onItemClick(index) },
                        onPlayNext = { onPlayNext(item) },
                        favorited = isFavorite(item),
                        onToggleFavorite = onToggleFavorite.takeIf { item.remoteId?.let { it > 0L } == true || !item.mid.isNullOrBlank() }
                            ?.let { callback -> { callback(item) } },
                    )
                }
            }
        }
    }
}

/** 固定正文视口中的空态：信息在剩余空间居中，不影响 BottomSheet 的整体高度。 */
@Composable
private fun PlaybackQueueEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun repeatModeTitle(mode: Int): String = when (mode) {
    androidx.media3.common.Player.REPEAT_MODE_ALL -> "列表循环"
    androidx.media3.common.Player.REPEAT_MODE_ONE -> "单曲循环"
    else -> "顺序播放"
}

private fun repeatModeDescription(mode: Int): String = when (mode) {
    androidx.media3.common.Player.REPEAT_MODE_ALL -> "播完最后一首后从第一首继续"
    androidx.media3.common.Player.REPEAT_MODE_ONE -> "当前歌曲会一直重复播放"
    else -> "按列表顺序播放，最后一首播完即停"
}

internal fun formatTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
