package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.taotao.music.model.Song

/**
 * 全局复用的视觉组件。
 *
 * 歌曲列表项、专辑封面、迷你播放器等原本在 TaotaoMusicApp.kt 和 SearchPage.kt 各有一份，
 * 造成重复定义（Kotlin 报 conflicting overloads）。统一收在这里，页面只负责组装。
 */

/** 专辑封面：有图用图，没图退回带音符的色块。 */
@Composable
fun AlbumArt(color: Color, size: Dp, iconSize: TextUnit, imageUri: String? = null) {
    val shape = if (size > 80.dp) CircleShape else RoundedCornerShape(12.dp)
    if (!imageUri.isNullOrBlank()) {
        AsyncImage(
            model = imageUri,
            contentDescription = "专辑封面",
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(Modifier.size(size).clip(shape).background(color), contentAlignment = Alignment.Center) {
            Text("♫", color = Color.White, fontSize = iconSize)
        }
    }
}

/**
 * 歌曲列表项，搜索结果和本地音乐共用。
 *
 * [favorited] / [onToggleFavorite] / [onDelete] 都带默认值：不传即维持原来的样子
 * （末尾是那个纯装饰的更多图标）。本地文件没有服务端 ID 时本来就不能收藏。
 */
@Composable
fun SongListItem(
    song: Song,
    active: Boolean,
    favorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(Color(song.color), 48.dp, 24.sp, song.coverUri)
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.title,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 付费歌曲搜索结果里就有标记，不用等点开才发现放不出来。
                if (song.vip) VipBadge(Modifier.padding(start = 6.dp))
            }
            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        }
        Text(song.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (favorited) "取消收藏" else "收藏",
                    tint = if (favorited) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        if (onToggleFavorite == null && onDelete == null) {
            Icon(Icons.Default.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** VIP / 付费标记。 */
@Composable
fun VipBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(TaotaoCoral.copy(alpha = 0.14f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text("VIP", color = TaotaoCoral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** 底部迷你播放器。 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlaying: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(Color(song.color), 44.dp, 22.sp, song.coverUri)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "上一首") }
        IconButton(onClick = onTogglePlaying) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "播放") }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "下一首") }
    }
}

/**
 * 搜索栏，首页和搜索页共用。
 * [focusRequester] 由搜索页传入以便进入时自动聚焦，首页不需要则留空。
 */
@Composable
fun MusicSearchBar(
    keyword: String,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChanged,
            modifier = Modifier.weight(1f)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { if (it.isFocused) onFocus() },
            singleLine = true,
            placeholder = { Text("搜索歌曲或歌手", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Default.Search, "搜索") },
        )
        TextButton(onClick = onSearch) { Text("搜索", color = TaotaoCoral) }
    }
}

/** 搜索中的骨架屏占位。 */
@Composable
fun SearchSkeletonList() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 18.dp)) {
        repeat(6) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Column(Modifier.padding(start = 13.dp)) {
                    Box(Modifier.width(150.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

/** 页面标题区域：统一标题样式和间距。 */
@Composable
fun PageTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 16.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

/** 带标题的圆角卡片。 */
@Composable
fun CardWithTitle(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = TaotaoCoral,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = titleColor)
        }
        content()
    }
}

/** 统一的空状态展示。 */
@Composable
fun EmptyStateView(title: String = "暂无数据", description: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("♫", fontSize = 48.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

/** 分页指示器：几页就几个点，当前页用主色实心。 */
@Composable
fun PagerDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(if (index == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (index == current) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
            )
        }
    }
}
