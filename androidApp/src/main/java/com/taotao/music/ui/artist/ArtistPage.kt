package com.taotao.music.ui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.data.AlbumSearchResult
import com.taotao.music.data.ArtistDetail
import com.taotao.music.data.ArtistSearchResult
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.EmptyStateView
import com.taotao.music.ui.common.SongListItem
import com.taotao.music.ui.common.songKeyOf
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.TaotaoCoral

/**
 * 歌手主页：沉浸式头部（大圆头像、名字、别名、统计、可折叠简介）+
 * 「歌曲 / 专辑 / 相似」三个内嵌标签。
 *
 * 与搜索页共用 SongListItem / AlbumArt / 主题 token；本文件里的 internal 辅助
 * （顶栏、渐变背景、状态占位、折叠简介、歌曲区块）同时供专辑页
 * （`ui/album/AlbumPage.kt`）复用 —— 两页是同一类资料目录页，拆开会出现两份逐行雷同的实现。
 */

/**
 * 歌手页大头像的边长。
 *
 * 规格在 72–96dp 之间取 88dp：介于现有头像档（TaotaoSizes.avatar = 64dp）与
 * 网格封面档（TaotaoSizes.artworkGrid = 112dp）之间。刻意不进 TaotaoSizes ——
 * 这是歌手页的一次性视觉规格，按 HistoryChipMaxWidth 的先例做页面私有常量。
 */
private val ArtistHeroAvatarSize = 88.dp

/**
 * 目录页顶部珊瑚渐变的高度。
 *
 * 与单曲倒带日记页同量级：只铺头部区域再向下淡出，配合宿主放开顶部内边距实现沉浸式。
 */
private val CatalogBackdropHeight = 280.dp

/** 滚动到距列表底部多少个条目内开始预取下一页。与搜索页的预取窗口一致。 */
internal const val CatalogLoadMoreThreshold = 6

/** 统计数字的缩写基数：超过 1 万按「4.8 万」缩写，超过 1 亿按「1.2 亿」。 */
private const val CatalogCountWanThreshold = 10_000L
private const val CatalogCountYiThreshold = 100_000_000L

/** 歌手页的内嵌标签。纯界面状态，不进状态层。 */
private enum class ArtistPageTab(val label: String) {
    SONGS("歌曲"),
    ALBUMS("专辑"),
    SIMILAR("相似"),
}

/**
 * 歌手页装配。
 *
 * 数据与操作全部来自 [com.taotao.music.ui.app.ArtistPageState]，由路由层注入；
 * [target] 是打开目标（详情到达前的头部兜底资料），页面自身不发起任何网络请求。
 */
@Composable
fun ArtistPage(
    target: ArtistSearchResult,
    detail: ArtistDetail?,
    detailLoading: Boolean,
    detailError: String?,
    songs: List<Song>,
    songsLoading: Boolean,
    songsLoadingMore: Boolean,
    songsHasMore: Boolean,
    songsError: String?,
    albums: List<AlbumSearchResult>,
    albumsLoading: Boolean,
    albumsLoadingMore: Boolean,
    albumsHasMore: Boolean,
    similar: List<ArtistSearchResult>,
    similarLoading: Boolean,
    favoriteRevision: Int = 0,
    downloadedRevision: Int = 0,
    isFavorite: (Song) -> Boolean = { false },
    isDownloaded: (Song) -> Boolean = { false },
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMoreSongs: () -> Unit = {},
    onLoadMoreAlbums: () -> Unit = {},
    onSongClick: (Int) -> Unit = {},
    onToggleFavorite: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onAlbumClick: (AlbumSearchResult) -> Unit = {},
    onSimilarArtistClick: (ArtistSearchResult) -> Unit = {},
    /** 切到「专辑」标签时回调，宿主据此懒加载专辑区块。 */
    onOpenAlbumsTab: () -> Unit = {},
    /** 切到「相似」标签时回调，宿主据此懒加载相似歌手区块。 */
    onOpenSimilarTab: () -> Unit = {},
) {
    // 切换歌手（含在相似列表点另一名歌手原地替换目标）时回到列表顶部；
    // 同一 target 的普通重组不能重置滚动位置。
    val listState = rememberLazyListState()
    LaunchedEffect(target.id) { listState.scrollToItem(0) }
    // 标签是纯界面状态：切换歌手时复位回「歌曲」。
    var currentTab by remember(target.id) { mutableStateOf(ArtistPageTab.SONGS) }
    LaunchedEffect(currentTab) {
        when (currentTab) {
            ArtistPageTab.ALBUMS -> onOpenAlbumsTab()
            ArtistPageTab.SIMILAR -> onOpenSimilarTab()
            ArtistPageTab.SONGS -> Unit
        }
    }
    // 头部与标签各占一个 item，其后才是各标签的条目；预取触发点要加上这两个前置 item。
    val leadingItemCount = 2
    val shouldLoadMore by remember(listState, songs.size, albums.size, currentTab) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            when (currentTab) {
                // 条目数不足以滚出预取窗口时（空页却 hasMore 的上游）不触发，靠 hasMore 收口。
                ArtistPageTab.SONGS -> songs.size > CatalogLoadMoreThreshold &&
                    last >= leadingItemCount + songs.size - CatalogLoadMoreThreshold
                // 专辑是双列网格：条目按行折半计数。
                ArtistPageTab.ALBUMS -> albums.size > CatalogLoadMoreThreshold &&
                    last >= leadingItemCount + (albums.size + 1) / 2 - CatalogLoadMoreThreshold
                ArtistPageTab.SIMILAR -> false
            }
        }
    }
    LaunchedEffect(shouldLoadMore, currentTab, songs.size, albums.size) {
        when {
            shouldLoadMore && currentTab == ArtistPageTab.SONGS -> onLoadMoreSongs()
            shouldLoadMore && currentTab == ArtistPageTab.ALBUMS -> onLoadMoreAlbums()
        }
    }
    Box(Modifier.fillMaxSize()) {
        CatalogPageBackdrop()
        Column(Modifier.fillMaxSize()) {
            CatalogTopBar(title = "歌手主页", onBack = onBack)
            when {
                // 详情未到达（加载中或失败）：头部用打开目标的兜底资料渲染，
                // 状态占位居中给加载 / 重试；不整页留白，用户至少知道自己在看谁。
                detail == null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
                        ArtistSummaryHeader(target = target, detail = null)
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CatalogPageStatus(
                                text = when {
                                    detailLoading -> "正在加载歌手资料…"
                                    detailError != null -> detailError
                                    else -> "歌手资料暂不可用"
                                },
                                loading = detailLoading,
                                onRetry = if (!detailLoading && detailError != null) onRetry else null,
                            )
                        }
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
                ) {
                    item(key = "artist-header") { ArtistSummaryHeader(target = target, detail = detail) }
                    item(key = "artist-tabs") { ArtistTabRow(currentTab = currentTab, onSelect = { currentTab = it }) }
                    when (currentTab) {
                        ArtistPageTab.SONGS -> catalogSongSection(
                            songs = songs,
                            firstPageLoading = songsLoading,
                            firstPageError = songsError,
                            onRetry = onRetry,
                            isLoadingMore = songsLoadingMore,
                            hasMore = songsHasMore,
                            favoriteRevision = favoriteRevision,
                            downloadedRevision = downloadedRevision,
                            isFavorite = isFavorite,
                            isDownloaded = isDownloaded,
                            onSongClick = onSongClick,
                            onToggleFavorite = onToggleFavorite,
                            onPlayNext = onPlayNext,
                            onAddToPlaylist = onAddToPlaylist,
                        )
                        ArtistPageTab.ALBUMS -> catalogAlbumSection(
                            albums = albums,
                            loading = albumsLoading,
                            onAlbumClick = onAlbumClick,
                        )
                        ArtistPageTab.SIMILAR -> catalogSimilarSection(
                            artists = similar,
                            loading = similarLoading,
                            onArtistClick = onSimilarArtistClick,
                        )
                    }
                    item(key = "artist-footer") { Spacer(Modifier.height(TaotaoSpacing.sm)) }
                }
            }
        }
    }
}

/**
 * 歌手页头部：大圆头像 + 名字 + 别名 + 统计行 + 可折叠简介。
 *
 * [detail] 未到达时用 [target]（搜索区块 / 相似列表点击时已有的最小资料）渲染，
 * 头像与名字立即可见；统计与简介等详情返回后再补 —— 搜索时的统计数可能过期，
 * 详情未到前不展示，防止出现前后不一致的数字。
 */
@Composable
private fun ArtistSummaryHeader(target: ArtistSearchResult, detail: ArtistDetail?) {
    val name = detail?.name?.takeIf { it.isNotBlank() } ?: target.name
    val pic = detail?.pic ?: target.pic
    Column(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CatalogArtistAvatar(name = name, pic = pic, size = ArtistHeroAvatarSize)
            Column(Modifier.weight(1f).padding(start = TaotaoSpacing.md)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val aliasName = detail?.aliasName.orEmpty()
                if (aliasName.isNotBlank()) {
                    Text(
                        aliasName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                    )
                }
            }
        }
        if (detail != null) {
            Text(
                "${formatCatalogCount(detail.musicCount)} 首 · " +
                    "${formatCatalogCount(detail.albumCount)} 张专辑 · 粉丝 ${formatCatalogCount(detail.fansCount)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = TaotaoSpacing.sm),
            )
            CatalogExpandableText(text = detail.desc, modifier = Modifier.padding(top = TaotaoSpacing.xs))
        }
    }
}

/** 三个内嵌标签：歌曲 / 专辑 / 相似。选中态用主题主色胶囊，未选中用浅色底。 */
@Composable
private fun ArtistTabRow(currentTab: ArtistPageTab, onSelect: (ArtistPageTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
        ArtistPageTab.entries.forEach { tab ->
            val selected = tab == currentTab
            Text(
                tab.label,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier
                    .clip(TaotaoShapes.pill)
                    .background(if (selected) TaotaoCoral else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = TaotaoSpacing.md, vertical = TaotaoSpacing.xs),
            )
        }
    }
}

/**
 * 目录页（歌手 / 专辑）的沉浸式顶栏：返回 + 居中标题。
 * 与单曲倒带日记页同一做法：宿主放开顶部内边距，这里用 statusBarsPadding 让开状态栏。
 */
@Composable
internal fun CatalogTopBar(title: String, onBack: () -> Unit) {
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
    }
}

/** 顶部珊瑚渐变背景：只铺头部高度再向下淡出，画到状态栏底下形成沉浸式。 */
@Composable
internal fun CatalogPageBackdrop() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(CatalogBackdropHeight)
            .background(Brush.verticalGradient(listOf(TaotaoCoral.copy(alpha = 0.18f), Color.Transparent))),
    )
}

/** 页面级加载 / 失败占位：详情未就绪时挂在头部下方，加载中转圈、失败给重试。 */
@Composable
internal fun CatalogPageStatus(text: String, loading: Boolean, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.padding(horizontal = TaotaoSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) CircularProgressIndicator(color = TaotaoCoral)
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (loading) TaotaoSpacing.md else TaotaoSpacing.none),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = TaotaoSpacing.xs)) { Text("重新加载") }
        }
    }
}

/** 列表区块内的失败占位：一行说明 + 重试；歌曲列表的错误不该弹全局提示。 */
@Composable
internal fun CatalogInlineStatus(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = TaotaoSpacing.xs)) { Text("重试") }
        }
    }
}

/**
 * 可折叠的长简介（歌手 / 专辑共用）。
 *
 * 默认收起 [CatalogCollapsedDescLines] 行；只有真实溢出过的文本才显示「展开」，
 * 用 onTextLayout 的 hasVisualOverflow 判定 —— 三行就放得下的简介不出现多余按钮。
 * 点击整段切换展开 / 收起。
 */
@Composable
internal fun CatalogExpandableText(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else CatalogCollapsedDescLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) overflowing = result.hasVisualOverflow },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        )
        if (overflowing || expanded) {
            Text(
                if (expanded) "收起" else "展开",
                color = TaotaoCoral,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = TaotaoSpacing.xxs)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

/** 简介折叠时的可见行数。 */
private const val CatalogCollapsedDescLines = 3

/**
 * 目录页共用的歌曲区块（歌手页「歌曲」标签与专辑页正文）。
 *
 * 必须在页面的 LazyColumn 里调用：按首屏加载 / 首屏失败 / 空态 / 列表四种情形展开，
 * 列表尾部自带「加载更多」与「没有更多了」占位。key 口径与搜索页一致（songKeyOf），
 * 状态层的 dedupeSongs 保证 key 唯一，LazyColumn 不会因重复 key 崩溃。
 */
internal fun LazyListScope.catalogSongSection(
    songs: List<Song>,
    firstPageLoading: Boolean,
    firstPageError: String?,
    onRetry: () -> Unit,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    favoriteRevision: Int,
    downloadedRevision: Int,
    isFavorite: (Song) -> Boolean,
    isDownloaded: (Song) -> Boolean,
    onSongClick: (Int) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
) {
    when {
        firstPageLoading && songs.isEmpty() -> {
            item(key = "catalog-songs-loading") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.progressInline))
                }
            }
        }
        songs.isEmpty() && firstPageError != null -> {
            item(key = "catalog-songs-error") { CatalogInlineStatus(text = firstPageError, onRetry = onRetry) }
        }
        songs.isEmpty() -> {
            item(key = "catalog-songs-empty") {
                EmptyStateView(
                    title = "还没有歌曲",
                    description = "这里暂时没有可播放的歌曲",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        else -> {
            itemsIndexed(
                songs,
                key = { _, song -> songKeyOf(song) },
                contentType = { _, _ -> "catalog-song" },
            ) { index, song ->
                val key = songKeyOf(song)
                SongListItem(
                    song = song,
                    active = false,
                    favorited = remember(key, favoriteRevision) { isFavorite(song) },
                    downloaded = remember(key, downloadedRevision) { isDownloaded(song) },
                    onToggleFavorite = { onToggleFavorite(song) },
                    onPlayNext = { onPlayNext(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                ) { onSongClick(index) }
            }
            if (isLoadingMore) {
                item(key = "catalog-loading-more") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.md),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(TaotaoSizes.progressInline), color = TaotaoCoral)
                        Text(
                            "正在加载更多…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = TaotaoSpacing.xs),
                        )
                    }
                }
            } else if (!hasMore) {
                item(key = "catalog-list-end") {
                    Text(
                        "没有更多了",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.md),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 目录页共用的专辑双列网格区块（歌手页「专辑」标签）。
 *
 * LazyColumn 内用按行展开的方式实现网格，不嵌套滚动容器；奇数末行的空位补一个
 * 等宽 Spacer，左列卡片才能保持与整行相同的高度对齐。
 */
internal fun LazyListScope.catalogAlbumSection(
    albums: List<AlbumSearchResult>,
    loading: Boolean,
    onAlbumClick: (AlbumSearchResult) -> Unit,
) {
    when {
        loading && albums.isEmpty() -> {
            item(key = "catalog-albums-loading") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.progressInline))
                }
            }
        }
        albums.isEmpty() -> {
            item(key = "catalog-albums-empty") {
                EmptyStateView(
                    title = "还没有专辑",
                    description = "这名歌手暂时没有可浏览的专辑",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        else -> {
            val rowCount = (albums.size + 1) / 2
            items(
                rowCount,
                key = { rowIndex -> "catalog-album-row-$rowIndex" },
                contentType = { "catalog-album-row" },
            ) { rowIndex ->
                val first = albums.getOrNull(rowIndex * 2)
                val second = albums.getOrNull(rowIndex * 2 + 1)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
                ) {
                    if (first != null) {
                        CatalogAlbumCard(album = first, onAlbumClick = onAlbumClick, modifier = Modifier.weight(1f))
                    }
                    if (second != null) {
                        CatalogAlbumCard(album = second, onAlbumClick = onAlbumClick, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 专辑卡片：与搜索页「专辑」区块同一套视觉（封面 + 专辑名 + 歌手名），点击进专辑页。 */
@Composable
private fun CatalogAlbumCard(
    album: AlbumSearchResult,
    onAlbumClick: (AlbumSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(TaotaoShapes.small)
            .clickable { onAlbumClick(album) }
            .padding(bottom = TaotaoSpacing.xxs),
    ) {
        // 与搜索区块一致的占位色：无图时由 AlbumArt 画色块 + 音符，整页观感统一。
        AlbumArt(
            color = Color(0xFFFFB4A2),
            size = TaotaoSizes.artworkGrid,
            imageUri = album.pic,
            shape = TaotaoShapes.artwork,
        )
        Text(
            album.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = TaotaoSpacing.xxs),
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 相似歌手区块：圆形头像 + 名字 + 统计的纵向列表，点击整项切换到该歌手
 * （同一页面替换目标并回到顶部，由宿主的 openArtistPage + 页面内滚动复位共同完成）。
 */
internal fun LazyListScope.catalogSimilarSection(
    artists: List<ArtistSearchResult>,
    loading: Boolean,
    onArtistClick: (ArtistSearchResult) -> Unit,
) {
    when {
        loading && artists.isEmpty() -> {
            item(key = "catalog-similar-loading") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.progressInline))
                }
            }
        }
        artists.isEmpty() -> {
            item(key = "catalog-similar-empty") {
                EmptyStateView(
                    title = "没有找到相似歌手",
                    description = "换个歌手再试试",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        else -> {
            // key 用下标：上游可能返回同名同 ID 的重复行，按内容做 key 会让 LazyColumn 崩溃。
            itemsIndexed(
                artists,
                key = { index, _ -> "catalog-similar-$index" },
                contentType = { _, _ -> "catalog-similar" },
            ) { _, artist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(TaotaoShapes.small)
                        .clickable { onArtistClick(artist) }
                        .padding(vertical = TaotaoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CatalogArtistAvatar(name = artist.name, pic = artist.pic, size = TaotaoSizes.avatar)
                    Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) {
                        Text(
                            artist.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${formatCatalogCount(artist.songCount)} 首 · ${formatCatalogCount(artist.albumCount)} 张专辑",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 歌手头像：有图用图，无图回退为主题色圆形 + 名字首字符。
 * 兜底思路与公共组件 [AlbumArt] 一致（图片淡入、色块占位），只是占位内容换成了首字符。
 */
@Composable
private fun CatalogArtistAvatar(name: String, pic: String?, size: Dp) {
    if (!pic.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(pic)
                .crossfade(AnimationDurations.FADE)
                .build(),
            contentDescription = "歌手头像",
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            // 空名字几乎没有实际场景，但占位圆里也不能什么都不画。
            Text(
                name.firstOrNull()?.toString() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

/**
 * 目录页统计数字的缩写：超过 1 亿按「1.2 亿」、超过 1 万按「4.8 万」折算，其余原样。
 * 折算结果只有一位小数且为 .0 时省略小数（「10 万」而不是「10.0 万」）。
 */
internal fun formatCatalogCount(count: Long): String = when {
    count >= CatalogCountYiThreshold -> "${formatScaledCount(count, CatalogCountYiThreshold)} 亿"
    count >= CatalogCountWanThreshold -> "${formatScaledCount(count, CatalogCountWanThreshold)} 万"
    else -> count.toString()
}

/** 按基数折算并保留一位小数；用长整型算到十分位再转回，避免浮点误差弄出 4.8000001。 */
private fun formatScaledCount(count: Long, base: Long): String {
    val tenths = count * 10 / base
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
