package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.AlbumSearchResult
import com.taotao.music.data.ArtistSearchResult
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
 * 搜索结果页的四个标签。
 *
 * 「综合」是原有布局（歌手 / 专辑区块 + 歌曲列表）；其余三个标签各看一类数据。
 * 标签本身是纯界面状态，但「歌手 / 专辑」标签的分页数据必须在状态层持有 ——
 * 它们有独立于歌曲的翻页、去重与代次校验。
 */
internal enum class SearchTab(val label: String) {
    OVERVIEW("综合"),
    SONGS("单曲"),
    ARTISTS("歌手"),
    ALBUMS("专辑"),
}

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
    /**
     * 搜索命中的歌手 / 专辑区块（仅酷我音源下发）。
     * 每次新搜索整体替换而不是追加；第 2 页起服务端不再下发，翻页不会改写它们。
     */
    var searchArtists by mutableStateOf(emptyList<ArtistSearchResult>())
    var searchAlbums by mutableStateOf(emptyList<AlbumSearchResult>())

    /** 当前结果标签；新搜索发起时回到「综合」。 */
    var searchTab by mutableStateOf(SearchTab.OVERVIEW)
        private set

    // ---- 「歌手」标签的分页状态：写法与上面的歌曲分页同口径（代次 + 基线快照拼接 + 空页收口）----
    var tabArtists by mutableStateOf(emptyList<ArtistSearchResult>())
    var tabArtistsLoading by mutableStateOf(false)
    var tabArtistsLoadingMore by mutableStateOf(false)
    var tabArtistsPage by mutableIntStateOf(1)
    var tabArtistsHasMore by mutableStateOf(false)
    /** 该标签首屏失败时给出的占位文案；成功后清空。 */
    var tabArtistsError by mutableStateOf<String?>(null)
    /** 首屏是否已成功拉过；失败时保持 false，重新进标签会自动重试。 */
    var tabArtistsLoaded by mutableStateOf(false)
    /** 名下歌手总数（服务端 meta.total），只用于尾部提示。 */
    var tabArtistsTotal by mutableIntStateOf(0)

    // ---- 「专辑」标签的分页状态：字段口径与 [tabArtists] 完全一致 ----
    var tabAlbums by mutableStateOf(emptyList<AlbumSearchResult>())
    var tabAlbumsLoading by mutableStateOf(false)
    var tabAlbumsLoadingMore by mutableStateOf(false)
    var tabAlbumsPage by mutableIntStateOf(1)
    var tabAlbumsHasMore by mutableStateOf(false)
    var tabAlbumsError by mutableStateOf<String?>(null)
    var tabAlbumsLoaded by mutableStateOf(false)
    var tabAlbumsTotal by mutableIntStateOf(0)

    /** 打开搜索页时重置结果区；页面开关本身由全局状态管理。 */
    fun openPage() {
        generation += 1
        results = emptyList()
        searchArtists = emptyList()
        searchAlbums = emptyList()
        hasSearched = false
        isSearching = false
        error = null
        page = 1
        hasMore = false
        total = 0
        isLoadingMore = false
        searchTab = SearchTab.OVERVIEW
        resetTabResults()
    }

    /** 输入框每次变化：更新关键词并清掉上一次的结果与错误。 */
    fun onKeywordInput(value: String) {
        keyword = value
        hasSearched = false
        results = emptyList()
        searchArtists = emptyList()
        searchAlbums = emptyList()
        error = null
        resetTabResults()
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
            searchArtists = emptyList()
            searchAlbums = emptyList()
            page = 1
            hasMore = false
            // 新搜索把标签带回「综合」并清空歌手 / 专辑标签的数据，
            // 旧关键词的标签结果不能混进新一轮搜索的标签里。
            searchTab = SearchTab.OVERVIEW
            resetTabResults()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.search(
                        query,
                        quality = qualityStore.playbackQuality().value,
                        source = TencentMusicApi.SEARCH_SOURCE_KUWO,
                        // 歌手 / 专辑区块在所有歌曲行之前一次性回调。与歌曲一样整体替换，
                        // 401 重放时旧内容会被同样的新内容覆盖，不会累积重复。
                        onSections = { foundArtists, foundAlbums ->
                            scope.launch {
                                if (requestGeneration == generation) {
                                    searchArtists = foundArtists
                                    searchAlbums = foundAlbums
                                }
                            }
                        },
                        // 进度回调必须点名传递：search 的**最后一个**参数是 onSections，
                        // 尾随 lambda 会绑定到它而不是 onProgress。
                        onProgress = { partial ->
                            // 服务端逐行下发，这里收到一首就渲染一首。切回主线程赋值，
                            // 并再次校验代次：期间用户可能已经发起了新搜索。
                            scope.launch { if (requestGeneration == generation) results = dedupeSongs(partial) }
                        },
                    )
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
                        // 翻页刻意不接 onSections：区块只属于第 1 页，第 2 页起服务端不再
                        // 下发，默认回调（空列表）反而会把第 1 页已经渲染的区块清掉。
                        // 进度回调同样点名传递，理由见 [start]。
                        onProgress = { partial ->
                            scope.launch { if (requestGeneration == generation) results = dedupeSongs(base + partial) }
                        },
                    )
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

    // ---- 结果标签（歌手 / 专辑的分页搜索）----

    /**
     * 切换结果标签。「歌手 / 专辑」标签在首次进入时触发首屏拉取；失败后 [tabArtistsLoaded] /
     * [tabAlbumsLoaded] 保持 false，切走再切回来就是重试。
     */
    fun selectTab(tab: SearchTab) {
        if (searchTab == tab) return
        searchTab = tab
        when (tab) {
            SearchTab.ARTISTS -> ensureTabArtists()
            SearchTab.ALBUMS -> ensureTabAlbums()
            SearchTab.OVERVIEW, SearchTab.SONGS -> Unit
        }
    }

    /** 「歌手」标签首屏：已加载或正在加载则跳过，保证切回标签不会重复请求。 */
    fun ensureTabArtists() {
        val query = keyword.trim()
        if (query.isBlank() || tabArtistsLoaded || tabArtistsLoading) return
        val requestGeneration = generation
        tabArtistsLoading = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { musicApi.searchArtistsPaged(query) }
            }
            // 校验代次：期间用户可能已发起新搜索，旧关键词的歌手不能写进新搜索的标签。
            if (requestGeneration != generation) return@launch
            result
                .onSuccess { page ->
                    tabArtists = dedupeTabArtists(page.items)
                    tabArtistsPage = page.page
                    tabArtistsHasMore = page.hasMore && page.items.isNotEmpty()
                    tabArtistsTotal = page.total.toInt()
                    tabArtistsError = null
                    tabArtistsLoaded = true
                }
                .onFailure { tabArtistsError = it.message ?: "歌手搜索失败" }
            tabArtistsLoading = false
        }
    }

    /** 「歌手」标签滚到近底部时拉下一页；基线快照与空页收口的理由见 [loadMore]。 */
    fun loadMoreTabArtists() {
        val query = keyword.trim()
        if (query.isBlank() || tabArtistsLoading || tabArtistsLoadingMore || !tabArtistsHasMore) return
        val requestGeneration = generation
        val nextPage = tabArtistsPage + 1
        val base = tabArtists
        tabArtistsLoadingMore = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.searchArtistsPaged(keyword = query, page = nextPage)
                }
            }
            if (requestGeneration != generation) return@launch
            result
                .onSuccess { page ->
                    tabArtists = dedupeTabArtists(base + page.items)
                    tabArtistsPage = nextPage
                    tabArtistsHasMore = page.hasMore && page.items.isNotEmpty()
                    tabArtistsTotal = page.total.toInt()
                }
                .onFailure { onMessage(it.message ?: "加载更多失败") }
            tabArtistsLoadingMore = false
        }
    }

    /** 「专辑」标签首屏，口径与 [ensureTabArtists] 相同。 */
    fun ensureTabAlbums() {
        val query = keyword.trim()
        if (query.isBlank() || tabAlbumsLoaded || tabAlbumsLoading) return
        val requestGeneration = generation
        tabAlbumsLoading = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { musicApi.searchAlbumsPaged(query) }
            }
            if (requestGeneration != generation) return@launch
            result
                .onSuccess { page ->
                    tabAlbums = dedupeTabAlbums(page.items)
                    tabAlbumsPage = page.page
                    tabAlbumsHasMore = page.hasMore && page.items.isNotEmpty()
                    tabAlbumsTotal = page.total.toInt()
                    tabAlbumsError = null
                    tabAlbumsLoaded = true
                }
                .onFailure { tabAlbumsError = it.message ?: "专辑搜索失败" }
            tabAlbumsLoading = false
        }
    }

    /** 「专辑」标签滚到近底部时拉下一页，口径与 [loadMoreTabArtists] 相同。 */
    fun loadMoreTabAlbums() {
        val query = keyword.trim()
        if (query.isBlank() || tabAlbumsLoading || tabAlbumsLoadingMore || !tabAlbumsHasMore) return
        val requestGeneration = generation
        val nextPage = tabAlbumsPage + 1
        val base = tabAlbums
        tabAlbumsLoadingMore = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.searchAlbumsPaged(keyword = query, page = nextPage)
                }
            }
            if (requestGeneration != generation) return@launch
            result
                .onSuccess { page ->
                    tabAlbums = dedupeTabAlbums(base + page.items)
                    tabAlbumsPage = nextPage
                    tabAlbumsHasMore = page.hasMore && page.items.isNotEmpty()
                    tabAlbumsTotal = page.total.toInt()
                }
                .onFailure { onMessage(it.message ?: "加载更多失败") }
            tabAlbumsLoadingMore = false
        }
    }

    /** 把「歌手 / 专辑」标签的分页数据复位成初始态。新搜索与开关页面时调用。 */
    private fun resetTabResults() {
        tabArtists = emptyList()
        tabArtistsLoading = false
        tabArtistsLoadingMore = false
        tabArtistsPage = 1
        tabArtistsHasMore = false
        tabArtistsError = null
        tabArtistsLoaded = false
        tabArtistsTotal = 0
        tabAlbums = emptyList()
        tabAlbumsLoading = false
        tabAlbumsLoadingMore = false
        tabAlbumsPage = 1
        tabAlbumsHasMore = false
        tabAlbumsError = null
        tabAlbumsLoaded = false
        tabAlbumsTotal = 0
    }

    /**
     * 歌手标签按「音源 + ID」判重：上游翻页边界可能吐出重复行，拼接后统一收口，
     * 否则内容 key 会撞车。同名同 ID 只保留先到的。
     */
    private fun dedupeTabArtists(items: List<ArtistSearchResult>): List<ArtistSearchResult> {
        val seen = HashSet<String>()
        return items.filter { seen.add("${it.source}#${it.id}") }
    }

    /** 专辑标签的判重，口径同 [dedupeTabArtists]。 */
    private fun dedupeTabAlbums(items: List<AlbumSearchResult>): List<AlbumSearchResult> {
        val seen = HashSet<String>()
        return items.filter { seen.add("${it.source}#${it.id}") }
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
