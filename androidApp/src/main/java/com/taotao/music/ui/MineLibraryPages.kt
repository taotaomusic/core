package com.taotao.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.data.PlaybackHistoryEntry
import com.taotao.music.model.Song
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 「我的」页下的三个独立音乐空间。 */
enum class MineLibrarySection { FAVORITES, HISTORY, LOCAL }

/** 收藏夹与本地歌曲共用的歌曲页，差异只通过明确的回调注入。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicLibraryPage(
    title: String,
    subtitle: String,
    songs: List<Song>,
    emptyTitle: String,
    emptyDescription: String,
    onBack: () -> Unit,
    onSongClick: (Int) -> Unit,
    isFavorite: ((Song) -> Boolean)? = null,
    onToggleFavorite: ((Song) -> Unit)? = null,
    onPlayNext: ((Song) -> Unit)? = null,
    onDelete: ((Song) -> Unit)? = null,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        LibraryPageHeader(title = title, subtitle = subtitle, onBack = onBack)
        if (loading && songs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = TaotaoCoral)
                Text(
                    "正在读取账号收藏",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        } else if (songs.isEmpty()) {
            LibraryEmptyState(
                title = if (error == null) emptyTitle else "收藏列表加载失败",
                description = error ?: emptyDescription,
                modifier = Modifier.weight(1f),
            )
            if (error != null && onRetry != null) {
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("重新加载")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(songs, key = { _, song -> librarySongKey(song) }) { index, song ->
                    SongListItem(
                        song = song,
                        active = false,
                        favorited = isFavorite?.invoke(song) == true,
                        downloaded = song.audioUri?.startsWith("file:") == true,
                        onToggleFavorite = onToggleFavorite?.let { callback -> { callback(song) } },
                        onPlayNext = onPlayNext?.let { callback -> { callback(song) } },
                        onDelete = onDelete?.let { callback -> { callback(song) } },
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                        onClick = { onSongClick(index) },
                    )
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

/** 最近播放独立页：记录按最近时间排列，保留时间信息并可一键清空。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackHistoryPage(
    history: List<PlaybackHistoryEntry>,
    onBack: () -> Unit,
    onSongClick: (Int) -> Unit,
    onPlayNext: (Song) -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        LibraryPageHeader(
            title = "最近播放",
            subtitle = if (history.isEmpty()) "还没有听过歌曲" else "共 ${history.size} 首 · 最近播放优先",
            onBack = onBack,
            action = if (history.isEmpty()) null else {
                { TextButton(onClick = onClear) { Text("清空") } }
            },
        )
        if (history.isEmpty()) {
            LibraryEmptyState(
                title = "还没有播放记录",
                description = "开始播放歌曲后会自动出现在这里",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(history, key = { _, entry -> librarySongKey(entry.song) }) { index, entry ->
                    SongRow(
                        song = entry.song,
                        subtitle = buildString {
                            append(entry.song.artist).append(" · ").append(formatHistoryTime(entry.playedAtMillis))
                            if (entry.playCount > 0) append(" · 播放 ").append(entry.playCount).append(" 次")
                        },
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                        onClick = { onSongClick(index) },
                        onPlayNext = { onPlayNext(entry.song) },
                        favorited = isFavorite(entry.song),
                        onToggleFavorite = onToggleFavorite.takeIf { entry.song.remoteId != null }
                            ?.let { callback -> { callback(entry.song) } },
                    )
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(title: String, description: String?, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val offsetPx = 20
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = taotaoTween(AnimationDurations.FADE)) +
            if (reduceMotion) {
                EnterTransition.None
            } else {
                slideInVertically(
                    animationSpec = taotaoTween(AnimationDurations.FADE, easing = AnimationCurves.emphasizedIn),
                ) { offsetPx }
            },
        modifier = modifier,
    ) {
        EmptyStateView(title = title, description = description)
    }
}

@Composable
private fun LibraryPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        // 列表页的标题只承担导航和摘要；过大的上下留白会把第一首歌推得太远。
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        action?.invoke()
    }
}

private fun librarySongKey(song: Song): String = song.remoteId?.let { "remote:$it" }
    ?: "local:${song.audioUri.orEmpty()}#${song.title}#${song.artist}"

private fun formatHistoryTime(timestamp: Long): String {
    if (timestamp <= 0L) return "最近"
    val todayStart = (Calendar.getInstance().clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val pattern = when {
        timestamp >= todayStart -> "今天 HH:mm"
        timestamp >= todayStart - 24 * 60 * 60 * 1000L -> "昨天 HH:mm"
        else -> "M月d日"
    }
    return SimpleDateFormat(pattern, Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
}













