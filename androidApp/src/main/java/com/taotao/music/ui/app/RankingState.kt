package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.QualityStore
import com.taotao.music.data.RankingBrief
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
 * 排行榜域状态：榜单目录（按模块分组）与榜单详情两个视图。
 *
 * 页面是扁平状态机的下级页：整页开关由全局状态的 `showRankingPage` 持有，
 * [selected] 非空即详情视图打开，返回键或顶栏返回把它置回 null（回目录）。
 * 打开详情复用目录里的 [RankingBrief] —— 点击源手里就是它，详情请求返回前
 * 先用它的名字 / 封面 / 更新日期渲染头部，避免整页空等；详情到达后以详情为准。
 *
 * 两条接口都是整包 JSON 响应（不是 NDJSON 流），`authorized()` 的 401 重放只会
 * 产出一份完整结果，不存在搜索页那种「重放导致回调累积」的问题；榜单歌曲固定
 * 约 20 首且无分页，所以这里没有 loadMore 一族方法，错误一律内联展示而非弹全局提示。
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
    /** 榜单详情资料；到达前头部用 [selected]，到达后以它为准（封面 / 日期可能被运营更新）。 */
    var detail by mutableStateOf<RankingInfo?>(null)
    var detailLoading by mutableStateOf(false)
    var detailError by mutableStateOf<String?>(null)
    var songs by mutableStateOf(emptyList<Song>())

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
        detailLoading = false
        detailError = null
        songs = emptyList()
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
     * 打开榜单详情：先用目录卡带来的 brief 渲染头部，再拉完整榜单。
     * 每次打开都递增代次 —— 在途的旧榜单响应（含快速换榜、详情重试）全部作废。
     */
    fun openRankingDetail(brief: RankingBrief) {
        epoch += 1
        val currentEpoch = epoch
        selected = brief
        detail = null
        detailLoading = true
        detailError = null
        songs = emptyList()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    musicApi.rankingSongs(id = brief.id, quality = qualityStore.playbackQuality().value)
                }
            }
            if (currentEpoch != epoch) return@launch
            result
                .onSuccess { loaded ->
                    detail = loaded.ranking
                    // 目录保证不了上游不吐重复行，统一按歌曲键判重，LazyColumn 的 key 才不会撞车。
                    songs = dedupeSongs(loaded.songs)
                }
                .onFailure { detailError = it.message ?: "榜单歌曲加载失败" }
            detailLoading = false
        }
    }

    /** 详情失败后的重试：用当前选中的 brief 原样重拉；目录视图没有选中项，忽略。 */
    fun retry() {
        val target = selected ?: return
        openRankingDetail(target)
    }

    /** 关闭详情（回目录）：递增代次作废在途详情请求，并清空详情视图的全部数据。 */
    fun closeDetail() {
        epoch += 1
        selected = null
        detail = null
        detailLoading = false
        detailError = null
        songs = emptyList()
    }

    /** 关闭整页：目录与详情一并清零（含加载态），下次打开重新拉目录，不留陈旧数据。 */
    fun close() {
        epoch += 1
        selected = null
        detail = null
        detailLoading = false
        detailError = null
        songs = emptyList()
        groups = emptyList()
        catalogLoading = false
        catalogError = null
        catalogLoaded = false
    }
}
