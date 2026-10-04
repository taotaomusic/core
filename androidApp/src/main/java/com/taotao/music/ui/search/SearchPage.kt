package com.taotao.music.ui.search

import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.EmptyStateView
import com.taotao.music.ui.common.MusicSearchBar
import com.taotao.music.ui.common.SearchSkeletonList
import com.taotao.music.ui.common.SongListItem
import com.taotao.music.ui.common.dedupeSongs
import com.taotao.music.ui.common.songKeyOf
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.TaotaoCoral
import com.taotao.music.ui.theme.contentFadeIn
import com.taotao.music.ui.theme.contentFadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.data.AlbumSearchResult
import com.taotao.music.data.ArtistSearchResult
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.playerui.SharedBackButton
import com.taotao.music.playerui.SharedContentState
import com.taotao.music.playerui.SharedContentStateType
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoElevation
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoTypeScale
// 「歌手 / 专辑」标签的失败占位与统计缩写复用歌手页已有的内部组件：同一模块内
// internal 可见，避免在搜索页再长一份一模一样的实现。
import com.taotao.music.ui.app.SearchTab
import com.taotao.music.ui.artist.CatalogInlineStatus
import com.taotao.music.ui.artist.formatCatalogCount

/**
 * 单个历史 chip 的文字宽度上限。
 *
 * 超出就省略号截断 —— 长关键词会把整行 chip 挤到只剩一个，
 * 流式布局也就失去了意义。
 */
private val HistoryChipMaxWidth = 160.dp

/**
 * 「歌手」区块头像的边长。
 *
 * 刻意不进 [TaotaoSizes]：这是搜索区块的一次性视觉规格，现有头像档（64dp）与
 * 网格封面档（112dp）都不合适；与 [HistoryChipMaxWidth] 一样按页面私有常量处理。
 */
private val ArtistAvatarSize = 72.dp

/** 搜索页面：负责关键词输入、结果展示和异步状态过渡。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchPage(
    keyword: String,
    songs: List<Song>,
    isSearching: Boolean,
    hasSearched: Boolean,
    errorMessage: String? = null,
    onBack: () -> Unit,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onSongClick: (Int, Song) -> Unit,
    history: List<String>,
    suggestions: List<String> = emptyList(),
    hotSearches: List<TencentMusicApi.HotSearchItem> = emptyList(),
    /** 是否记录新的搜索关键词；关闭后历史仍可点击，但不再新增。 */
    historyEnabled: Boolean = true,
    onHistoryEnabledChanged: (Boolean) -> Unit = {},
    onQuickSearch: (String) -> Unit,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    /** 长按拖动排序松手后，提交重排完成的完整历史序列。 */
    onHistoryReorder: (List<String>) -> Unit = {},
    /**
     * 收藏缓存的版本号。它本身不参与渲染，只是让缓存变化能触发重组 ——
     * 收藏状态存在 SharedPreferences 里，那东西不是可观察状态。
     */
    favoriteRevision: Int = 0,
    isFavorite: (Song) -> Boolean = { false },
    onToggleFavorite: ((Song) -> Unit)? = null,
    /** 将歌曲插到当前曲目的下一首；没有活动队列时由上层直接开始播放。 */
    onPlayNext: ((Song) -> Unit)? = null,
    /** 将搜索结果保存到云端歌单。 */
    onAddToPlaylist: ((Song) -> Unit)? = null,
    /** 已下载列表变化后自增，理由同 [favoriteRevision]。 */
    downloadedRevision: Int = 0,
    isDownloaded: (Song) -> Boolean = { false },
    /** 滚到接近底部时回调，用来拉下一页。 */
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
    hasMore: Boolean = false,
    total: Int = 0,
    /** 搜索命中的歌手区块；空列表时不渲染对应区块，也不留占位。 */
    artists: List<ArtistSearchResult> = emptyList(),
    /** 搜索命中的专辑区块；空列表时不渲染对应区块，也不留占位。 */
    albums: List<AlbumSearchResult> = emptyList(),
    /** 点击歌手项；歌手主页尚未实现，由调用方决定反馈方式。 */
    onArtistClick: (ArtistSearchResult) -> Unit = {},
    /** 点击专辑项；专辑页尚未实现，由调用方决定反馈方式。 */
    onAlbumClick: (AlbumSearchResult) -> Unit = {},
    // ---- 四标签（综合 / 单曲 / 歌手 / 专辑）----
    /** 当前结果标签；新搜索发起时状态层会回到「综合」。 */
    searchTab: SearchTab = SearchTab.OVERVIEW,
    /** 切换标签；「歌手 / 专辑」的首屏拉取由状态层在切换时触发，页面只上报意图。 */
    onTabSelected: (SearchTab) -> Unit = {},
    /** 「歌手」标签的分页数据与状态。 */
    tabArtists: List<ArtistSearchResult> = emptyList(),
    tabArtistsLoading: Boolean = false,
    tabArtistsLoadingMore: Boolean = false,
    tabArtistsHasMore: Boolean = false,
    tabArtistsError: String? = null,
    /** 名下歌手总数，只用于尾部「已显示全部」提示。 */
    tabArtistsTotal: Int = 0,
    /** 「专辑」标签的分页数据与状态，字段口径与歌手标签一致。 */
    tabAlbums: List<AlbumSearchResult> = emptyList(),
    tabAlbumsLoading: Boolean = false,
    tabAlbumsLoadingMore: Boolean = false,
    tabAlbumsHasMore: Boolean = false,
    tabAlbumsError: String? = null,
    tabAlbumsTotal: Int = 0,
    /** 「歌手 / 专辑」标签首屏失败后的重试入口（状态层里与首次进标签是同一个函数）。 */
    onEnsureTabArtists: () -> Unit = {},
    onEnsureTabAlbums: () -> Unit = {},
    /** 歌手 / 专辑标签滚到近底部时拉下一页。 */
    onLoadMoreTabArtists: () -> Unit = {},
    onLoadMoreTabAlbums: () -> Unit = {},
    /**
     * 歌曲行菜单「查看歌手」，入参是歌曲本身；为空或歌曲没带 artistId 时菜单项隐藏。
     * 跳转目标由调用方从歌曲构造（歌手头像不等于歌曲封面，pic 传 null 让歌手页自拉资料）。
     */
    onOpenArtist: ((Song) -> Unit)? = null,
    /** 歌曲行菜单「查看专辑」，可见条件同 [onOpenArtist]（要求歌曲带 albumId）。 */
    onOpenAlbum: ((Song) -> Unit)? = null,
    /** 保留兼容当前调用链；搜索结果不再需要逐条入场状态。 */
    searchSession: Int = 0,
) {
    val focusRequester = remember { FocusRequester() }
    // 进入搜索页自动聚焦输入框。焦点请求必须在组件挂载后发起，否则请求会被丢弃。
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
        SharedSectionHeader(
            title = "搜索音乐",
            level = SharedSectionLevel.PAGE,
            leading = { SharedBackButton(onBack) },
        )
        MusicSearchBar(
            keyword = keyword,
            onKeywordChanged = onKeywordChanged,
            onSearch = onSearch,
            focusRequester = focusRequester,
        )
        // 搜索历史放在热门搜索上面：个人历史的使用频率通常高于热搜榜。
        if (!hasSearched && history.isNotEmpty()) {
            var showClearConfirm by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("搜索历史", style = TaotaoTypeScale.sectionTitle, modifier = Modifier.weight(1f))
                Text(
                    if (historyEnabled) "记录" else "已暂停",
                    color = if (historyEnabled) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral,
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = historyEnabled,
                    onCheckedChange = onHistoryEnabledChanged,
                    modifier = Modifier.semantics { contentDescription = "是否记录搜索历史" },
                )
                TextButton(onClick = { showClearConfirm = true }) { Text("清空", color = TaotaoCoral) }
            }
            // 流式标签布局：一条历史一个 chip，横向排满自动换行，比逐行列表省一半以上空间。
            SearchHistoryChips(
                history = history,
                onHistoryClick = onHistoryClick,
                onHistoryRemove = onHistoryRemove,
                onHistoryReorder = onHistoryReorder,
                modifier = Modifier.fillMaxWidth().padding(bottom = TaotaoSpacing.xxs),
            )
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("清空搜索历史") },
                    text = { Text("确定要清空全部搜索历史吗？清空后无法恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            onHistoryClear()
                            showClearConfirm = false
                        }) { Text("清空", color = TaotaoCoral) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                    },
                )
            }
        }
        if (!hasSearched && keyword.isNotBlank() && suggestions.isNotEmpty()) {
            Text(
                "搜索联想",
                style = TaotaoTypeScale.sectionTitle,
                modifier = Modifier.padding(top = TaotaoSpacing.xs, bottom = TaotaoSpacing.xxs),
            )
            suggestions.forEach { suggestion ->
                ListItem(
                    headlineContent = { Text(suggestion) },
                    leadingContent = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.fillMaxWidth().clickable { onQuickSearch(suggestion) },
                )
            }
        }
        if (!hasSearched && keyword.isBlank() && hotSearches.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = TaotaoSpacing.sm, bottom = TaotaoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("热门搜索", style = TaotaoTypeScale.sectionTitle, modifier = Modifier.weight(1f))
                Text("实时更新", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.xxs)) {
                    hotSearches.take(10).chunked(2).forEachIndexed { rowIndex, rowItems ->
                        Row(Modifier.fillMaxWidth()) {
                            rowItems.forEachIndexed { columnIndex, item ->
                                val rank = rowIndex * 2 + columnIndex + 1
                                Row(
                                    modifier = Modifier.weight(1f)
                                        .clickable { onQuickSearch(item.keyword) }
                                        .padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        rank.toString(),
                                        color = if (rank <= 3) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(end = TaotaoSpacing.xs),
                                    )
                                    Text(item.keyword, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (rowItems.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // 骨架屏 / 错误 / 空态之间用淡入淡出切换，硬切会让内容"跳"一下。
        // 结果已经到了就不再显示骨架屏，否则骨架和结果会同时出现。
        AnimatedVisibility(
            // 歌手 / 专辑区块先于歌曲到达时也算「有内容」，骨架屏要让位，避免和区块叠在一起。
            visible = isSearching && songs.isEmpty() && artists.isEmpty() && albums.isEmpty(),
            enter = contentFadeIn(),
            exit = contentFadeOut(),
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "正在搜索音乐"
            },
        ) {
            SearchSkeletonList()
        }
        AnimatedVisibility(
            visible = hasSearched && !isSearching && !errorMessage.isNullOrBlank(),
            enter = contentFadeIn(),
            exit = contentFadeOut(),
        ) {
            SharedContentState(
                type = SharedContentStateType.ERROR,
                title = "搜索失败",
                description = errorMessage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(
            // 命中了区块但歌曲全被预筛掉时不弹空态：那仍是「搜到了」，继续显示区块。
            // 歌手 / 专辑标签自带各自的数据源与空态，这里的「没有找到相关歌曲」只属于
            // 综合与单曲两个以歌曲为准的标签，否则会一页出现两个空态。
            visible = hasSearched && !isSearching && songs.isEmpty() && errorMessage.isNullOrBlank() &&
                artists.isEmpty() && albums.isEmpty() &&
                (searchTab == SearchTab.OVERVIEW || searchTab == SearchTab.SONGS),
            enter = contentFadeIn(),
            exit = contentFadeOut(),
        ) {
            EmptyStateView(
                title = "没有找到相关歌曲",
                description = "换个关键词再试试",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (hasSearched) {
            val listState = rememberLazyListState()
            // 四标签切换行：视觉沿用歌手页的内嵌胶囊标签（选中主色胶囊、未选中浅底）。
            SearchTabRow(
                currentTab = searchTab,
                onSelect = onTabSelected,
                modifier = Modifier.padding(top = TaotaoSpacing.xs, bottom = TaotaoSpacing.xxs),
            )
            // 「综合」标签的歌手 / 专辑区块各占列表顶部一个 item，预取阈值要把它们计入，
            // 否则触发点会随区块出现而后移，「加载更多」就拉晚了；其余标签没有前置区块。
            val leadingItemCount = if (searchTab == SearchTab.OVERVIEW) {
                (if (artists.isNotEmpty()) 1 else 0) + (if (albums.isNotEmpty()) 1 else 0)
            } else {
                0
            }
            // 滚到距列表底部 6 项以内就预取下一页，等真滚到底再拉会有明显的空档。
            // 专辑标签是双列网格，条目按行折半计数（与歌手页的专辑网格同一口径）；
            // 条目数不足以滚出预取窗口时不触发，靠 hasMore 收口。
            val shouldLoadMore by remember(
                listState, searchTab, leadingItemCount, songs.size, tabArtists.size, tabAlbums.size,
            ) {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                        ?: return@derivedStateOf false
                    when (searchTab) {
                        SearchTab.OVERVIEW -> songs.isNotEmpty() && last >= songs.size - 6 + leadingItemCount
                        SearchTab.SONGS -> songs.isNotEmpty() && last >= songs.size - 6
                        SearchTab.ARTISTS -> tabArtists.size > 6 && last >= tabArtists.size - 6
                        SearchTab.ALBUMS -> tabAlbums.size > 6 && last >= (tabAlbums.size + 1) / 2 - 6
                    }
                }
            }
            // 预取按当前标签分派；标签与各列表长度都是 key，切换标签后重新评估，
            // 不会把上一标签的加载请求错发给当前标签。
            LaunchedEffect(shouldLoadMore, searchTab, songs.size, tabArtists.size, tabAlbums.size) {
                when {
                    shouldLoadMore && (searchTab == SearchTab.OVERVIEW || searchTab == SearchTab.SONGS) -> onLoadMore()
                    shouldLoadMore && searchTab == SearchTab.ARTISTS -> onLoadMoreTabArtists()
                    shouldLoadMore && searchTab == SearchTab.ALBUMS -> onLoadMoreTabAlbums()
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
            ) {
                when (searchTab) {
                    SearchTab.OVERVIEW -> {
                        // 歌手 / 专辑区块排在歌曲之前，随内容一起滚动；空区块直接不组合，不留空白。
                        if (artists.isNotEmpty()) {
                            item(key = "search-artists", contentType = "artist-section") {
                                ArtistSearchSection(artists = artists, onArtistClick = onArtistClick)
                            }
                        }
                        if (albums.isNotEmpty()) {
                            item(key = "search-albums", contentType = "album-section") {
                                AlbumSearchSection(albums = albums, onAlbumClick = onAlbumClick)
                            }
                        }
                        searchSongItems(
                            songs = songs,
                            favoriteRevision = favoriteRevision,
                            downloadedRevision = downloadedRevision,
                            isFavorite = isFavorite,
                            isDownloaded = isDownloaded,
                            onSongClick = onSongClick,
                            onToggleFavorite = onToggleFavorite,
                            onPlayNext = onPlayNext,
                            onAddToPlaylist = onAddToPlaylist,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                        )
                        searchSongFooter(
                            isLoadingMore = isLoadingMore,
                            hasMore = hasMore,
                            songs = songs,
                            total = total,
                        )
                    }
                    SearchTab.SONGS -> {
                        // 与「综合」同一份 songs 数据，只是不再显示歌手 / 专辑区块。
                        searchSongItems(
                            songs = songs,
                            favoriteRevision = favoriteRevision,
                            downloadedRevision = downloadedRevision,
                            isFavorite = isFavorite,
                            isDownloaded = isDownloaded,
                            onSongClick = onSongClick,
                            onToggleFavorite = onToggleFavorite,
                            onPlayNext = onPlayNext,
                            onAddToPlaylist = onAddToPlaylist,
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                        )
                        searchSongFooter(
                            isLoadingMore = isLoadingMore,
                            hasMore = hasMore,
                            songs = songs,
                            total = total,
                        )
                    }
                    SearchTab.ARTISTS -> searchTabArtistSection(
                        artists = tabArtists,
                        loading = tabArtistsLoading,
                        error = tabArtistsError,
                        isLoadingMore = tabArtistsLoadingMore,
                        hasMore = tabArtistsHasMore,
                        total = tabArtistsTotal,
                        onRetry = onEnsureTabArtists,
                        onArtistClick = onArtistClick,
                    )
                    SearchTab.ALBUMS -> searchTabAlbumSection(
                        albums = tabAlbums,
                        loading = tabAlbumsLoading,
                        error = tabAlbumsError,
                        isLoadingMore = tabAlbumsLoadingMore,
                        hasMore = tabAlbumsHasMore,
                        total = tabAlbumsTotal,
                        onRetry = onEnsureTabAlbums,
                        onAlbumClick = onAlbumClick,
                    )
                }
            }
        }
    }
}

/** 四个结果标签：视觉沿用歌手页的内嵌胶囊标签 —— 选中主色胶囊，未选中浅色底。 */
@Composable
private fun SearchTabRow(
    currentTab: SearchTab,
    onSelect: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
        SearchTab.entries.forEach { tab ->
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
 * 「综合 / 单曲」标签共用的歌曲条目。
 *
 * 必须在页面的 LazyColumn 里调用；「三个点」菜单的「查看歌手 / 查看专辑」在这里从
 * 歌曲构造回调 —— 歌曲**没带对应 ID**（非酷我音源）时该项即便上层给了回调也按隐藏处理，
 * 与组件侧「ID 非空 + 回调非空」的双门槛保持一致。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.searchSongItems(
    songs: List<Song>,
    favoriteRevision: Int,
    downloadedRevision: Int,
    isFavorite: (Song) -> Boolean,
    isDownloaded: (Song) -> Boolean,
    onSongClick: (Int, Song) -> Unit,
    onToggleFavorite: ((Song) -> Unit)?,
    onPlayNext: ((Song) -> Unit)?,
    onAddToPlaylist: ((Song) -> Unit)?,
    onOpenArtist: ((Song) -> Unit)?,
    onOpenAlbum: ((Song) -> Unit)?,
) {
    itemsIndexed(
        songs,
        // 与 dedupeSongs 的判重口径一致，所以这里的 key 必然唯一。
        key = { _, song -> songKeyOf(song) },
        contentType = { _, _ -> "song" },
    ) { index, song ->
        val key = songKeyOf(song)
        val favorited = remember(key, favoriteRevision) { isFavorite(song) }
        SongListItem(
            song = song,
            active = false,
            favorited = favorited,
            downloaded = remember(key, downloadedRevision) { isDownloaded(song) },
            onToggleFavorite = onToggleFavorite
                ?.takeIf { song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank() }
                ?.let { toggle -> { toggle(song) } },
            onPlayNext = onPlayNext?.let { callback -> { callback(song) } },
            onAddToPlaylist = onAddToPlaylist
                ?.takeIf { song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank() }
                ?.let { callback -> { callback(song) } },
            onOpenArtist = onOpenArtist
                ?.takeIf { song.artistId != null }
                ?.let { open -> { open(song) } },
            onOpenAlbum = onOpenAlbum
                ?.takeIf { song.albumId != null }
                ?.let { open -> { open(song) } },
            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
        ) { onSongClick(index, song) }
    }
}

/** 「综合 / 单曲」标签共用的列表尾部：「加载更多」占位与「已显示全部」收口。 */
private fun LazyListScope.searchSongFooter(
    isLoadingMore: Boolean,
    hasMore: Boolean,
    songs: List<Song>,
    total: Int,
) {
    if (isLoadingMore) {
        item(key = "loading-more") {
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
    } else if (songs.isNotEmpty() && !hasMore) {
        item(key = "list-end") {
            Text(
                if (total > 0) "已显示全部 ${songs.size} 首（共搜到 $total 首）" else "没有更多了",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.md),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 搜索历史标签区：流式 chip 布局，支持长按拖动排序。
 * 拖动过程中用本地预览序列渲染，松手后才通过 [onHistoryReorder] 整体提交；
 * chip 被预览重排到新槽位时补偿拖拽偏移，保证视觉位置始终跟手指。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryChips(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 每个 chip 相对 FlowRow 的矩形，拖动时用它判定指针压在哪个 chip 上。
    val chipBounds = remember { mutableStateMapOf<String, Rect>() }
    var dragKeyword by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // 拖动中的预览序列；null 表示当前没在拖动，直接渲染上层传入的历史。
    var preview by remember { mutableStateOf<List<String>?>(null) }
    val items = preview ?: history

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxs),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .zIndex(if (dragKeyword == item) 1f else 0f)
                    .graphicsLayer {
                        if (dragKeyword == item) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.05f
                            scaleY = 1.05f
                            shape = TaotaoShapes.pill
                            shadowElevation = TaotaoElevation.overlay.toPx()
                        }
                    }
                    .onGloballyPositioned { coords ->
                        val rect = Rect(coords.positionInParent(), coords.size.toSize())
                        val old = chipBounds.put(item, rect)
                        // 预览序列变化会把 chip 重排到新槽位，把位移从拖拽偏移里扣掉，
                        // 视觉上 chip 才不会跳到手指前面或后面。
                        if (item == dragKeyword && old != null && old.topLeft != rect.topLeft) {
                            dragOffset += old.center - rect.center
                        }
                    }
                    .clip(TaotaoShapes.pill)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable { onHistoryClick(item) }
                    .pointerInput(item) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragKeyword = item
                                dragOffset = Offset.Zero
                                preview = null
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount
                                val bounds = chipBounds[item] ?: return@detectDragGesturesAfterLongPress
                                val center = bounds.center + dragOffset
                                val target = chipBounds.entries
                                    .firstOrNull { (key, rect) -> key != item && rect.contains(center) }
                                    ?.key ?: return@detectDragGesturesAfterLongPress
                                val current = preview ?: history
                                val from = current.indexOf(item)
                                val to = current.indexOf(target)
                                if (from >= 0 && to >= 0 && from != to) {
                                    preview = current.toMutableList().apply {
                                        removeAt(from)
                                        add(to, item)
                                    }
                                }
                            },
                            onDragEnd = {
                                preview?.let(onHistoryReorder)
                                dragKeyword = null
                                preview = null
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = {
                                dragKeyword = null
                                preview = null
                                dragOffset = Offset.Zero
                            },
                        )
                    }
                    .padding(
                        start = TaotaoSpacing.sm,
                        end = TaotaoSpacing.xxs,
                        top = TaotaoSpacing.xxs,
                        bottom = TaotaoSpacing.xxs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = HistoryChipMaxWidth),
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除历史「$item」",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = TaotaoSpacing.xxs)
                        .clip(CircleShape)
                        .clickable { onHistoryRemove(item) }
                        .padding(TaotaoSpacing.xxs)
                        .size(TaotaoSizes.iconXs),
                )
            }
        }
    }
}

/**
 * 「歌手」搜索区块：标题 + 横向滚动的圆形头像列表。
 *
 * 只在列表非空时由结果列表组合；点击整项交给 [onArtistClick]，区块自身不感知
 * 跳转目标，保持单向数据流。
 */
@Composable
private fun ArtistSearchSection(
    artists: List<ArtistSearchResult>,
    onArtistClick: (ArtistSearchResult) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.sm)) {
        Text("歌手", style = TaotaoTypeScale.sectionTitle)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.md),
            contentPadding = PaddingValues(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.xxs),
        ) {
            items(artists) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(TaotaoShapes.small)
                        .clickable { onArtistClick(artist) }
                        .padding(TaotaoSpacing.xs),
                ) {
                    ArtistSearchAvatar(name = artist.name, pic = artist.pic)
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        // 与头像同宽：名字过长时省略号收在头像宽度内，项与项之间不会互相挤压。
                        modifier = Modifier.padding(top = TaotaoSpacing.xxs).width(ArtistAvatarSize),
                    )
                }
            }
        }
    }
}

/**
 * 歌手头像：有图用图，无图回退为主题色圆形 + 名字首字符。
 * 兜底思路与公共组件 [AlbumArt] 一致（图片淡入、色块占位），只是占位内容换成了首字符。
 * 「歌手」标签的列表行与「综合」区块共用，只是尺寸不同（区块 72dp、列表行用全局头像档）。
 */
@Composable
private fun ArtistSearchAvatar(name: String, pic: String?, size: Dp = ArtistAvatarSize) {
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
 * 「专辑」搜索区块：标题 + 横向滚动的圆角封面卡片。
 * 只在列表非空时由结果列表组合；点击整项交给 [onAlbumClick]。
 */
@Composable
private fun AlbumSearchSection(
    albums: List<AlbumSearchResult>,
    onAlbumClick: (AlbumSearchResult) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.sm)) {
        Text("专辑", style = TaotaoTypeScale.sectionTitle)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.md),
            contentPadding = PaddingValues(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.xxs),
        ) {
            items(albums) { album ->
                // 横滑区块按封面档定宽；「专辑」标签的网格里由 weight(1f) 平分宽度，
                // 所以固定宽度不能写进共享卡片内部。
                AlbumResultCard(
                    album = album,
                    onAlbumClick = onAlbumClick,
                    modifier = Modifier.width(TaotaoSizes.artworkGrid),
                )
            }
        }
    }
}

/**
 * 专辑卡片：与歌手页目录网格同一套视觉（封面 + 专辑名 + 歌手名），点击进专辑页。
 * 「综合」区块的横滑卡片与「专辑」标签的网格卡片共用这一份实现，避免视觉漂移；
 * 宽度策略由调用方给 [modifier]（区块定宽 / 网格 weight），卡片自身不定宽。
 */
@Composable
private fun AlbumResultCard(
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
        // 封面直接复用全局 [AlbumArt]：112dp 高于列表行封面档，组件会按尺寸
        // 自动判成圆形，这里显式传圆角形状覆盖；无图时组件自带色块 + 音符兜底，
        // 颜色沿用歌曲行封面的占位色，整页观感一致。
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
            modifier = Modifier
                .padding(top = TaotaoSpacing.xxs)
                .padding(horizontal = TaotaoSpacing.xxs),
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = TaotaoSpacing.xxs),
        )
    }
}

/**
 * 「歌手」标签：分页的歌手列表（圆形头像 + 名字 + 统计行），点击进歌手主页。
 *
 * 必须在页面的 LazyColumn 里调用：按首屏加载 / 失败 / 空态 / 列表四种情形展开，
 * 尾部自带「加载更多」与「已显示全部」。条目 key 用下标 —— 与歌手页相似列表同一理由，
 * 上游可能返回同名同 ID 的重复行，状态层判重兜底之外再保险一层。
 */
private fun LazyListScope.searchTabArtistSection(
    artists: List<ArtistSearchResult>,
    loading: Boolean,
    error: String?,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    total: Int,
    onRetry: () -> Unit,
    onArtistClick: (ArtistSearchResult) -> Unit,
) {
    when {
        loading && artists.isEmpty() -> {
            item(key = "tab-artists-loading") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.progressInline))
                }
            }
        }
        artists.isEmpty() && error != null -> {
            item(key = "tab-artists-error") { CatalogInlineStatus(text = error, onRetry = onRetry) }
        }
        artists.isEmpty() -> {
            item(key = "tab-artists-empty") {
                EmptyStateView(
                    title = "没有找到相关歌手",
                    description = "换个关键词再试试",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        else -> {
            itemsIndexed(
                artists,
                key = { index, _ -> "tab-artist-$index" },
                contentType = { _, _ -> "tab-artist" },
            ) { _, artist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(TaotaoShapes.small)
                        .clickable { onArtistClick(artist) }
                        .padding(vertical = TaotaoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtistSearchAvatar(name = artist.name, pic = artist.pic, size = TaotaoSizes.avatar)
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
            searchTabFooter(
                key = "tab-artists-footer-more",
                endKey = "tab-artists-end",
                isLoadingMore = isLoadingMore,
                hasMore = hasMore,
                shownCount = artists.size,
                unit = "位",
                total = total,
            )
        }
    }
}

/**
 * 「专辑」标签：分页的专辑双列网格，点击进专辑主页。
 *
 * LazyColumn 内按行展开实现网格（不嵌套滚动容器），奇数末行的空位补等宽 Spacer；
 * 预取窗口按行数折半的口径由页面的 shouldLoadMore 负责。
 */
private fun LazyListScope.searchTabAlbumSection(
    albums: List<AlbumSearchResult>,
    loading: Boolean,
    error: String?,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    total: Int,
    onRetry: () -> Unit,
    onAlbumClick: (AlbumSearchResult) -> Unit,
) {
    when {
        loading && albums.isEmpty() -> {
            item(key = "tab-albums-loading") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.progressInline))
                }
            }
        }
        albums.isEmpty() && error != null -> {
            item(key = "tab-albums-error") { CatalogInlineStatus(text = error, onRetry = onRetry) }
        }
        albums.isEmpty() -> {
            item(key = "tab-albums-empty") {
                EmptyStateView(
                    title = "没有找到相关专辑",
                    description = "换个关键词再试试",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        else -> {
            val rowCount = (albums.size + 1) / 2
            items(
                rowCount,
                key = { rowIndex -> "tab-album-row-$rowIndex" },
                contentType = { "tab-album-row" },
            ) { rowIndex ->
                val first = albums.getOrNull(rowIndex * 2)
                val second = albums.getOrNull(rowIndex * 2 + 1)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
                ) {
                    if (first != null) {
                        AlbumResultCard(album = first, onAlbumClick = onAlbumClick, modifier = Modifier.weight(1f))
                    }
                    if (second != null) {
                        AlbumResultCard(album = second, onAlbumClick = onAlbumClick, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            searchTabFooter(
                key = "tab-albums-footer-more",
                endKey = "tab-albums-end",
                isLoadingMore = isLoadingMore,
                hasMore = hasMore,
                shownCount = albums.size,
                unit = "张",
                total = total,
            )
        }
    }
}

/** 「歌手 / 专辑」标签共用的尾部：「加载更多」占位与「已显示全部」收口。 */
private fun LazyListScope.searchTabFooter(
    key: String,
    endKey: String,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    shownCount: Int,
    unit: String,
    total: Int,
) {
    if (isLoadingMore) {
        item(key = key) {
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
        item(key = endKey) {
            Text(
                if (total > 0) "已显示全部 $shownCount $unit（共 $total $unit）" else "没有更多了",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.md),
                textAlign = TextAlign.Center,
            )
        }
    }
}
