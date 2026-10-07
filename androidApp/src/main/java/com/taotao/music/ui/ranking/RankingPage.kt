package com.taotao.music.ui.ranking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taotao.music.data.RankingBrief
import com.taotao.music.data.RankingGroup
import com.taotao.music.data.RankingInfo
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoColors
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoWash
import com.taotao.music.ui.artist.CatalogInlineStatus
import com.taotao.music.ui.artist.CatalogLoadMoreThreshold
import com.taotao.music.ui.artist.CatalogPageBackdrop
import com.taotao.music.ui.artist.CatalogPageStatus
import com.taotao.music.ui.artist.CatalogTopBar
import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.EmptyStateView
import com.taotao.music.ui.common.SongListItem
import com.taotao.music.ui.common.songKeyOf
import com.taotao.music.ui.theme.TaotaoCoral

/**
 * 排行榜页：目录态（按模块分组的横版榜单卡）与详情态（榜单封面头部 + 歌曲列表）两个视图。
 *
 * 视图切换由 `selected` 决定（非空即详情态），页面自身不持有任何业务状态；
 * 数据与操作全部来自 [com.taotao.music.ui.app.RankingState]，由路由层注入。
 * 沉浸式头部、顶栏与状态占位复用歌手 / 专辑页的 internal 共用件（同模块跨包引用合法），
 * 歌曲行复用公共 [SongListItem]、行首带全局名次序号（前三名金 / 银 / 铜高亮），
 * 菜单「查看歌手 / 查看专辑」照搜索页装配。
 */
@Composable
fun RankingPage(
    groups: List<RankingGroup>,
    catalogLoading: Boolean,
    catalogError: String?,
    selected: RankingBrief?,
    ranking: RankingInfo?,
    songs: List<Song>,
    /** 歌曲第一页加载中（头部资料与第一页是同一个请求）。 */
    songsLoading: Boolean,
    songsLoadingMore: Boolean,
    songsHasMore: Boolean,
    /** 第一页失败（songs 为空，列表位置重试）与加载更多失败（songs 非空，页脚重试）共用的内联错误。 */
    songsError: String?,
    /** 榜单真实条数（分页 meta.total）；详情头部「共 N 首」用它。 */
    songsTotal: Long,
    favoriteRevision: Int = 0,
    downloadedRevision: Int = 0,
    isFavorite: (Song) -> Boolean = { false },
    isDownloaded: (Song) -> Boolean = { false },
    /** 目录卡点击进详情态。 */
    onOpenDetail: (RankingBrief) -> Unit = {},
    /** 详情态返回目录。 */
    onCloseDetail: () -> Unit = {},
    /** 目录态关闭整页（回首页）。 */
    onClose: () -> Unit,
    /** 目录加载失败后的重试。 */
    onRetryCatalog: () -> Unit = {},
    /** 详情第一页失败后的重试。 */
    onRetryDetail: () -> Unit = {},
    /** 滚到歌曲列表近底部时拉取下一页。 */
    onLoadMoreSongs: () -> Unit = {},
    /** 点歌曲行：以整条榜单（已累积、名次顺序）为队列上下文入队。 */
    onSongClick: (Int) -> Unit = {},
    onToggleFavorite: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    /** 歌曲行菜单「查看歌手」：只有 song.artistId 非空时才会被组件展示。 */
    onOpenArtist: (Song) -> Unit = {},
    /** 歌曲行菜单「查看专辑」：只有 song.albumId 非空时才会被组件展示。 */
    onOpenAlbum: (Song) -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        CatalogPageBackdrop()
        Column(Modifier.fillMaxSize()) {
            val brief = selected
            if (brief == null) {
                CatalogTopBar(title = "排行榜", onBack = onClose)
                RankingCatalogView(
                    groups = groups,
                    loading = catalogLoading,
                    error = catalogError,
                    onOpenDetail = onOpenDetail,
                    onRetry = onRetryCatalog,
                )
            } else {
                CatalogTopBar(title = "榜单详情", onBack = onCloseDetail)
                RankingDetailView(
                    brief = brief,
                    detail = ranking,
                    songs = songs,
                    songsLoading = songsLoading,
                    songsLoadingMore = songsLoadingMore,
                    songsHasMore = songsHasMore,
                    songsError = songsError,
                    songsTotal = songsTotal,
                    favoriteRevision = favoriteRevision,
                    downloadedRevision = downloadedRevision,
                    isFavorite = isFavorite,
                    isDownloaded = isDownloaded,
                    onRetry = onRetryDetail,
                    onLoadMore = onLoadMoreSongs,
                    onSongClick = onSongClick,
                    onToggleFavorite = onToggleFavorite,
                    onPlayNext = onPlayNext,
                    onAddToPlaylist = onAddToPlaylist,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
    }
}

/**
 * 目录态：按模块分组的小节标题 + 组内横滑的榜单横版卡。
 *
 * 加载 / 失败 / 空态用页面级占位居中展示（与歌手页详情未就绪同一套做法）；
 * 成功后每组占两个 item（标题 + 横滑行），模块名缺失时只出横滑行，空组整组跳过。
 */
@Composable
private fun RankingCatalogView(
    groups: List<RankingGroup>,
    loading: Boolean,
    error: String?,
    onOpenDetail: (RankingBrief) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        loading && groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CatalogPageStatus(text = "正在加载排行榜…", loading = true)
        }
        groups.isEmpty() && error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CatalogPageStatus(text = error, loading = false, onRetry = onRetry)
        }
        groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyStateView(title = "暂无排行榜", description = "服务端暂时没有可浏览的榜单")
        }
        else -> {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
            ) {
                groups.forEachIndexed { groupIndex, group ->
                    if (group.bangs.isEmpty()) return@forEachIndexed
                    if (group.moduleName.isNotBlank()) {
                        item(key = "ranking-module-title-$groupIndex") {
                            Text(
                                group.moduleName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = TaotaoSpacing.sm),
                            )
                        }
                    }
                    item(key = "ranking-module-row-$groupIndex") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
                            contentPadding = PaddingValues(vertical = TaotaoSpacing.xxs),
                        ) {
                            // key 用下标：上游可能给同名同 ID 的重复榜单行，按内容做 key 会让
                            // LazyColumn 崩溃（与歌手页相似列表同一口径）。
                            itemsIndexed(
                                group.bangs,
                                key = { bangIndex, _ -> "ranking-bang-$groupIndex-$bangIndex" },
                                contentType = { _, _ -> "ranking-bang" },
                            ) { _, brief ->
                                RankingCard(brief = brief, onOpen = { onOpenDetail(brief) })
                            }
                        }
                    }
                }
                item(key = "ranking-catalog-footer") { Spacer(Modifier.height(TaotaoSpacing.sm)) }
            }
        }
    }
}

/**
 * 榜单横版卡：封面在左、榜单名与更新日期在右。
 *
 * 宽度是只属于这一屏的布局常量（横版卡比竖版封面卡更能突出「榜单」语义），
 * 不进共享尺寸刻度；封面兜底色走共享 token（TaotaoColors 唯一数据源）。
 */
@Composable
private fun RankingCard(brief: RankingBrief, onOpen: () -> Unit) {
    Row(
        Modifier
            .width(RankingCardWidth)
            .clip(TaotaoShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TaotaoWash.subtle))
            .clickable(onClick = onOpen)
            .padding(TaotaoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            color = TaotaoColors.placeholderArtwork,
            size = RankingCardCoverSize,
            imageUri = brief.pic,
            shape = TaotaoShapes.small,
        )
        Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) {
            Text(
                brief.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (brief.pubStr.isNotBlank()) {
                Text(
                    brief.pubStr,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                )
            }
        }
    }
}

/**
 * 详情态：头部（榜单封面 + 名 + 更新日期 + 共 N 首）+ 榜单歌曲列表。
 *
 * 头部在详情资料到达前用 [brief] 兜底渲染，加载 / 失败占位挂在头部下方 ——
 * 不整页留白，用户至少知道自己在看哪个榜。歌曲列表是分页累积的：行首展示
 * 全局名次（前三名金 / 银 / 铜高亮），滚到近底部自动经 [onLoadMore] 翻页，
 * 页脚给「加载更多 / 失败重试 / 没有更多了」三态；空榜单（部分歌手榜）给专属空态。
 */
@Composable
private fun RankingDetailView(
    brief: RankingBrief,
    detail: RankingInfo?,
    songs: List<Song>,
    songsLoading: Boolean,
    songsLoadingMore: Boolean,
    songsHasMore: Boolean,
    songsError: String?,
    songsTotal: Long,
    favoriteRevision: Int,
    downloadedRevision: Int,
    isFavorite: (Song) -> Boolean,
    isDownloaded: (Song) -> Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSongClick: (Int) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onOpenArtist: (Song) -> Unit,
    onOpenAlbum: (Song) -> Unit,
) {
    val listState = rememberLazyListState()
    // 换榜单（含同榜单重试）时回到列表顶部；同榜单的普通重组不能重置滚动位置。
    LaunchedEffect(brief.id) { listState.scrollToItem(0) }
    // 头部占一个 item，其后才是歌曲条目；预取触发点要加上这个前置 item。
    val leadingItemCount = 1
    val shouldLoadMore by remember(listState, songs.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            // 条目数不足以滚出预取窗口时不触发（靠 hasMore 收口），与歌手 / 专辑页同一窗口。
            songs.size > CatalogLoadMoreThreshold &&
                last >= leadingItemCount + songs.size - CatalogLoadMoreThreshold
        }
    }
    LaunchedEffect(shouldLoadMore, songs.size) {
        if (shouldLoadMore) onLoadMore()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
    ) {
        item(key = "ranking-header") {
            RankingDetailHeader(brief = brief, detail = detail, songsTotal = songsTotal)
        }
        when {
            songsLoading && songs.isEmpty() -> {
                item(key = "ranking-songs-loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.progressInline))
                    }
                }
            }
            songs.isEmpty() && songsError != null -> {
                item(key = "ranking-songs-error") { CatalogInlineStatus(text = songsError, onRetry = onRetry) }
            }
            songs.isEmpty() -> {
                item(key = "ranking-songs-empty") {
                    EmptyStateView(
                        title = "该榜单暂无歌曲",
                        description = "部分歌手榜可能暂时还没有上榜歌曲",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            else -> {
                itemsIndexed(
                    songs,
                    key = { _, song -> songKeyOf(song) },
                    contentType = { _, _ -> "ranking-song" },
                ) { index, song ->
                    val key = songKeyOf(song)
                    // 全局名次 = 累积列表的线性下标 + 1。服务端契约保证条目顺序即排名
                    // 顺序、翻页前进不交叠，分页按页码顺序追加，所以线性下标天然连续
                    // （= (page-1) × 每页条数 + 页内下标 + 1 的等价形式），名次不重不漏。
                    val rank = index + 1
                    RankingSongRow(
                        rank = rank,
                        song = song,
                        favorited = remember(key, favoriteRevision) { isFavorite(song) },
                        downloaded = remember(key, downloadedRevision) { isDownloaded(song) },
                        onToggleFavorite = { onToggleFavorite(song) },
                        onPlayNext = { onPlayNext(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        // 菜单双门槛照搜索页装配：回调非空且歌曲带音源内歌手 / 专辑 ID 才放行，
                        // SongListItem 内部还有一层同口径判定，两层一致不会出现「点了没反应」。
                        onOpenArtist = onOpenArtist.takeIf { song.artistId != null }?.let { open -> { open(song) } },
                        onOpenAlbum = onOpenAlbum.takeIf { song.albumId != null }?.let { open -> { open(song) } },
                        onClick = { onSongClick(index) },
                    )
                }
                // 页脚三态互斥：加载更多指示 / 加载更多失败重试 / 没有更多了。
                // 列表非空时 songsError 只可能来自加载更多（第一页失败时列表是空的），
                // 所以这里的重试直接续拉下一页，不必整榜重来。
                when {
                    songsLoadingMore -> item(key = "ranking-loading-more") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.md),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(TaotaoSizes.progressInline), color = TaotaoCoral)
                            Text(
                                "正在加载更多…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = TaotaoSpacing.xs),
                            )
                        }
                    }
                    songsError != null -> item(key = "ranking-load-more-error") {
                        CatalogInlineStatus(text = songsError, onRetry = onLoadMore)
                    }
                    !songsHasMore -> item(key = "ranking-list-end") {
                        Text(
                            "没有更多了",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.md),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        item(key = "ranking-detail-footer") { Spacer(Modifier.height(TaotaoSpacing.sm)) }
    }
}

/**
 * 带排名序号的歌曲行：行首固定宽度序号列 + 公共 [SongListItem]。
 *
 * 序号照 `%02d` 补零（01…09；三位名次如 100 由 format 原样放下）；个位数用
 * 大一号字体、两位数起换小一号，避免长名次挤压行高。名次配色照波点惯例：
 * 金 / 银 / 铜区分冠军 / 亚军 / 季军，其余用 onSurfaceVariant 弱化。
 * 序号列单独可点且与歌曲行等价 —— 用户点「01」时预期就是播这首歌。
 */
@Composable
private fun RankingSongRow(
    rank: Int,
    song: Song,
    favorited: Boolean,
    downloaded: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "%02d".format(rank),
            color = rankingRankColor(rank),
            style = if (rank < 10) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (rank <= 3) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(RankingRankColumnWidth)
                .clickable(onClick = onClick),
        )
        SongListItem(
            song = song,
            active = false,
            favorited = favorited,
            downloaded = downloaded,
            onToggleFavorite = onToggleFavorite,
            onPlayNext = onPlayNext,
            onAddToPlaylist = onAddToPlaylist,
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            // 序号列吃掉固定宽度后，歌曲行吃剩余宽度（SongRow 内部自带 fillMaxWidth）。
            modifier = Modifier.weight(1f),
        ) { onClick() }
    }
}

/** 名次序号配色：金 / 银 / 铜区分冠军 / 亚军 / 季军，其余弱化为 onSurfaceVariant。 */
@Composable
private fun rankingRankColor(rank: Int): Color = when (rank) {
    1 -> RankingGold
    2 -> RankingSilver
    3 -> RankingBronze
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 前三名序号色：金 / 银 / 铜。榜单是一次性视觉配色，不进主题 token（页面私有常量先例）。 */
private val RankingGold = Color(0xFFDFA32E)
private val RankingSilver = Color(0xFF9AA3AD)
private val RankingBronze = Color(0xFFC88250)

/**
 * 榜单详情头部：封面 + 榜单名 + 更新日期。
 *
 * [detail] 未到达时用 [brief]（目录卡点击时已有的最小资料）渲染；到达后以详情为准。
 * 目录侧的 pubStr 可能已带「更新」字样、详情侧的 pub 只有日期，这里归一成
 * 「10-05 更新」一种形态，避免出现「10-05更新更新」。
 */
@Composable
private fun RankingDetailHeader(brief: RankingBrief, detail: RankingInfo?, songsTotal: Long) {
    val name = detail?.name?.takeIf { it.isNotBlank() } ?: brief.name
    val pic = detail?.pic ?: brief.pic
    val pub = detail?.pub?.takeIf { it.isNotBlank() } ?: brief.pubStr
    Row(
        Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(
            color = TaotaoColors.placeholderArtwork,
            size = TaotaoSizes.artworkGrid,
            imageUri = pic,
            shape = TaotaoShapes.artwork,
        )
        Column(Modifier.weight(1f).padding(start = TaotaoSpacing.md)) {
            Text(
                name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 副行合并「更新于 … · 共 N 首」：total 是分页 meta 的真实条数
            //（主流榜单恒 100），不是 ranking.total 的上游展示值。
            val meta = buildList {
                if (pub.isNotBlank()) add(if (pub.endsWith("更新")) pub else "$pub 更新")
                if (songsTotal > 0) add("共 ${formatCatalogCount(songsTotal)} 首")
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = TaotaoSpacing.xs),
                )
            }
        }
    }
}

/** 榜单横版卡的宽度：只属于排行榜目录一屏的布局常量，不进设计刻度。 */
private val RankingCardWidth = 216.dp

/** 榜单横版卡的封面边长：卡内缩略图，介于列表行（48dp）与网格封面（112dp）之间。 */
private val RankingCardCoverSize = 72.dp

/** 详情列表行首排名序号列的定宽：两位数补零观感（「01」起），三位名次原样放下。 */
private val RankingRankColumnWidth = 40.dp
