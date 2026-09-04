package com.taotao.music.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val shape = if (size > 80.dp) CircleShape else RoundedCornerShape(24.dp)
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
 * 统一歌曲行。
 *
 * 搜索、收藏、本地、历史记录和播放队列都使用这个骨架，避免封面大小、行高、标题层级和
 * 选中颜色在不同页面逐渐分叉。右侧固定保留时长和更多菜单；各页面只注入可用的歌曲操作。
 */
@Composable
fun SongRow(
    song: Song,
    active: Boolean = false,
    downloaded: Boolean = false,
    subtitle: String = song.artist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    favorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    /** 将歌曲保存到云端歌单；为空时不显示该菜单项。 */
    onAddToPlaylist: (() -> Unit)? = null,
    /** 由平台层生成短链并打开系统分享面板。 */
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    dragHandle: (@Composable (Modifier) -> Unit)? = null,
) {
    var showActions by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = taotaoTween(AnimationDurations.MICRO),
        label = "歌曲行背景",
    )
    val titleColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = taotaoTween(AnimationDurations.MICRO),
        label = "歌曲行标题",
    )
    // 服务端个别歌曲元数据曾携带换行/空白字符；列表项必须保持紧凑，不能由一条脏数据撑开整行。
    val displayTitle = song.title.replace(Regex("\\s+"), " ").trim()
    val displaySubtitle = subtitle.replace(Regex("\\s+"), " ").trim()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(Color(song.color), 48.dp, 24.sp, song.coverUri)
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayTitle,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displaySubtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (downloaded) {
                    Icon(
                        Icons.Default.OfflinePin,
                        "已下载",
                        tint = TaotaoCoral,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp),
                    )
                }
                if (song.vip) VipBadge(Modifier.padding(start = 6.dp))
            }
        }
        Text(song.duration, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        if (dragHandle != null) {
            dragHandle(Modifier.size(36.dp))
        } else Box {
            IconButton(onClick = { showActions = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, "更多操作", tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { showActions = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                    )
                }
                if (onPlayNext != null) {
                    DropdownMenuItem(
                        text = { Text("下一首播放") },
                        onClick = { showActions = false; onPlayNext() },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                    )
                }
                if (onToggleFavorite != null) {
                    DropdownMenuItem(
                        text = { Text(if (favorited) "取消收藏" else "收藏") },
                        onClick = { showActions = false; onToggleFavorite() },
                        leadingIcon = {
                            Icon(if (favorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                        },
                    )
                }
                if (onAddToPlaylist != null) {
                    DropdownMenuItem(
                        text = { Text("加入歌单") },
                        onClick = { showActions = false; onAddToPlaylist() },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                    )
                }
                if (onShare != null) {
                    DropdownMenuItem(
                        text = { Text("分享歌曲") },
                        onClick = { showActions = false; onShare() },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                    )
                }
                if (
                    onDelete == null && onPlayNext == null && onToggleFavorite == null &&
                    onAddToPlaylist == null && onShare == null
                ) {
                    DropdownMenuItem(
                        text = { Text("暂无可用操作") },
                        onClick = { showActions = false },
                        enabled = false,
                    )
                }
            }
        }
    }
}

/** 搜索与媒体库列表的兼容封装：收藏、删除等特有操作只在这里组合。 */
@Composable
fun SongListItem(
    song: Song,
    active: Boolean,
    favorited: Boolean = false,
    downloaded: Boolean = false,
    modifier: Modifier = Modifier,
    onToggleFavorite: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    SongRow(
        song = song,
        active = active,
        downloaded = downloaded,
        modifier = modifier,
        onClick = onClick,
        favorited = favorited,
        onToggleFavorite = onToggleFavorite,
        onPlayNext = onPlayNext,
        onAddToPlaylist = onAddToPlaylist,
        onShare = onShare,
        onDelete = onDelete,
    )
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
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(
        targetValue = if (favorited && !reduceMotion) 1.08f else 1f,
        animationSpec = if (reduceMotion) snap() else taotaoSpring(dampingRatio = 0.85f),
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(10.dp),
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

/** 分页指示器：几页就几个点，当前页用主色实心。选中用缩放而不是改布局尺寸。 */
@Composable
fun PagerDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            val selected = index == current
            val scale by animateFloatAsState(
                targetValue = if (selected) 8f / 6f else 1f,
                animationSpec = if (reduceMotion) snap() else taotaoSpring(),
                label = "分页点缩放",
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
            Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}







