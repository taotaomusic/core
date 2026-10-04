package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.AlbumDetail
import com.taotao.music.data.AlbumSearchResult
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
 * 专辑页域状态：详情与收录歌曲的分页。
 *
 * 与 [ArtistPageState] 同一套骨架但少两个区块（专辑页只有歌曲一个列表）。
 * 打开目标复用 [AlbumSearchResult]：点击源（搜索区块、歌手页专辑卡片）手里的就是它，
 * 详情返回前先用它的封面 / 名字 / 歌手 / 发行日期渲染头部，详情到达后再补权威值与简介。
 */
internal class AlbumPageState(
    private val scope: CoroutineScope,
    private val musicApi: TencentMusicApi,
    private val qualityStore: QualityStore,
    private val onMessage: (String) -> Unit,
) {
    /** 当前打开的专辑；null 表示页面关闭。详情到达前头部先用它渲染。 */
    var selected by mutableStateOf<AlbumSearchResult?>(null)
    var album by mutableStateOf<AlbumDetail?>(null)
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
     * 回包时校验，旧目标的响应一律拒绝回写（理由见 [ArtistPageState.epoch]）。
     */
    private var epoch by mutableIntStateOf(0)

    /** 打开专辑页：重置全部状态并加载详情与第一页歌曲。 */
    fun open(target: AlbumSearchResult) {
        selected = target
        epoch += 1
        album = null
        detailLoading = true
        detailError = null
        songs = emptyList()
        songsLoading = true
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
        val currentEpoch = epoch
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { musicApi.albumDetail(target.id) }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { album = it }
                .onFailure { detailError = it.message ?: "专辑资料加载失败" }
            detailLoading = false
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.albumSongs(albumId = target.id, quality = qualityStore.playbackQuality().value)
                }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { page -> applyFirstSongPage(page) }
                .onFailure { songsError = it.message ?: "歌曲加载失败" }
            songsLoading = false
        }
    }

    /** 详情或歌曲首页失败后的重试：专辑页只有这两路，直接整体重开即可。 */
    fun retry() {
        val target = selected ?: return
        open(target)
    }

    /** 滚到歌曲列表近底部时拉下一页；口径与 [ArtistPageState.loadMoreSongs] 相同。 */
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
                    musicApi.albumSongs(albumId = target.id, page = nextPage, quality = qualityStore.playbackQuality().value)
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

    /** 关闭页面：清目标与全部数据，并递增代次让在途请求的响应作废。 */
    fun close() {
        epoch += 1
        selected = null
        album = null
        detailLoading = false
        detailError = null
        songs = emptyList()
        songsLoading = false
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
    }

    private fun applyFirstSongPage(page: PagedList<Song>) {
        songs = dedupeSongs(page.items)
        songsPage = page.page
        songsHasMore = page.hasMore && page.items.isNotEmpty()
    }
}
