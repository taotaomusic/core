package com.taotao.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
            EmptyStateView(
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
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(songs, key = { _, song -> librarySongKey(song) }) { index, song ->
                    SongListItem(
                        song = song,
                        active = false,
                        favorited = isFavorite?.invoke(song) == true,
                        downloaded = song.audioUri?.startsWith("file:") == true,
                        onToggleFavorite = onToggleFavorite?.let { callback -> { callback(song) } },
                        onDelete = onDelete?.let { callback -> { callback(song) } },
                        onClick = { onSongClick(index) },
                    )
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

/** 最近播放独立页：记录按最近时间排列，保留时间信息并可一键清空。 */
@Composable
fun PlaybackHistoryPage(
    history: List<PlaybackHistoryEntry>,
    onBack: () -> Unit,
    onSongClick: (Int) -> Unit,
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
            EmptyStateView(
                title = "还没有播放记录",
                description = "开始播放歌曲后会自动出现在这里",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(history, key = { _, entry -> librarySongKey(entry.song) }) { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { onSongClick(index) }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AlbumArt(Color(entry.song.color), 48.dp, 24.sp, entry.song.coverUri)
                        Column(Modifier.weight(1f).padding(start = 13.dp)) {
                            Text(entry.song.title, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                "${entry.song.artist} · ${formatHistoryTime(entry.playedAtMillis)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        Icon(Icons.Default.PlayArrow, "重新播放", tint = TaotaoCoral)
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
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
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp),
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
