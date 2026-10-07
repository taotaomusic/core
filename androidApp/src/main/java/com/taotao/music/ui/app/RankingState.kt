package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.QualityStore
import com.taotao.music.data.RankingBrief
import com.taotao.music.data.RankingDetail
import com.taotao.music.data.RankingGroup
import com.taotao.music.data.RankingInfo
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.ui.common.dedupeSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 榜单歌曲的分页大小。状态层发请求与界面换算全局名次必须用同一个值，
 * 否则「名次 = (页码 - 1) × 每页条数 + 页内下标 + 1」会整体错位；
 * 取值与服务端 `/rankings/:id/songs` 的 num 缺省值一致（服务端上限 100）。
 */
internal const val RANKING_SONGS_PAGE_NUM = 30

/**
 * 排行榜域状态：榜单目录（按模块分组）与榜单详情两个视图。
 *
 * 页面是扁平状态机的下级页：整页开关由全局状态的 `showRankingPage` 持有，
 * [selected] 非空即详情视图打开，返回键或顶栏返回把它置回 null（回目录）。
 * 打开详情复用目录里的 [RankingBrief] —— 点击源手里就是它，详情请求返回前
 * 先用它的名字 / 封面 / 更新日期渲染头部，避免整页空等；详情到达后以详情为准。
 *
 * 两条接口都是整包 JSON 响应（不是 NDJSON 流），`authorized()` 的 401 重放只会
 * 产出一份完整结果，不存在搜索页那种「重放导致回调累积」的问题。榜单详情是
 * 分页接口：头部资料（ranking）与歌曲第一页同请求同成败，之后由 [loadMoreSongs]
 * 按基线快照逐页累积；错误一律内联展示而非弹全局提示（第一页失败在列表位置、
 * 加载更多失败在页脚，按 [songs] 是否为空区分）。
 */
internal class RankingState(
    private val scope: CoroutineScope,
    private val musicApi: TencentMusicApi,
    private val qualityStore: QualityStore,
) {
    /** 榜单目录：按模块（置顶位 / 热力榜 / 全球榜 / 特色榜等）分组。 */
    var groups by mutableStateOf(emptyList<RankingGroup>())
    var catalogLoading by mutableStateOf(false)
    var catalogError by mutableStateOf<String?>(null)

    /**
     * 目录是否已成功拉过。普通 Boolean 即可：它只决定「要不要再发一次请求」，
     * 不直接驱动界面重组（界面读的是 groups / catalogLoading / catalogError）。
     */
    private var catalogLoaded = false

    /** 当前打开的榜单；null 表示在目录视图。详情到达前头部先用它兜底渲染。 */
    var selected by mutableStateOf<RankingBrief?>(null)
    /** 榜单详情头资料；到达前头部用 [selected]，到达后以它为准（封面 / 日期可能被运营更新）。 */
    var detail by mutableStateOf<RankingInfo?>(null)

    /** 已累积的榜单歌曲（第一页起逐页拼接，键判重后的结果）。 */
    var songs by mutableStateOf(emptyList<Song>())
    /** 歌曲第一页加载中；头部资料与第一页是同一个请求，见 [openRankingDetail]。 */
    var songsLoading by mutableStateOf(false)
    var songsLoadingMore by mutableStateOf(false)
    /** 已成功装载的最新页码（1 基）；名次换算与下一页请求都基于它推进。 */
    var songsPage by mutableIntStateOf(1)
    var songsHasMore by mutableStateOf(false)

    /**
     * 列表内联错误：第一页失败（[songs] 为空，在列表位置给重试）与加载更多失败
     * （[songs] 非空，在页脚给重试）共用一个字段，两种情形不会同时出现 ——
     * 第一页失败时 songs 必为空，装载成功后它只可能来自加载更多。
     */
    var songsError by mutableStateOf<String?>(null)

    /** 榜单真实条数（meta.total，主流榜单恒 100）；详情头部「共 N 首」用它。 */
    var songsTotal by mutableLongStateOf(0L)

    /**
     * 页面代次：目录、详情两路请求共用一个代次计数，开关整页、开关详情与重试都递增；
     * 请求捕获发起时刻的代次，回包时校验，旧响应一律拒绝回写 —— 不校验的话，
     * 快速连点两个榜单时前一个榜单的歌曲会闪进后一个榜单的列表。
     */
    private var epoch by mutableIntStateOf(0)

    /** 打开整页（进目录视图）：作废残留的详情态与在途请求，并按需拉取目录。 */
    fun openRankingPage() {
        epoch += 1
        selected = null
        detail = null
        clearSongs()
        ensureCatalog()
    }

    /**
     * 拉取榜单目录：已加载或正在加载则跳过；失败后 [catalogLoaded] 保持 false，
     * 目录页的重试按钮与下次打开页面都会再走到这里重新请求。
     */
    fun ensureCatalog() {
        if (catalogLoaded || catalogLoading) return
        val currentEpoch = epoch
        catalogLoading = true
        catalogError = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { musicApi.rankings() } }
            // 目录与详情共用代次：期间若整页被关闭（epoch 已变），旧响应不得覆盖清空后的状态。
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { fetched ->
                    groups = fetched
                    catalogLoaded = true
                }
                .onFailure { catalogError = it.message ?: "排行榜加载失败" }
            catalogLoading = false
        }
    }

    /**
     * 打开榜单详情：先用目录卡带来的 brief 渲染头部，再拉头部资料与歌曲第一页
     * （两者是同一个请求、同一份成败）。每次打开都递增代次 —— 在途的旧榜单响应
     * （含快速换榜、详情重试）全部作废。
     */
    fun openRankingDetail(brief: RankingBrief) {
        epoch += 1
        val currentEpoch = epoch
        selected = brief
        detail = null
        resetSongsForReload()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.rankingSongs(
                        id = brief.id,
                        page = 1,
                        num = RANKING_SONGS_PAGE_NUM,
                        quality = qualityStore.playbackQuality().value,
                    )
                }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { loaded ->
                    detail = loaded.ranking
                    applyFirstSongPage(loaded)
                }
                .onFailure { songsError = it.message ?: "榜单歌曲加载失败" }
            songsLoading = false
        }
    }

    /** 滚到榜单歌曲列表近底部时拉下一页；基线快照在请求前取定，回包后整体拼接判重。 */
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
                    musicApi.rankingSongs(
                        id = target.id,
                        page = nextPage,
                        num = RANKING_SONGS_PAGE_NUM,
                        quality = qualityStore.playbackQuality().value,
                    )
                }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { loaded ->
                    // 基线快照 + 键判重：401 重放、并发回包都只会产出一份完整结果，
                    // 这里再兜一层，LazyColumn 的 key 才不会撞车。
                    songs = dedupeSongs(base + loaded.songs)
                    songsPage = nextPage
                    // 上游可能给出空页却仍然说 hasMore；空页直接收口，避免无限拉取。
                    songsHasMore = loaded.hasMore && loaded.songs.isNotEmpty()
                    songsTotal = loaded.total
                    songsError = null
                }
                .onFailure { songsError = it.message ?: "加载更多失败" }
            songsLoadingMore = false
        }
    }

    /** 详情失败后的重试：用当前选中的 brief 原样重拉第一页；目录视图没有选中项，忽略。 */
    fun retry() {
        val target = selected ?: return
        openRankingDetail(target)
    }

    /** 关闭详情（回目录）：递增代次作废在途详情请求，并清空详情视图的全部数据。 */
    fun closeDetail() {
        epoch += 1
        selected = null
        detail = null
        clearSongs()
    }

    /** 关闭整页：目录与详情一并清零（含加载态），下次打开重新拉目录，不留陈旧数据。 */
    fun close() {
        epoch += 1
        selected = null
        detail = null
        clearSongs()
        groups = emptyList()
        catalogLoading = false
        catalogError = null
        catalogLoaded = false
    }

    /**
     * 把歌曲第一页装载进分页状态。空页收口（hasMore 必须伴随非空条目）照
     * ArtistPageState 的同一口径；[RankingDetail.total] 是 meta 里的真实条数，
     * 覆盖写即可 —— 每一页回包都带同一份 meta.total。
     */
    private fun applyFirstSongPage(loaded: RankingDetail) {
        songs = dedupeSongs(loaded.songs)
        songsPage = loaded.page
        songsHasMore = loaded.hasMore && loaded.songs.isNotEmpty()
        songsTotal = loaded.total
    }

    /** 把歌曲分页状态复位成「即将重新装载第一页」：打开详情与重试共用这一段清场。 */
    private fun resetSongsForReload() {
        songs = emptyList()
        songsLoading = true
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
        songsTotal = 0L
    }

    /** 把歌曲分页状态归零（关闭详情 / 整页时的清场）：加载态一并复位，不留 true。 */
    private fun clearSongs() {
        songs = emptyList()
        songsLoading = false
        songsLoadingMore = false
        songsPage = 1
        songsHasMore = false
        songsError = null
        songsTotal = 0L
    }
}
