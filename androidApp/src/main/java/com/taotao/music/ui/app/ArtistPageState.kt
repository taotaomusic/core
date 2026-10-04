package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.AlbumSearchResult
import com.taotao.music.data.ArtistDetail
import com.taotao.music.data.ArtistSearchResult
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
 * 歌手主页域状态：详情、歌曲 / 专辑 / 相似三个区块与各自分页。
 *
 * 页面是扁平状态机的下级页：[selected] 非空即打开，返回键或关闭动作把它置回 null。
 * 打开目标复用 [ArtistSearchResult] —— 点击源（搜索区块、相似歌手列表）手里的就是它，
 * 详情请求返回前先用它的 name / pic 渲染头部，避免整页空等；搜索时的统计数与详情
 * 可能不同步，以详情为准（详情未到前不显示统计行，防止展示过期数字）。
 *
 * 这些接口都是整包 JSON 响应（不是 NDJSON 流），`authorized()` 的 401 重放只会
 * 产出一份完整结果，所以不存在搜索页那种「重放导致回调累积」的问题；
 * 分页仍沿用「请求前取基线快照、回包后整页拼接」的写法，与 [SearchState.loadMore] 一致。
 */
internal class ArtistPageState(
    private val scope: CoroutineScope,
    private val musicApi: TencentMusicApi,
    private val qualityStore: QualityStore,
    private val onMessage: (String) -> Unit,
) {
    /** 当前打开的歌手；null 表示页面关闭。详情到达前头部先用它渲染。 */
    var selected by mutableStateOf<ArtistSearchResult?>(null)
    var artist by mutableStateOf<ArtistDetail?>(null)
    var detailLoading by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)

    var songs by mutableStateOf(emptyList<Song>())
    var songsLoading by mutableStateOf(false)
    var songsLoadingMore by mutableStateOf(false)
    var songsPage by mutableIntStateOf(1)
    var songsHasMore by mutableStateOf(false)
    var songsError by mutableStateOf<String?>(null)

    var albums by mutableStateOf(emptyList<AlbumSearchResult>())
    var albumsLoading by mutableStateOf(false)
    var albumsLoadingMore by mutableStateOf(false)
    var albumsPage by mutableIntStateOf(1)
    var albumsHasMore by mutableStateOf(false)
    /** 专辑区块只在首次切到该标签时拉取；失败时保持 false，切回标签会自动重试。 */
    var albumsLoaded by mutableStateOf(false)

    var similar by mutableStateOf(emptyList<ArtistSearchResult>())
    var similarLoading by mutableStateOf(false)
    /** 相似歌手没有分页，拉过一次就不再重复请求；失败时保持 false 以便下次切标签重试。 */
    var similarLoaded by mutableStateOf(false)

    /**
     * 页面代次：每次打开 / 切换歌手（含点相似歌手原地替换目标）与重试都递增。
     * 详情与歌曲是两路并行请求，各自捕获发起时刻的代次，回包时校验；
     * 旧目标的响应一律拒绝回写 —— 不校验的话，切歌手瞬间旧歌手的歌曲会闪进新歌手的列表。
     */
    private var epoch by mutableIntStateOf(0)

    /** 打开歌手主页：重置全部区块并加载详情与第一页歌曲。 */
    fun open(target: ArtistSearchResult) {
        selected = target
        resetCore()
        resetAlbums()
        resetSimilar()
        loadCore(target)
    }

    /**
     * 详情或歌曲首页失败后的重试：只重拉详情与第一页歌曲。
     * 刻意不复用 [open] 整体重置 —— 用户可能已经加载过专辑 / 相似区块，
     * 重试不该把它们一并清掉重新等网络。
     */
    fun retry() {
        val target = selected ?: return
        resetCore()
        loadCore(target)
    }

    /** 详情与歌曲第一页是页面的核心内容，打开与重试共用这一段装载逻辑。 */
    private fun loadCore(target: ArtistSearchResult) {
        epoch += 1
        val currentEpoch = epoch
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { musicApi.artistDetail(target.id) }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { artist = it }
                .onFailure { detailError = it.message ?: "歌手资料加载失败" }
            detailLoading = false
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.artistSongs(artistId = target.id, quality = qualityStore.playbackQuality().value)
                }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { page -> applyFirstSongPage(page) }
                .onFailure { songsError = it.message ?: "歌曲加载失败" }
            songsLoading = false
        }
    }

    /** 滚到「歌曲」列表近底部时拉下一页；基线在请求前取定，防 401 重放或并发回写产生重复。 */
    fun loadMoreSongs() {
        val target = selected ?: return
        if (songsLoading || songsLoadingMore || !songsHasMore) return
        val currentEpoch = epoch
        val nextPage = songsPage + 1
        val base = songs
        songsLoadingMore = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.artistSongs(artistId = target.id, page = nextPage, quality = qualityStore.playbackQuality().value)
                }
            }
            if (currentEpoch != epoch) return@launch
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

    /** 首次切到「专辑」标签时拉取第一页；已加载或正在加载则跳过，切回标签不重复请求。 */
    fun ensureAlbums() {
        val target = selected ?: return
        if (albumsLoaded || albumsLoading) return
        val currentEpoch = epoch
        albumsLoading = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { musicApi.artistAlbums(artistId = target.id) }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { page ->
                    albums = page.items
                    albumsPage = page.page
                    albumsHasMore = page.hasMore && page.items.isNotEmpty()
                    albumsLoaded = true
                }
                .onFailure { onMessage(it.message ?: "专辑加载失败") }
            albumsLoading = false
        }
    }

    /** 专辑区块滚到近底部时拉下一页，口径与 [loadMoreSongs] 相同。 */
    fun loadMoreAlbums() {
        val target = selected ?: return
        if (albumsLoading || albumsLoadingMore || !albumsHasMore) return
        val currentEpoch = epoch
        val nextPage = albumsPage + 1
        val base = albums
        albumsLoadingMore = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.artistAlbums(artistId = target.id, page = nextPage)
                }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { page ->
                    albums = base + page.items
                    albumsPage = nextPage
                    albumsHasMore = page.hasMore && page.items.isNotEmpty()
                }
                .onFailure { onMessage(it.message ?: "加载更多失败") }
            albumsLoadingMore = false
        }
    }

    /** 首次切到「相似」标签时拉取；一次返回全部，没有分页。 */
    fun ensureSimilar() {
        val target = selected ?: return
        if (similarLoaded || similarLoading) return
        val currentEpoch = epoch
        similarLoading = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { musicApi.similarArtists(target.id) }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { artists ->
                    similar = artists
                    similarLoaded = true
                }
                .onFailure { onMessage(it.message ?: "相似歌手加载失败") }
            similarLoading = false
        }
    }

    /** 关闭页面：清目标与全部区块数据（加载态一并复位），并递增代次让在途请求的响应作废。 */
    fun close() {
        epoch += 1
        selected = null
        artist = null
        detailLoading = false
        detailError = null
        songs = emptyList()
        songsLoading = false
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
        resetAlbums()
        resetSimilar()
    }

    /**
     * 把核心内容（详情 + 歌曲第一页）复位成「即将重新加载」的初始态。
     * 只服务于 [open] 与 [retry]；[close] 不能用它 —— 关页应当把加载态归零，
     * 而不是留着 true 等下一次打开覆盖。
     */
    private fun resetCore() {
        artist = null
        detailLoading = true
        detailError = null
        songs = emptyList()
        songsLoading = true
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
    }

    private fun resetAlbums() {
        albums = emptyList()
        albumsLoading = false
        albumsLoadingMore = false
        albumsPage = 1
        albumsHasMore = false
        albumsLoaded = false
    }

    private fun resetSimilar() {
        similar = emptyList()
        similarLoading = false
        similarLoaded = false
    }

    private fun applyFirstSongPage(page: PagedList<Song>) {
        songs = dedupeSongs(page.items)
        songsPage = page.page
        songsHasMore = page.hasMore && page.items.isNotEmpty()
    }
}
