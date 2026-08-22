package com.taotao.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.model.Song

/** 搜索页面：负责关键词输入、流式结果展示和搜索结果进入动画。 */
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
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 },
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text("搜索音乐", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        MusicSearchBar(keyword, onKeywordChanged, onSearch, focusRequester = focusRequester)
        if (!hasSearched && history.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("搜索历史", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onHistoryClear) { Text("清空") }
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
        if (isSearching) SearchSkeletonList()
        if (hasSearched && !isSearching && !errorMessage.isNullOrBlank()) {
            Text(errorMessage, color = TaotaoCoral, modifier = Modifier.padding(top = 28.dp))
        }
        if (hasSearched && !isSearching && songs.isEmpty()) {
            if (errorMessage.isNullOrBlank()) {
                Text("没有找到相关歌曲", color = Color.Gray, modifier = Modifier.padding(top = 28.dp))
            }
        }
        if (hasSearched) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(songs, key = { index, song -> "${song.remoteId ?: index}-${song.title}" }) { index, song ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(song) { visible = true }
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(180, index.coerceAtMost(7) * 24)) + slideInVertically(tween(180, index.coerceAtMost(7) * 24)) { it / 12 },
                    ) {
                        SongListItem(song, false) { onSongClick(index, song) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SearchSkeletonList() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 18.dp)) {
        repeat(6) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEDE7E5)))
                Column(Modifier.padding(start = 13.dp)) {
                    Box(Modifier.width(150.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEDE7E5)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFF1ECEA)))
                }
            }
        }
    }
}

@Composable
fun MusicSearchBar(
    keyword: String,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onFocus: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChanged,
            modifier = Modifier.weight(1f)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { if (it.isFocused) onFocus() },
            singleLine = true,
            placeholder = { Text("搜索歌曲或歌手") },
            leadingIcon = { Icon(Icons.Default.Search, "搜索") },
        )
        TextButton(onClick = onSearch) {
            Text("搜索", color = TaotaoCoral)
        }
    }
}
