package com.taotao.music.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.OfflinePin
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.model.Song

/**
 * 全局复用的视觉组件。
 *
 * 歌曲列表项、专辑封面、迷你播放器等原本在 TaotaoMusicApp.kt 和 SearchPage.kt 各有一份，
 * 造成重复定义（Kotlin 报 conflicting overloads）。统一收在这里，页面只负责组装。
 */

/**
 * 专辑封面：有图用图，没图退回带音符的色块。
 *
 * 图片加淡入：不加的话图片是"啪"一下出现的，列表滚动时一片闪烁。
 */
@Composable
fun AlbumArt(color: Color, size: Dp, iconSize: TextUnit, imageUri: String? = null) {
    val shape = if (size > 80.dp) CircleShape else RoundedCornerShape(12.dp)
    if (!imageUri.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageUri)
                .crossfade(AnimationDurations.FADE)
                .build(),
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
 *
 * [downloaded] 为真时加一个标记：搜索结果里看得见"这首已经下过了"，
 * 才不会重复下载或以为在走流量。
 */
@Composable
fun SongListItem(
    song: Song,
    active: Boolean,
    favorited: Boolean = false,
    downloaded: Boolean = false,
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
                if (downloaded) {
                    Icon(
                        Icons.Default.OfflinePin,
                        "已下载",
                        tint = TaotaoCoral,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp),
                    )
                }
            }
            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        }
        Text(song.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        if (onToggleFavorite != null) {
            FavoriteButton(favorited = favorited, onClick = onToggleFavorite, size = 20.dp)
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

/**
 * 收藏按钮。
 *
 * 收藏是这个应用里反馈最需要即时的动作，所以保留图标的轻微缩放和颜色渐变。
 * 图标按状态直接切换，避免快速点按时两个图标叠在一起。
 */
@Composable
fun FavoriteButton(
    favorited: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 24.dp,
) {
    // 收藏是高频操作，使用低回弹弹簧提供反馈，不能留下明显拖尾。
    val scale by animateFloatAsState(
        targetValue = if (favorited) 1.08f else 1f,
        animationSpec = taotaoSpring(dampingRatio = 0.85f),
        label = "收藏缩放",
    )
    val tint by animateColorAsState(
        targetValue = if (favorited) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = taotaoTween(AnimationDurations.MICRO),
        label = "收藏着色",
    )
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(size + 16.dp)) {
        Icon(
            if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (favorited) "取消收藏" else "收藏",
            tint = tint,
            modifier = Modifier.size(size).scale(scale),
        )
    }
}

/** 播放 / 暂停按钮：高频控制即时切换，按压波纹提供点按反馈。 */
@Composable
fun PlayPauseIcon(isPlaying: Boolean, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    Icon(
        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
        if (isPlaying) "暂停" else "播放",
        modifier = modifier,
        tint = tint,
    )
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
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, "上一首", tint = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onTogglePlaying) {
            PlayPauseIcon(isPlaying, tint = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, "下一首", tint = MaterialTheme.colorScheme.onSurface)
        }
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

/** 分页指示器：几页就几个点，当前页用主色实心。切换时尺寸与颜色都渐变。 */
@Composable
fun PagerDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            val selected = index == current
            val size by animateDpAsState(
                targetValue = if (selected) 8.dp else 6.dp,
                animationSpec = taotaoSpring(),
                label = "分页点尺寸",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    TaotaoCoral
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
                animationSpec = taotaoTween(AnimationDurations.MICRO),
                label = "分页点着色",
            )
            Box(Modifier.size(size).clip(CircleShape).background(color))
        }
    }
}
