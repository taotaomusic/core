package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.OnlinePlaylistDetail
import com.taotao.music.data.PlaylistSearchResult
import com.taotao.music.data.PagedList
import com.taotao.music.data.QualityStore
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.ui.common.dedupeSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在线歌单页域状态：详情与收录歌曲的分页。
 *
 * 与 [AlbumPageState] 同一套骨架但寻址方式不同：专辑按音源内 ID 寻址，在线歌单要多带
 * 搜索行下发的 sourceMarker（上游数字标记，客户端原样回传）。打开目标先用
 * [PlaylistSearchResult] 拼一份最小详情垫进 [selected]，头部立即可渲染；
 * 详情到达后整体替换为权威值，简介与收藏数等搜索行没有的字段随之补齐。
 *
 * 这些接口都是整包 JSON 响应（不是 NDJSON 流），`authorized()` 的 401 重放只会
 * 产出一份完整结果，不存在搜索页那种「重放导致回调累积」的问题；分页沿用
 * 「请求前取基线快照、回包后整页拼接」的写法，与 [SearchState.loadMore] 一致。
 */
internal class OnlinePlaylistPageState(
    private val scope: CoroutineScope,
    private val musicApi: TencentMusicApi,
    private val qualityStore: QualityStore,
    private val onMessage: (String) -> Unit,
) {
    /**
     * 当前打开的在线歌单；null 表示页面关闭。
     * 打开瞬间是搜索行拼的最小详情（仅头部兜底资料），详情请求成功后被权威值整体替换。
     */
    var selected by mutableStateOf<OnlinePlaylistDetail?>(null)
    var detailLoading by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)

    var songs by mutableStateOf(emptyList<Song>())
    var songsLoading by mutableStateOf(false)
    var songsLoadingMore by mutableStateOf(false)
    var songsPage by mutableIntStateOf(1)
    var songsHasMore by mutableStateOf(false)
    var songsError by mutableStateOf<String?>(null)

    /**
     * 页面代次：打开 / 重试递增。详情与歌曲两路并行请求各自捕获发起时刻的代次，
     * 回包时校验，旧目标的响应一律拒绝回写 —— 不校验的话，快速连点两个歌单时
     * 旧歌单的歌曲会闪进新歌单的列表（理由同 [AlbumPageState.epoch]）。
     */
    private var generation by mutableIntStateOf(0)

    /** 当前打开歌单的寻址参数：详情与歌曲接口都要原样回传。 */
    private var playlistId = 0L
    private var activeSource = TencentMusicApi.SEARCH_SOURCE_KUWO
    private var sourceMarker = 0L

    /** 打开在线歌单页：记录寻址参数、用搜索行垫一份头部资料，并加载详情与第一页歌曲。 */
    fun open(target: PlaylistSearchResult) {
        playlistId = target.id
        activeSource = target.source.ifBlank { TencentMusicApi.SEARCH_SOURCE_KUWO }
        sourceMarker = target.sourceMarker
        selected = target.toOpenTarget()
        loadCore()
    }

    /**
     * 详情或歌曲首页失败后的重试：重新拉详情与第一页歌曲。
     * 寻址参数已在 [open] 记录，不必再依赖搜索行；[selected] 仍垫着兜底头部，
     * 重试期间页面不空白。
     */
    fun retry() {
        if (playlistId <= 0L) return
        loadCore()
    }

    /** 详情与歌曲第一页是页面的核心内容，打开与重试共用这一段装载逻辑。 */
    private fun loadCore() {
        generation += 1
        val currentGeneration = generation
        val id = playlistId
        val source = activeSource
        val marker = sourceMarker
        detailLoading = true
        detailError = null
        songs = emptyList()
        songsLoading = true
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { musicApi.fetchOnlinePlaylistDetail(id, marker, source) }
            }
            if (currentGeneration != generation) return@launch
            result
                .onSuccess { selected = it }
                .onFailure { detailError = it.message ?: "歌单资料加载失败" }
            detailLoading = false
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.fetchOnlinePlaylistSongs(
                        id = id,
                        sourceMarker = marker,
                        source = source,
                        quality = qualityStore.playbackQuality().value,
                    )
                }
            }
            if (currentGeneration != generation) return@launch
            result
                .onSuccess { page -> applyFirstSongPage(page) }
                .onFailure { songsError = it.message ?: "歌曲加载失败" }
            songsLoading = false
        }
    }

    /** 滚到歌曲列表近底部时拉下一页；口径与 [AlbumPageState.loadMoreSongs] 相同。 */
    fun loadMoreSongs() {
        if (playlistId <= 0L) return
        if (songsLoading || songsLoadingMore || !songsHasMore) return
        val currentGeneration = generation
        val id = playlistId
        val source = activeSource
        val marker = sourceMarker
        val nextPage = songsPage + 1
        val base = songs
        songsLoadingMore = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.fetchOnlinePlaylistSongs(
                        id = id,
                        sourceMarker = marker,
                        source = source,
                        page = nextPage,
                        quality = qualityStore.playbackQuality().value,
                    )
                }
            }
            if (currentGeneration != generation) return@launch
            result
                .onSuccess { page ->
                    songs = dedupeSongs(base + page.items)
                    songsPage = nextPage
                    // 上游可能给出空页却仍然说 hasMore；空页直接收口，避免无限拉取。
                    songsHasMore = page.hasMore && page.items.isNotEmpty()
                }
                .onFailure { onMessage(it.message ?: "加载更多失败") }
            songsLoadingMore = false
        }
    }

    /** 关闭页面：清目标、寻址参数与全部数据，并递增代次让在途请求的响应作废。 */
    fun close() {
        generation += 1
        selected = null
        detailLoading = false
        detailError = null
        songs = emptyList()
        songsLoading = false
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
        playlistId = 0L
        activeSource = TencentMusicApi.SEARCH_SOURCE_KUWO
        sourceMarker = 0L
    }

    private fun applyFirstSongPage(page: PagedList<Song>) {
        songs = dedupeSongs(page.items)
        songsPage = page.page
        songsHasMore = page.hasMore && page.items.isNotEmpty()
    }
}

/**
 * 打开目标：搜索行手里没有的字段（简介、收藏数、创建时间等）以「缺失」默认值垫底，
 * 详情到达前头部只渲染搜索行已有的封面 / 名字 / 创建者 / 曲目数 / 播放数，
 * 不会把 0 当成权威数字展示（数据行各段只在大于 0 时出现，见在线歌单页头部）。
 */
private fun PlaylistSearchResult.toOpenTarget(): OnlinePlaylistDetail = OnlinePlaylistDetail(
    source = source.ifBlank { TencentMusicApi.SEARCH_SOURCE_KUWO },
    id = id,
    name = name,
    pic = pic,
    description = "",
    playCount = playCount,
    trackCount = trackCount,
    collectedCount = 0L,
    creatorId = 0L,
    creatorName = creator,
    creatorIcon = null,
    isPrivate = false,
)
