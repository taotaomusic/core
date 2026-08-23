package com.taotao.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(songs, key = { index, song -> "${song.remoteId ?: index}-${song.title}" }) { index, song ->
                    var visible by remember(song) { mutableStateOf(false) }
                    LaunchedEffect(song) { visible = true }
                    AnimatedVisibility(visible = visible, enter = listItemEnter(index)) {
                        // favoriteRevision 参与读取，收藏变化才会重组到这一行。
                        val favorited = remember(song.remoteId, favoriteRevision) { isFavorite(song) }
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
            }
        }
    }
}
