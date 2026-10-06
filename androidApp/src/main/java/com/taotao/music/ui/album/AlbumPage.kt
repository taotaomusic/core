package com.taotao.music.ui.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.taotao.music.data.AlbumDetail
import com.taotao.music.data.AlbumSearchResult
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoColors
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.artist.CatalogExpandableText
import com.taotao.music.ui.artist.CatalogLoadMoreThreshold
import com.taotao.music.ui.artist.CatalogPageBackdrop
import com.taotao.music.ui.artist.CatalogPageStatus
import com.taotao.music.ui.artist.CatalogTopBar
import com.taotao.music.ui.artist.catalogSongSection
import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.theme.TaotaoCoral

/**
 * 专辑页：沉浸式头部（圆角封面、专辑名、可点进歌手页的歌手名、发行日期与歌曲数）、
 * 默认收起的长简介，以及分页的歌曲列表。
 *
 * 顶栏 / 渐变背景 / 状态占位 / 折叠简介 / 歌曲区块复用歌手页暴露的 internal 辅助 ——
 * 两页是同一类资料目录页（见 `ui/artist/ArtistPage.kt` 的说明）。
 */

/**
 * 专辑页头图封面的边长：hero 语义，走三端共用封面刻度最大档 [TaotaoSizes.artworkHero]。
 *
 * （视觉收敛 2026-10：原为页面私有 120dp，收编进共享 token，与播放页大封面同档。）
 */
private val AlbumCoverSize = TaotaoSizes.artworkHero

/**
 * 专辑页装配。
 *
 * 数据与操作全部来自 [com.taotao.music.ui.app.AlbumPageState]，由路由层注入；
 * [target] 是打开目标（详情到达前的头部兜底资料），页面自身不发起任何网络请求。
 */
@Composable
fun AlbumPage(
    target: AlbumSearchResult,
    detail: AlbumDetail?,
    detailLoading: Boolean,
    detailError: String?,
    songs: List<Song>,
    songsLoading: Boolean,
    songsLoadingMore: Boolean,
    songsHasMore: Boolean,
    songsError: String?,
    favoriteRevision: Int = 0,
    downloadedRevision: Int = 0,
    isFavorite: (Song) -> Boolean = { false },
    isDownloaded: (Song) -> Boolean = { false },
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMoreSongs: () -> Unit = {},
    onSongClick: (Int) -> Unit = {},
    onToggleFavorite: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    /** 点击头部歌手名：携带歌手的音源 ID 与展示名，宿主据此打开歌手页。 */
    onArtistClick: (artistId: Long, artistName: String) -> Unit = { _, _ -> },
) {
    // 切换专辑（从歌手页专辑卡片点进另一张专辑）时回到列表顶部。
    val listState = rememberLazyListState()
    LaunchedEffect(target.id) { listState.scrollToItem(0) }
    // 头部与简介各占一个 item，其后才是歌曲条目；预取触发点要加上这两个前置 item。
    val leadingItemCount = 2
    val shouldLoadMore by remember(listState, songs.size) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            // 条目数不足以滚出预取窗口时不触发，靠 hasMore 收口。
            songs.size > CatalogLoadMoreThreshold &&
                last >= leadingItemCount + songs.size - CatalogLoadMoreThreshold
        }
    }
    LaunchedEffect(shouldLoadMore, songs.size) { if (shouldLoadMore) onLoadMoreSongs() }
    Box(Modifier.fillMaxSize()) {
        CatalogPageBackdrop()
        Column(Modifier.fillMaxSize()) {
            CatalogTopBar(title = "专辑", onBack = onBack)
            when {
                // 详情未到达（加载中或失败）：头部用打开目标的兜底资料渲染，
                // 状态占位居中给加载 / 重试；此时歌曲列表一并隐藏，重试后一起回来。
                detail == null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
                        AlbumSummaryHeader(target = target, detail = null, onArtistClick = onArtistClick)
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CatalogPageStatus(
                                text = when {
                                    detailLoading -> "正在加载专辑资料…"
                                    detailError != null -> detailError
                                    else -> "专辑资料暂不可用"
                                },
                                loading = detailLoading,
                                onRetry = if (!detailLoading && detailError != null) onRetry else null,
                            )
                        }
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
                ) {
                    item(key = "album-header") {
                        AlbumSummaryHeader(target = target, detail = detail, onArtistClick = onArtistClick)
                    }
                    item(key = "album-desc") { CatalogExpandableText(text = detail.desc) }
                    catalogSongSection(
                        songs = songs,
                        firstPageLoading = songsLoading,
                        firstPageError = songsError,
                        onRetry = onRetry,
                        isLoadingMore = songsLoadingMore,
                        hasMore = songsHasMore,
                        favoriteRevision = favoriteRevision,
                        downloadedRevision = downloadedRevision,
                        isFavorite = isFavorite,
                        isDownloaded = isDownloaded,
                        onSongClick = onSongClick,
                        onToggleFavorite = onToggleFavorite,
                        onPlayNext = onPlayNext,
                        onAddToPlaylist = onAddToPlaylist,
                    )
                    item(key = "album-footer") { Spacer(Modifier.height(TaotaoSpacing.sm)) }
                }
            }
        }
    }
}

/**
 * 专辑页头部：圆角封面 + 专辑名 + 歌手（可点进歌手页）+ 发行日期与歌曲数次要行。
 *
 * [detail] 未到达时用 [target]（搜索区块 / 歌手页专辑卡片点击时已有的最小资料）渲染；
 * 歌手名只在 ID 与名字都有效时才可点，缺一就退化为纯文本，避免跳到无效目标。
 */
@Composable
private fun AlbumSummaryHeader(
    target: AlbumSearchResult,
    detail: AlbumDetail?,
    onArtistClick: (artistId: Long, artistName: String) -> Unit,
) {
    val name = detail?.name?.takeIf { it.isNotBlank() } ?: target.name
    val pic = detail?.pic ?: target.pic
    val artist = detail?.artist?.takeIf { it.isNotBlank() } ?: target.artist
    val artistId = detail?.artistId?.takeIf { it > 0L } ?: target.artistId
    val songCount = detail?.songCount ?: target.songCount
    val showtime = detail?.showtime?.takeIf { it.isNotBlank() } ?: target.showtime
    Row(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        // 走共享占位色 token：无封面兜底色全端唯一（0xFFFFB4A2 收编进 TaotaoColors）。
        AlbumArt(
            color = TaotaoColors.placeholderArtwork,
            size = AlbumCoverSize,
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
            if (artist.isNotBlank() && artistId > 0L) {
                Text(
                    artist,
                    color = TaotaoCoral,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = TaotaoSpacing.xxs)
                        .clickable { onArtistClick(artistId, artist) },
                )
            }
            val metaLine = listOfNotNull(
                showtime.takeIf { it.isNotBlank() },
                songCount.takeIf { it > 0L }?.let { count -> "$count 首" },
            ).joinToString(" · ")
            if (metaLine.isNotBlank()) {
                Text(
                    metaLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                )
            }
        }
    }
}
