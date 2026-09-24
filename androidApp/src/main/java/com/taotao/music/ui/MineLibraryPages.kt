package com.taotao.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.taotao.music.data.PlaybackHistoryEntry
import com.taotao.music.model.Song
import com.taotao.music.playerui.SharedBackButton
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoSpacing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 「我的」页下的三个独立音乐空间。 */
enum class MineLibrarySection { FAVORITES, HISTORY, LOCAL, PLAYLISTS }

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
    onAddToPlaylist: ((Song) -> Unit)? = null,
    onDelete: ((Song) -> Unit)? = null,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
        SharedSectionHeader(
            title = title,
            subtitle = subtitle,
            level = SharedSectionLevel.PAGE,
            leading = { SharedBackButton(onBack) },
        )
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
                    modifier = Modifier.padding(top = TaotaoSpacing.md),
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
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
            ) {
                itemsIndexed(songs, key = { _, song -> librarySongKey(song) }) { index, song ->
                    SongListItem(
                        song = song,
                        active = false,
                        favorited = isFavorite?.invoke(song) == true,
                        downloaded = song.audioUri?.startsWith("file:") == true,
                        onToggleFavorite = onToggleFavorite?.let { callback -> { callback(song) } },
                        onPlayNext = onPlayNext?.let { callback -> { callback(song) } },
                        onAddToPlaylist = onAddToPlaylist
                            ?.takeIf { song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank() }
                            ?.let { callback -> { callback(song) } },
                        onDelete = onDelete?.let { callback -> { callback(song) } },
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                        onClick = { onSongClick(index) },
                    )
                }
                item { Spacer(Modifier.height(TaotaoSpacing.md)) }
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
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
        val clearAction: (@Composable () -> Unit)? = if (history.isEmpty()) {
            null
        } else {
            { TextButton(onClick = onClear) { Text("清空") } }
        }
        SharedSectionHeader(
            title = "最近播放",
            subtitle = if (history.isEmpty()) "还没有听过歌曲" else "共 ${history.size} 首 · 最近播放优先",
            level = SharedSectionLevel.PAGE,
            leading = { SharedBackButton(onBack) },
            trailing = clearAction,
        )
        if (history.isEmpty()) {
            LibraryEmptyState(
                title = "还没有播放记录",
                description = "开始播放歌曲后会自动出现在这里",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
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
                        onToggleFavorite = onToggleFavorite.takeIf { entry.song.remoteId?.let { it > 0L } == true || !entry.song.mid.isNullOrBlank() }
                            ?.let { callback -> { callback(entry.song) } },
                    )
                }
                item { Spacer(Modifier.height(TaotaoSpacing.md)) }
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

private fun librarySongKey(song: Song): String {
    val source = song.source.ifBlank { "tencent" }
    val identity = song.remoteId?.takeIf { it > 0L }?.toString()
        ?: song.mid?.trim()?.takeIf { it.isNotBlank() }
        ?: "local:${song.audioUri.orEmpty()}#${song.title}#${song.artist}"
    return "$source:$identity"
}

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



