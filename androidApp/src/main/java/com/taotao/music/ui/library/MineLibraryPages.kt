package com.taotao.music.ui.library

import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.EmptyStateView
import com.taotao.music.ui.common.SongListItem
import com.taotao.music.ui.common.SongRow
import com.taotao.music.ui.theme.AnimationCurves
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoCoral
import com.taotao.music.ui.theme.taotaoTween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import com.taotao.music.data.TencentMusicApi.SongDiary
import com.taotao.music.data.TencentMusicApi.SongDiaryRecord
import com.taotao.music.model.Song
import com.taotao.music.playerui.SharedBackButton
import com.taotao.music.playerui.SharedCard
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoSpacing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taotao.music.data.TencentMusicApi.SongDiaryYear
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes

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
    onOpenDiary: ((Song) -> Unit)? = null,
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
                        onOpenDiary = onOpenDiary?.let { callback -> { callback(song) } },
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
    onOpenDiary: ((Song) -> Unit)? = null,
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
                        onOpenDiary = onOpenDiary?.let { callback -> { callback(entry.song) } },
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

/**
 * 单曲倒带日记：针对当前用户的某一首歌，展示首次邂逅、上次收听、狂热循环、
 * 近半年/近一年播放与历史播放记录。数据由后端现算，页面只负责呈现。
 */
@Composable
fun SongDiaryPage(
    song: Song,
    diary: SongDiary?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onShare: (() -> Unit)? = null,
    onOpenRecords: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        // 顶部一层珊瑚色渐变，只铺满头部区域再向下淡出，营造「回顾」的氛围。
        // 页面由宿主放开顶部内边距，渐变能画到状态栏底下实现沉浸式。
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(TaotaoCoral.copy(alpha = 0.22f), Color.Transparent))),
        )
        Column(Modifier.fillMaxSize()) {
            DiaryTopBar(title = "单曲倒带日记", onBack = onBack, onShare = onShare)
            when {
                loading && diary == null -> DiaryStatus("正在翻阅这首歌的倒带日记", loading = true)
                diary == null -> DiaryStatus(
                    text = error ?: "播放并听满几秒后，这里会出现你与它的故事",
                    onRetry = if (error != null) onRetry else null,
                )
                else -> SongDiaryContent(
                    song = song,
                    diary = diary,
                    onOpenRecords = onOpenRecords,
                )
            }
        }
    }
}

@Composable
private fun SongDiaryContent(
    song: Song,
    diary: SongDiary,
    onOpenRecords: (() -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.md),
    ) {
        item { DiaryHeader(song = song, recordCount = diary.records.size, onOpenRecords = onOpenRecords) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.md)) {
                DiaryMetricCard(
                    label = "上次收听",
                    value = diary.lastPlayedAtMillis?.let { formatClock(it) } ?: "—",
                    caption = diary.lastPlayedAtMillis?.let { formatFullDate(it) } ?: "还没听过",
                    modifier = Modifier.weight(1f),
                )
                DiaryMetricCard(
                    label = "狂热循环",
                    value = if (diary.peakDayCount > 0) formatMonth(diary.peakDayAtMillis) else "—",
                    caption = if (diary.peakDayCount > 0) "那天循环了 ${diary.peakDayCount} 次" else "还没有集中循环",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { DiaryFirstEncounterCard(diary) }
        item { DiaryYearlyCard(diary) }
        item { DiaryHalfYearCard(diary) }
        item { Spacer(Modifier.height(TaotaoSpacing.md)) }
    }
}

/**
 * 日记的「播放记录」下级独立页：逐次列出这首歌被收听的会话。
 * 与日记页共用珊瑚渐变和沉浸式顶栏，由宿主放开顶部内边距。
 */
@Composable
fun DiaryRecordsPage(
    song: Song,
    records: List<SongDiaryRecord>,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(TaotaoCoral.copy(alpha = 0.18f), Color.Transparent))),
        )
        Column(Modifier.fillMaxSize()) {
            DiaryTopBar(title = "播放记录", onBack = onBack, onShare = null)
            if (records.isEmpty()) {
                LibraryEmptyState(
                    title = "还没有播放记录",
                    description = "听满几秒后，这里会记录每一次收听",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal),
                    contentPadding = PaddingValues(top = TaotaoSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.md),
                ) {
                    item {
                        Column {
                            Text(
                                song.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = TaotaoCoral,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "这首歌被翻开过 ${records.size} 次",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                            )
                        }
                    }
                    itemsIndexed(records, key = { index, _ -> "diary-record-$index" }) { _, record ->
                        DiaryRecordRow(record)
                    }
                    item { Spacer(Modifier.height(TaotaoSpacing.md)) }
                }
            }
        }
    }
}

/** 顶部栏：返回、居中标题、分享；内容用 statusBarsPadding 让开状态栏，背景照常沉浸。 */
@Composable
private fun DiaryTopBar(
    title: String,
    onBack: () -> Unit,
    onShare: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.xs),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
        if (onShare != null) {
            IconButton(onClick = onShare, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Default.IosShare, "分享", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** 加载 / 空 / 失败态的居中占位。 */
@Composable
private fun ColumnScope.DiaryStatus(text: String, loading: Boolean = false, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator(color = TaotaoCoral)
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = TaotaoSpacing.md, start = TaotaoSpacing.xl, end = TaotaoSpacing.xl),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = TaotaoSpacing.xs)) { Text("重新加载") }
        }
    }
}

/** 头部：封面 + 珊瑚色标题 / 歌手 + 「播放记录」胶囊按钮（点开进入下级独立页）。 */
@Composable
private fun DiaryHeader(song: Song, recordCount: Int, onOpenRecords: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xs, bottom = TaotaoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            color = Color(song.color),
            size = TaotaoSizes.artworkGrid,
            imageUri = song.coverUri,
            shape = TaotaoShapes.artwork,
        )
        Column(Modifier.padding(start = TaotaoSpacing.md).weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleLarge,
                color = TaotaoCoral,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = TaotaoCoral.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TaotaoSpacing.xxs),
            )
            if (onOpenRecords != null) {
                Box(
                    modifier = Modifier
                        .padding(top = TaotaoSpacing.sm)
                        .clip(RoundedCornerShape(percent = 50))
                        .border(1.dp, TaotaoCoral, RoundedCornerShape(percent = 50))
                        .clickable(onClick = onOpenRecords)
                        .padding(horizontal = TaotaoSpacing.md, vertical = TaotaoSpacing.xs),
                ) {
                    Text(
                        if (recordCount > 0) "播放记录 · $recordCount 次" else "播放记录",
                        style = MaterialTheme.typography.labelLarge,
                        color = TaotaoCoral,
                    )
                }
            }
        }
    }
}

/** 通用小卡：标签 + 珊瑚色大数值 + 灰色说明。数值用 titleLarge 保证「2026年12月」这类
 *  长时间串在半宽卡片里不被省略号截断。 */
@Composable
private fun DiaryMetricCard(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    SharedCard(modifier = modifier) {
        Column(Modifier.padding(TaotaoSpacing.md)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = TaotaoCoral,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TaotaoSpacing.sm),
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TaotaoSpacing.xs),
            )
        }
    }
}

/** 首次邂逅：诗意短语 + 精确时间，右上角一弯月，底部播放来源与相遇天数。 */
@Composable
private fun DiaryFirstEncounterCard(diary: SongDiary) {
    val first = diary.firstPlayedAtMillis
    SharedCard {
        Box(Modifier.fillMaxWidth().padding(TaotaoSpacing.md)) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = null,
                tint = TaotaoCoral.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).size(TaotaoSizes.artworkRow),
            )
            Column {
                Text("首次邂逅", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (first != null) firstEncounterPhrase(first) else "尚未开始",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TaotaoCoral,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = TaotaoSpacing.sm),
                )
                if (first != null) {
                    Text(
                        formatDateTime(first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = TaotaoSpacing.xs),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("播放来源 Android手机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (first != null) {
                        Text("相遇天数 ${daysSince(first)}天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 近年播放：左侧大数字，右侧逐年折线。 */
@Composable
private fun DiaryYearlyCard(diary: SongDiary) {
    SharedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TaotaoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(0.42f)) {
                Text("近年播放", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${diary.playsLastYear}次",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TaotaoCoral,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = TaotaoSpacing.sm),
                )
                Text(
                    "曾一起看过几次日落",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = TaotaoSpacing.xs),
                )
            }
            DiaryYearlyChart(
                diary.yearly,
                Modifier.weight(0.58f).height(112.dp).padding(start = TaotaoSpacing.sm),
            )
        }
    }
}

/** 逐年折线：最后一个点高亮，年份标注在底部。图表几何按像素布局，故直接用 dp。 */
@Composable
private fun DiaryYearlyChart(yearly: List<SongDiaryYear>, modifier: Modifier = Modifier) {
    if (yearly.isEmpty()) return
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val maxCount = yearly.maxOf { it.count }.coerceAtLeast(1)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = TaotaoSpacing.xs)) {
            val n = yearly.size
            val stepX = if (n > 1) size.width / (n - 1) else 0f
            val topPad = 10.dp.toPx()
            val usableH = (size.height - topPad).coerceAtLeast(1f)
            fun point(i: Int): Offset {
                val x = if (n > 1) stepX * i else size.width / 2f
                val y = topPad + usableH * (1f - yearly[i].count / maxCount.toFloat())
                return Offset(x, y)
            }
            for (i in 0 until n - 1) {
                drawLine(TaotaoCoral, point(i), point(i + 1), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            for (i in 0 until n) {
                drawCircle(onVariant.copy(alpha = 0.4f), radius = 2.5f.dp.toPx(), center = point(i))
            }
            val last = point(n - 1)
            drawCircle(TaotaoCoral.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = last)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = last)
            drawCircle(TaotaoCoral, radius = 3.dp.toPx(), center = last)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            yearly.forEachIndexed { i, year ->
                Text(
                    year.year.toString().takeLast(4),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == yearly.lastIndex) TaotaoCoral else onVariant,
                    fontWeight = if (i == yearly.lastIndex) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 近半年播放：左侧大数字，右侧逐日点阵热力图。 */
@Composable
private fun DiaryHalfYearCard(diary: SongDiary) {
    SharedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TaotaoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(0.42f)) {
                Text("近半年播放", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${diary.playsLastHalfYear}次",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TaotaoCoral,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = TaotaoSpacing.sm),
                )
                Text(
                    "总有那么多次惊奇际遇",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = TaotaoSpacing.xs),
                )
            }
            DiaryHeatmap(
                diary.dailyCounts,
                Modifier.weight(0.58f).height(112.dp).padding(start = TaotaoSpacing.sm),
            )
        }
    }
}

/** 逐日点阵：每个圆点是一天，播放过的天染成珊瑚色，深浅随当天次数。 */
@Composable
private fun DiaryHeatmap(daily: List<Int>, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    Canvas(modifier) {
        if (daily.isEmpty()) return@Canvas
        val columns = 15
        val rows = kotlin.math.ceil(daily.size / columns.toFloat()).toInt().coerceAtLeast(1)
        val cellW = size.width / columns
        val cellH = size.height / rows
        val radius = (minOf(cellW, cellH) / 2f) * 0.6f
        val maxCount = (daily.maxOrNull() ?: 0).coerceAtLeast(1)
        daily.forEachIndexed { index, count ->
            val col = index % columns
            val row = index / columns
            val center = Offset(cellW * col + cellW / 2f, cellH * row + cellH / 2f)
            val color = if (count <= 0) {
                base
            } else {
                TaotaoCoral.copy(alpha = (0.45f + 0.55f * (count / maxCount.toFloat())).coerceIn(0.45f, 1f))
            }
            drawCircle(color, radius = radius, center = center)
        }
    }
}

private fun formatClock(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))

private fun formatMonth(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "—"
    return SimpleDateFormat("yyyy年M月", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
}

private fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))

/** 相遇天数：首次播放距今的天数。 */
private fun daysSince(timestamp: Long): Int =
    ((System.currentTimeMillis() - timestamp) / 86_400_000L).toInt().coerceAtLeast(0)

/** 把首次播放的时刻写成「季节 + 时段」的诗意短语，例如「仲夏的深夜」。 */
private fun firstEncounterPhrase(timestamp: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val season = when (calendar.get(Calendar.MONTH) + 1) {
        3, 4, 5 -> "暖春"
        6, 7, 8 -> "仲夏"
        9, 10, 11 -> "深秋"
        else -> "寒冬"
    }
    val part = when (calendar.get(Calendar.HOUR_OF_DAY)) {
        in 5..8 -> "清晨"
        in 9..11 -> "上午"
        in 12..13 -> "正午"
        in 14..17 -> "午后"
        in 18..20 -> "傍晚"
        else -> "深夜"
    }
    return "${season}的${part}"
}

@Composable
private fun DiaryRecordRow(record: SongDiaryRecord) {
    SharedCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TaotaoSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatHistoryTime(record.startedAtMillis),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                buildString {
                    append(formatListenedDuration(record.listenedMs))
                    append(if (record.completed) " · 完整听完" else " · 试听")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatFullDate(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "—"
    return SimpleDateFormat("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE).format(Date(timestamp))
}

private fun formatListenedDuration(ms: Long): String {
    if (ms <= 0L) return "不到 1 分钟"
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L -> "$hours 小时 $minutes 分钟"
        totalMinutes > 0L -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
}






