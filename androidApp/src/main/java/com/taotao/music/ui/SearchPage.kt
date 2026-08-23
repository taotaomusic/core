package com.taotao.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.model.Song

/** 搜索页面：负责关键词输入、结果展示和结果逐条入场动画。 */
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
    /** 滚到接近底部时回调，用来拉下一页。 */
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
    hasMore: Boolean = false,
    total: Int = 0,
    /** 每次新搜索自增。用它重置「哪些项已经播过入场动画」的记录。 */
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
                    leadingContent = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                    trailingContent = { TextButton(onClick = { onHistoryRemove(item) }) { Text("删除") } },
                    modifier = Modifier.fillMaxWidth().clickable { onHistoryClick(item) },
                )
            }
        }
        // 结果现在是逐条到达的，已经有结果就不该再显示骨架屏，否则骨架和结果会同时出现。
        if (isSearching && songs.isEmpty()) SearchSkeletonList()
        if (hasSearched && !isSearching && !errorMessage.isNullOrBlank()) {
            Text(errorMessage, color = TaotaoCoral, modifier = Modifier.padding(top = 28.dp))
        }
        if (hasSearched && !isSearching && songs.isEmpty() && errorMessage.isNullOrBlank()) {
            EmptyStateView(title = "没有找到相关歌曲", description = "换个关键词再试试")
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

            // 已经播过入场动画的歌。必须放在 LazyColumn **外面**：
            // LazyColumn 会销毁滚出屏幕的项，item 内部的 remember 随之重置，
            // 结果每次滚回来整屏都重新淡入一遍 —— 那就是滑动时看到的抖动来源。
            val animated = remember(searchSession) { mutableSetOf<Long>() }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    songs,
                    key = { index, song -> song.remoteId ?: -(index.toLong() + 1) },
                    contentType = { _, _ -> "song" },
                ) { index, song ->
                    val id = song.remoteId ?: -(index.toLong() + 1)
                    val isNew = remember(id) { animated.add(id) }
                    var visible by remember(id) { mutableStateOf(!isNew) }
                    LaunchedEffect(id) { visible = true }
                    AnimatedVisibility(
                        visible = visible,
                        // 只有首次出现才错开延迟；回滚复用的项直接显示，不再重播动画。
                        enter = if (isNew) listItemEnter(index) else EnterTransition.None,
                    ) {
                        val favorited = remember(id, favoriteRevision) { isFavorite(song) }
                        SongListItem(
                            song = song,
                            active = false,
                            favorited = favorited,
                            onToggleFavorite = onToggleFavorite
                                ?.takeIf { song.remoteId != null }
                                ?.let { toggle -> { toggle(song) } },
                        ) { onSongClick(index, song) }
                    }
                }
                if (isLoadingMore) {
                    item(key = "loading-more") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = TaotaoCoral)
                            Text("正在加载更多…", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                } else if (songs.isNotEmpty() && !hasMore) {
                    item(key = "list-end") {
                        Text(
                            if (total > 0) "已显示全部 ${songs.size} 首（共搜到 $total 首）" else "没有更多了",
                            color = Color.Gray,
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
