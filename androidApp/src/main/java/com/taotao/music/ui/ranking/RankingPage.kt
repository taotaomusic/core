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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
 * 歌曲行复用公共 [SongListItem]，菜单「查看歌手 / 查看专辑」照搜索页装配。
 */
@Composable
fun RankingPage(
    groups: List<RankingGroup>,
    catalogLoading: Boolean,
    catalogError: String?,
    selected: RankingBrief?,
    ranking: RankingInfo?,
    detailLoading: Boolean,
    detailError: String?,
    songs: List<Song>,
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
    /** 详情加载失败后的重试。 */
    onRetryDetail: () -> Unit = {},
    /** 点歌曲行：以整条榜单为队列上下文入队。 */
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
                    detailLoading = detailLoading,
                    detailError = detailError,
                    songs = songs,
                    favoriteRevision = favoriteRevision,
                    downloadedRevision = downloadedRevision,
                    isFavorite = isFavorite,
                    isDownloaded = isDownloaded,
                    onRetry = onRetryDetail,
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
 * 详情态：头部（榜单封面 + 名 + 更新日期）+ 榜单歌曲列表。
 *
 * 头部在详情资料到达前用 [brief] 兜底渲染，加载 / 失败占位挂在头部下方 ——
 * 不整页留白，用户至少知道自己在看哪个榜。榜单歌曲固定约 20 首且无分页，
 * 所以没有「加载更多」逻辑；空榜单（部分歌手榜）给专属空态而不是错误。
 */
@Composable
private fun RankingDetailView(
    brief: RankingBrief,
    detail: RankingInfo?,
    detailLoading: Boolean,
    detailError: String?,
    songs: List<Song>,
    favoriteRevision: Int,
    downloadedRevision: Int,
    isFavorite: (Song) -> Boolean,
    isDownloaded: (Song) -> Boolean,
    onRetry: () -> Unit,
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
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
    ) {
        item(key = "ranking-header") { RankingDetailHeader(brief = brief, detail = detail) }
        when {
            detailLoading && songs.isEmpty() -> {
                item(key = "ranking-songs-loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.progressInline))
                    }
                }
            }
            songs.isEmpty() && detailError != null -> {
                item(key = "ranking-songs-error") { CatalogInlineStatus(text = detailError, onRetry = onRetry) }
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
                    // 菜单双门槛照搜索页装配：回调非空且歌曲带音源内歌手 / 专辑 ID 才放行，
                    // SongListItem 内部还有一层同口径判定，两层一致不会出现「点了没反应」。
                    SongListItem(
                        song = song,
                        active = false,
                        favorited = remember(key, favoriteRevision) { isFavorite(song) },
                        downloaded = remember(key, downloadedRevision) { isDownloaded(song) },
                        onToggleFavorite = { onToggleFavorite(song) },
                        onPlayNext = { onPlayNext(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onOpenArtist = onOpenArtist.takeIf { song.artistId != null }?.let { open -> { open(song) } },
                        onOpenAlbum = onOpenAlbum.takeIf { song.albumId != null }?.let { open -> { open(song) } },
                    ) { onSongClick(index) }
                }
            }
        }
        item(key = "ranking-detail-footer") { Spacer(Modifier.height(TaotaoSpacing.sm)) }
    }
}

/**
 * 榜单详情头部：封面 + 榜单名 + 更新日期。
 *
 * [detail] 未到达时用 [brief]（目录卡点击时已有的最小资料）渲染；到达后以详情为准。
 * 目录侧的 pubStr 可能已带「更新」字样、详情侧的 pub 只有日期，这里归一成
 * 「10-05 更新」一种形态，避免出现「10-05更新更新」。
 */
@Composable
private fun RankingDetailHeader(brief: RankingBrief, detail: RankingInfo?) {
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
            if (pub.isNotBlank()) {
                Text(
                    if (pub.endsWith("更新")) pub else "$pub 更新",
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
