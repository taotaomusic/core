package com.taotao.music.ui.playlist

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
import com.taotao.music.data.OnlinePlaylistDetail
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
import com.taotao.music.ui.artist.formatCatalogCount
import com.taotao.music.ui.common.AlbumArt

/**
 * 在线歌单页：音源侧公开歌单的详情页（搜索「歌单」标签点进来看到的），
 * 与账号云端歌单详情（[PlaylistDetailPage]）是两套东西 —— 后者是账号数据、
 * 带增删改与拖动排序；本页只读，职责是把上游歌单的资料与收录歌曲呈现出来。
 *
 * 结构与专辑页（`ui/album/AlbumPage.kt`）同构：沉浸式头部（圆角封面、歌单名、
 * 创建者行、数据行）、默认收起的长简介，以及分页的歌曲列表。顶栏 / 渐变背景 /
 * 状态占位 / 折叠简介 / 歌曲区块复用歌手页暴露的 internal 辅助 —— 三页是同一类
 * 资料目录页（见 `ui/artist/ArtistPage.kt` 的说明）。
 */

/**
 * 在线歌单页装配。
 *
 * 数据与操作全部来自 [com.taotao.music.ui.app.OnlinePlaylistPageState]，由路由层注入。
 * [detail] 永不为空：打开瞬间是搜索行拼的最小资料（详情到达前的头部兜底），
 * 详情请求成功后被权威值整体替换 —— 所以「详情未到达」的判定要用
 * [detailLoading] / [detailError]，不能用资料对象本身判空（这点与专辑页不同）。
 */
@Composable
fun OnlinePlaylistPage(
    detail: OnlinePlaylistDetail,
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
) {
    // 从搜索页点进不同歌单时回到列表顶部（代次状态层已保证数据不串页）。
    val listState = rememberLazyListState()
    LaunchedEffect(detail.id) { listState.scrollToItem(0) }
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
            CatalogTopBar(title = "歌单", onBack = onBack)
            when {
                // 权威详情未到达（加载中或失败）：头部用搜索行垫的兜底资料渲染，
                // 状态占位居中给加载 / 重试；此时歌曲列表一并隐藏，重试后一起回来。
                // 专辑页用「detail == null」判这个状态，本页资料对象恒非空，改用双标记。
                detailLoading || detailError != null -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
                        OnlinePlaylistHeader(detail = detail)
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CatalogPageStatus(
                                text = when {
                                    detailLoading -> "正在加载歌单资料…"
                                    detailError != null -> detailError
                                    else -> "歌单资料暂不可用"
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
                    item(key = "playlist-header") {
                        OnlinePlaylistHeader(detail = detail)
                    }
                    item(key = "playlist-desc") { CatalogExpandableText(text = detail.description) }
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
                    item(key = "playlist-footer") { Spacer(Modifier.height(TaotaoSpacing.sm)) }
                }
            }
        }
    }
}

/**
 * 在线歌单页头部：圆角封面 + 歌单名 + 创建者行 + 数据行（曲目数 · 播放数 · 收藏数）。
 *
 * 与专辑页头部（[com.taotao.music.ui.album] 内私有实现）刻意不同构的两点：
 * 创建者**不可点**（上游用户主页没有对应接口，点了没地方去），数据行用
 * [formatCatalogCount] 缩写大数（搜索行与详情都带播放 / 收藏计数，这是歌单的
 * 核心展示信息）。各计数段只在大于 0 时出现 —— 搜索行垫的兜底资料里收藏数为 0，
 * 不渲染就不会把「没加载完」当成「没人收藏」。
 */
@Composable
private fun OnlinePlaylistHeader(detail: OnlinePlaylistDetail) {
    Row(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        // 走共享占位色 token：无封面兜底色全端唯一，与专辑 / 歌手页同一套。
        AlbumArt(
            color = TaotaoColors.placeholderArtwork,
            size = TaotaoSizes.artworkHero,
            imageUri = detail.pic,
            shape = TaotaoShapes.artwork,
        )
        Column(Modifier.weight(1f).padding(start = TaotaoSpacing.md)) {
            Text(
                detail.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.creatorName.isNotBlank()) {
                val creatorLine = buildString {
                    append(detail.creatorName)
                    append(" 创建")
                    if (detail.isPrivate) append(" · 私密")
                }
                Text(
                    creatorLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                )
            }
            val metaLine = listOfNotNull(
                detail.trackCount.takeIf { it > 0L }?.let { count -> "$count 首" },
                detail.playCount.takeIf { it > 0L }?.let { count -> "播放 ${formatCatalogCount(count)}" },
                detail.collectedCount.takeIf { it > 0L }?.let { count -> "收藏 ${formatCatalogCount(count)}" },
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
