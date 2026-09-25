package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.FavoritesStore
import com.taotao.music.data.QualityStore
import com.taotao.music.data.SearchHistoryStore
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.ui.common.dedupeSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 搜索域状态与操作：关键词、逐行下发的结果流、分页、历史与联想。
 *
 * 原先全部内联在主 Composable 里；抽出后由全局状态容器持有，
 * 搜索页装配只读这里的属性。
 */
internal class SearchState(
    private val scope: CoroutineScope,
    private val musicApi: TencentMusicApi,
    private val qualityStore: QualityStore,
    private val historyStore: SearchHistoryStore,
    private val favoritesStore: FavoritesStore,
    private val onMessage: (String) -> Unit,
    private val onFavoritesTouched: () -> Unit,
) {
    var keyword by mutableStateOf("")
    var results by mutableStateOf(emptyList<Song>())
    var isSearching by mutableStateOf(false)
    var hasSearched by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** 请求代次：请求返回后校验，旧请求不得覆盖新状态。 */
    var generation by mutableIntStateOf(0)
    /** 分页状态。服务端一直在返回 hasMore / total，客户端以前直接丢掉。 */
    var page by mutableIntStateOf(1)
    var hasMore by mutableStateOf(false)
    var total by mutableIntStateOf(0)
    var isLoadingMore by mutableStateOf(false)
    var history by mutableStateOf(historyStore.read())
    var historyEnabled by mutableStateOf(historyStore.isEnabled())
    var suggestions by mutableStateOf(emptyList<String>())
    var hotSearches by mutableStateOf(emptyList<TencentMusicApi.HotSearchItem>())

    /** 打开搜索页时重置结果区；页面开关本身由全局状态管理。 */
    fun openPage() {
        generation += 1
        results = emptyList()
        hasSearched = false
        isSearching = false
        error = null
        page = 1
        hasMore = false
        total = 0
        isLoadingMore = false
    }

    /** 输入框每次变化：更新关键词并清掉上一次的结果与错误。 */
    fun onKeywordInput(value: String) {
        keyword = value
        hasSearched = false
        results = emptyList()
        error = null
    }

    /** 热搜/快捷词：直接填入并发起搜索。 */
    fun quickSearch(value: String) {
        keyword = value
        suggestions = emptyList()
        start()
    }

    fun start() {
        val query = keyword.trim()
        if (query.isBlank()) return
        historyStore.add(query)
        history = historyStore.read()
        val requestGeneration = generation + 1
        generation = requestGeneration
        scope.launch {
            hasSearched = true
            isSearching = true
            error = null
            results = emptyList()
            page = 1
            hasMore = false
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.search(
                        query,
                        quality = qualityStore.playbackQuality().value,
                        source = TencentMusicApi.SEARCH_SOURCE_KUWO,
                    ) { partial ->
                        // 服务端逐行下发，这里收到一首就渲染一首。切回主线程赋值，
                        // 并再次校验代次：期间用户可能已经发起了新搜索。
                        scope.launch { if (requestGeneration == generation) results = dedupeSongs(partial) }
                    }
                }
            }
            // 请求返回后再次校验代次：期间用户可能已经发起新搜索或退出搜索页，旧结果不应覆盖新状态。
            if (requestGeneration != generation) return@launch
            result
                .onSuccess { found ->
                    results = dedupeSongs(found.songs)
                    hasMore = found.hasMore
                    total = found.total
                    mergeFavorites(found.songs)
                }
                .onFailure { error = it.message ?: "搜索失败，请稍后重试" }
            isSearching = false
        }
    }

    /**
     * 滚到底时拉下一页。
     *
     * 累加时的基线 [base] 在请求前就取定：`authorized()` 遇到 401 会重放整个请求、
     * 把这一页的 NDJSON 从头再读一遍，回调给的是**本页**的累积快照，
     * 所以必须每次都用 `base + partial` 而不是往当前列表上追加，否则重放会产出重复条目。
     */
    fun loadMore() {
        val query = keyword.trim()
        if (query.isBlank() || isSearching || isLoadingMore || !hasMore) return
        val requestGeneration = generation
        val nextPage = page + 1
        val base = results
        isLoadingMore = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.search(
                        query,
                        page = nextPage,
                        quality = qualityStore.playbackQuality().value,
                        source = TencentMusicApi.SEARCH_SOURCE_KUWO,
                    ) { partial ->
                        scope.launch { if (requestGeneration == generation) results = dedupeSongs(base + partial) }
                    }
                }
            }
            if (requestGeneration != generation) return@launch
            result
                .onSuccess { found ->
                    results = dedupeSongs(base + found.songs)
                    page = nextPage
                    // 上游可能给出空页却仍然说 hasMore，那样会无限拉；空页直接收口。
                    hasMore = found.hasMore && found.songs.isNotEmpty()
                    total = found.total
                    mergeFavorites(found.songs)
                }
                .onFailure { onMessage(it.message ?: "加载更多失败") }
            isLoadingMore = false
        }
    }

    // ---- 搜索历史 ----

    fun onHistoryClick(value: String) {
        keyword = value
        historyStore.add(value)
        history = historyStore.read()
    }

    fun removeHistory(value: String) {
        historyStore.remove(value)
        history = historyStore.read()
    }

    fun clearHistory() {
        historyStore.clear()
        history = emptyList()
    }

    fun onHistoryEnabledChanged(enabled: Boolean) {
        historyEnabled = enabled
        historyStore.setEnabled(enabled)
    }

    fun replaceHistory(reordered: List<String>) {
        historyStore.replaceAll(reordered)
        history = historyStore.read()
    }

    // ---- 联想与热搜（由副作用层在关键词/页面变化时调用）----

    /** 搜索页打开时刷新热搜。失败时保持空列表，不能让推荐接口阻断正常搜索。 */
    fun refreshHotSearches() {
        scope.launch {
            hotSearches = withContext(Dispatchers.IO) {
                runCatching { musicApi.hotSearch() }.getOrDefault(emptyList())
            }
        }
    }

    /** 输入停止 250ms 后请求联想；调用方的 LaunchedEffect 会自动取消上一关键词的在途协程。 */
    suspend fun refreshSuggestions(pageOpen: Boolean) {
        if (!pageOpen || keyword.isBlank()) {
            suggestions = emptyList()
            return
        }
        delay(250L)
        val requested = keyword.trim()
        suggestions = withContext(Dispatchers.IO) {
            runCatching { musicApi.searchSuggestions(requested) }.getOrDefault(emptyList())
        }.takeIf { keyword.trim() == requested } ?: emptyList()
    }

    /** 把一页结果里的收藏状态同步进本地缓存。服务端给的是权威值。 */
    private suspend fun mergeFavorites(songs: List<Song>) {
        withContext(Dispatchers.IO) { favoritesStore.merge(songs) }
        onFavoritesTouched()
    }
}
