package com.taotao.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.model.Song

/** 搜索页面：负责关键词输入、结果展示和异步状态过渡。 */
@OptIn(ExperimentalFoundationApi::class)
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
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    /**
     * 收藏缓存的版本号。它本身不参与渲染，只是让缓存变化能触发重组 ——
     * 收藏状态存在 SharedPreferences 里，那东西不是可观察状态。
     */
    favoriteRevision: Int = 0,
    isFavorite: (Song) -> Boolean = { false },
    onToggleFavorite: ((Song) -> Unit)? = null,
    /** 将歌曲插到当前曲目的下一首；没有活动队列时由上层直接开始播放。 */
    onPlayNext: ((Song) -> Unit)? = null,
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

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("搜索音乐", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        MusicSearchBar(
            keyword = keyword,
            onKeywordChanged = onKeywordChanged,
            onSearch = onSearch,
            focusRequester = focusRequester,
        )
        if (!hasSearched && history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("搜索历史", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onHistoryClear) { Text("清空", color = TaotaoCoral) }
            }
            history.forEach { item ->
                ListItem(
                    headlineContent = { Text(item) },
                    leadingContent = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { TextButton(onClick = { onHistoryRemove(item) }) { Text("删除") } },
                    modifier = Modifier.fillMaxWidth().clickable { onHistoryClick(item) },
                )
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
            Text(
                errorMessage.orEmpty(),
                color = TaotaoCoral,
                modifier = Modifier.padding(top = 28.dp).semantics { liveRegion = LiveRegionMode.Polite },
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            ?.takeIf { song.remoteId != null }
                            ?.let { toggle -> { toggle(song) } },
                        onPlayNext = onPlayNext?.let { callback -> { callback(song) } },
                        modifier = Modifier.animateItem(),
                    ) { onSongClick(index, song) }
                }
                if (isLoadingMore) {
                    item(key = "loading-more") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = TaotaoCoral)
                            Text("正在加载更多…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                } else if (songs.isNotEmpty() && !hasMore) {
                    item(key = "list-end") {
                        Text(
                            if (total > 0) "已显示全部 ${songs.size} 首（共搜到 $total 首）" else "没有更多了",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}









