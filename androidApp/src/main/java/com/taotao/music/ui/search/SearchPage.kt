package com.taotao.music.ui.search

import com.taotao.music.ui.common.EmptyStateView
import com.taotao.music.ui.common.MusicSearchBar
import com.taotao.music.ui.common.SearchSkeletonList
import com.taotao.music.ui.common.SongListItem
import com.taotao.music.ui.common.dedupeSongs
import com.taotao.music.ui.common.songKeyOf
import com.taotao.music.ui.theme.TaotaoCoral
import com.taotao.music.ui.theme.contentFadeIn
import com.taotao.music.ui.theme.contentFadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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

/**
 * 单个历史 chip 的文字宽度上限。
 *
 * 超出就省略号截断 —— 长关键词会把整行 chip 挤到只剩一个，
 * 流式布局也就失去了意义。
 */
private val HistoryChipMaxWidth = 160.dp

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
            visible = isSearching && songs.isEmpty(),
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
            visible = hasSearched && !isSearching && songs.isEmpty() && errorMessage.isNullOrBlank(),
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
            // 滚到距底部 6 项以内就预取下一页，等真滚到底再拉会有明显的空档。
            val shouldLoadMore by remember(listState, songs.size) {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                    songs.isNotEmpty() && last >= songs.size - 6
                }
            }
            LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
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
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    ) { onSongClick(index, song) }
                }
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
