package com.taotao.music.ui.common

import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoCoral
import com.taotao.music.ui.theme.taotaoSpring
import com.taotao.music.ui.theme.taotaoTween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.model.Song
import com.taotao.music.playerui.SharedContentState
import com.taotao.music.playerui.SharedContentStateType
import com.taotao.music.playerui.SharedSongRow
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoElevation
import com.taotao.music.playerui.theme.taotaoShadowColors
import com.taotao.music.playerui.theme.TaotaoSpacing

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
 *
 * ## 尺寸只从 [TaotaoSizes] 取
 *
 * 引入 token 之前，这个组件在四个调用点用了 **42 / 48 / 52 dp** 表示同一个语义角色
 * （列表行封面），另有 88 / 112 dp 两档，占位音符也跟着各写一个字号。
 * 现在尺寸收敛为 [TaotaoSizes.artworkRow] / [artworkGrid][TaotaoSizes.artworkGrid] /
 * [artworkHero][TaotaoSizes.artworkHero] 三档，音符按封面等比推算。
 *
 * 形状按尺寸自动判定：大于一个列表行封面就当作大封面（圆形），否则用
 * [TaotaoShapes.artwork] 圆角矩形。调用点需要覆盖时显式传 [shape]
 * （例如 [SongRow] 要求封面与行内其它元素对齐）。
 */
@Composable
fun AlbumArt(
    color: Color,
    size: Dp,
    imageUri: String? = null,
    shape: Shape = if (size > TaotaoSizes.artworkRow) CircleShape else TaotaoShapes.artwork,
    /** 占位音符的字号。留空则按封面尺寸的一半推算，超大封面可显式覆盖。 */
    iconSize: TextUnit? = null,
) {
    // 音符随封面等比缩放，不再让每个调用点各写一个字号。
    val glyph = iconSize ?: with(LocalDensity.current) { (size * 0.5f).toSp() }
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
            Text("♫", color = Color.White, fontSize = glyph)
        }
    }
}

/**
 * 统一歌曲行。
 *
 * 搜索、收藏、本地、历史记录和播放队列都使用这个骨架，避免封面大小、行高、标题层级和
 * 选中颜色在不同页面逐渐分叉。右侧固定保留时长、可选的拖动拖把和更多菜单；
 * 各页面只注入可用的歌曲操作。
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
    /** 打开这首歌的单曲倒带日记；为空时不显示该菜单项。 */
    onOpenDiary: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    dragHandle: (@Composable (Modifier) -> Unit)? = null,
) {
    var showActions by remember { mutableStateOf(false) }
    // 拖把与更多菜单并存：此前传了拖把就隐藏整个菜单，播放队列里删除、收藏的回调
    // 传了却没有任何入口。两个都渲染；确实没有可用操作时才只显示拖把或什么都不显示。
    val hasRowActions = onDelete != null || onPlayNext != null || onToggleFavorite != null ||
        onAddToPlaylist != null || onShare != null || onOpenDiary != null
    SharedSongRow(
        song = song,
        active = active,
        downloaded = downloaded,
        subtitle = subtitle,
        artworkContent = {
            AlbumArt(
                color = Color(song.color),
                size = TaotaoSizes.artworkRow,
                imageUri = song.coverUri,
                shape = TaotaoShapes.button,
            )
        },
        onClick = onClick,
        modifier = modifier,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dragHandle != null) {
                    dragHandle(Modifier.size(TaotaoSizes.iconButton))
                }
                if (hasRowActions) {
                    Box {
                        IconButton(onClick = { showActions = true }, modifier = Modifier.size(TaotaoSizes.iconButton)) {
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
                            if (onOpenDiary != null) {
                                DropdownMenuItem(
                                    text = { Text("查看单曲日记") },
                                    onClick = { showActions = false; onOpenDiary() },
                                    leadingIcon = { Icon(Icons.Default.AutoStories, null) },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
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
    onOpenDiary: (() -> Unit)? = null,
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
        onOpenDiary = onOpenDiary,
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
    size: Dp = TaotaoSizes.iconMd,
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
    // 图标外扩 16dp 作为触达余量，保证不小于 Material 建议的最小点击区。
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(size + TaotaoSpacing.md)) {
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

/** VIP / 付费标记。容器色成对取用，避免透明度叠加在亮色白底上淡到看不见。 */
@Composable
fun VipBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(TaotaoShapes.badge)
            .background(MaterialTheme.colorScheme.primaryContainer)
            // 纵向只留 1dp：徽标高度应当由行高决定，再撑开就会把整行顶高。
            .padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.tightVertical),
    ) {
        Text("VIP", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
    Row(Modifier.fillMaxWidth().padding(bottom = TaotaoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
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

/**
 * 骨架屏占位条的尺寸。
 *
 * 刻意不进 [TaotaoSizes]：骨架只表达"这里将出现一行"，它跟随真实行的尺寸，
 * 而不是反过来定义尺寸刻度。放进刻度反而会让「48dp 到底是封面还是占位块」失去答案。
 */
private val SkeletonTitleWidth = 150.dp
private val SkeletonTitleHeight = 16.dp
private val SkeletonSubtitleWidth = 90.dp
private val SkeletonSubtitleHeight = 12.dp

/** 搜索中的骨架屏占位。 */
@Composable
fun SearchSkeletonList() {
    Column(
        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
        modifier = Modifier.padding(top = TaotaoSpacing.md),
    ) {
        repeat(6) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 圆角与尺寸都跟真实歌曲行一致，避免加载完成时方块跳一下。
                Box(
                    Modifier.size(TaotaoSizes.artworkRow).clip(TaotaoShapes.button)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.padding(start = TaotaoSpacing.sm)) {
                    Box(
                        Modifier.width(SkeletonTitleWidth).height(SkeletonTitleHeight)
                            .clip(TaotaoShapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(TaotaoSpacing.xs))
                    Box(
                        Modifier.width(SkeletonSubtitleWidth).height(SkeletonSubtitleHeight)
                            .clip(TaotaoShapes.extraSmall).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

/** 统一的空状态展示。 */
@Composable
fun EmptyStateView(title: String = "暂无数据", description: String? = null, modifier: Modifier = Modifier) {
    SharedContentState(
        type = SharedContentStateType.EMPTY,
        title = title,
        description = description,
        modifier = modifier,
    )
}

/**
 * 分页指示器的圆点尺寸。
 *
 * 选中态外径比常态大 2dp，靠这个差值制造「选中被放大」的观感；
 * 放大倍数由两者相除得出，改尺寸时不必再去同步一个写死的 `8f / 6f`。
 */
private val PagerDotActiveSize = 8.dp
private val PagerDotIdleSize = 6.dp

/** 分页指示器：几页就几个点，当前页用主色实心。选中用缩放而不是改布局尺寸。 */
@Composable
fun PagerDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { index ->
            val selected = index == current
            val scale by animateFloatAsState(
                targetValue = if (selected) PagerDotActiveSize.value / PagerDotIdleSize.value else 1f,
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
            Box(Modifier.size(PagerDotActiveSize), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(PagerDotIdleSize)
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

/**
 * 全局提示条：贴底居中的胶囊。
 *
 * 不再走默认 Snackbar 的宽扁灰条 —— 那是观感上「像系统控件」的根源。
 * 这里统一改成胶囊形 + `inverseSurface` 深浅反色底 + 投影：亮色主题下是深底白字，
 * 暗色主题自动反转成浅底深字，下载、收藏、定时等所有反馈共用这一个实现。
 */
@Composable
fun TaotaoSnackbar(snackbarData: SnackbarData, modifier: Modifier = Modifier) {
    val shadow = taotaoShadowColors()
    Snackbar(
        snackbarData = snackbarData,
        modifier = modifier.shadow(
            elevation = TaotaoElevation.raised,
            shape = TaotaoShapes.pill,
            ambientColor = shadow.ambient,
            spotColor = shadow.spot,
        ),
        shape = TaotaoShapes.pill,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        actionColor = MaterialTheme.colorScheme.inversePrimary,
    )
}
