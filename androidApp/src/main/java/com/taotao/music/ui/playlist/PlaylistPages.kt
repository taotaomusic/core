package com.taotao.music.ui.playlist

import com.taotao.music.ui.common.AlbumArt
import com.taotao.music.ui.common.DragReorderList
import com.taotao.music.ui.common.SongRow
import com.taotao.music.ui.common.songKeyOf
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.TaotaoCoral
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.playerui.SharedBackButton
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import java.util.Locale

/**
 * 选歌 / 选歌单对话框内容区的最大高度。
 *
 * 两个选择器此前分别写了 460dp 与 420dp，超出部分靠滚动，
 * 实际差异只是「早滚一行还是晚滚一行」，收敛成同一个上限。
 */
private val PickerDialogContentMaxHeight = 420.dp

/** 歌单资料编辑模式；编辑模式携带原歌单以便提交对应 ID。 */
sealed interface PlaylistEditorMode {
    data object CREATE : PlaylistEditorMode
    data class EDIT(val playlist: TencentMusicApi.Playlist) : PlaylistEditorMode
}

/**
 * 云端歌单总览页。歌单资料由上层负责请求和写入，这个页面只处理展示与用户意图，
 * 这样 Android 和 Windows 可以各自保持原有导航而共用同一套接口语义。
 */
@Composable
fun PlaylistLibraryPage(
    playlists: List<TencentMusicApi.Playlist>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (TencentMusicApi.Playlist) -> Unit,
    onRename: (TencentMusicApi.Playlist) -> Unit,
    onDelete: (TencentMusicApi.Playlist) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
        SharedSectionHeader(
            title = "我的歌单",
            subtitle = if (playlists.isEmpty()) "云端同步到所有设备" else "${playlists.size} 个歌单 · 云端同步",
            level = SharedSectionLevel.PAGE,
            leading = { SharedBackButton(onBack) },
            // 两个操作按钮需要并排，所以自己包一层 Row —— SharedSectionHeader
            // 的 trailing 刻意不带 RowScope 接收者，见该参数说明。
            trailing = {
                Row {
                    IconButton(onClick = onCreate) { Icon(Icons.Default.Add, "新建歌单") }
                    IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "刷新歌单") }
                }
            },
        )
        if (loading && playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TaotaoCoral)
            }
        } else if (playlists.isEmpty()) {
            PlaylistEmptyState(error, onRefresh, onCreate)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = TaotaoSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onOpen(playlist) },
                        onRename = { onRename(playlist) },
                        onDelete = { onDelete(playlist) },
                    )
                }
            }
        }
    }
}

/** 歌单详情：支持添加、播放、删除和拖动排序歌曲，以及编辑歌单资料。 */
@Composable
fun PlaylistDetailPage(
    playlist: TencentMusicApi.Playlist,
    knownSongs: List<Song> = emptyList(),
    onBack: () -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onPlaySong: (List<Song>, Int) -> Unit,
    onRemoveSong: (TencentMusicApi.PlaylistSong) -> Unit,
    onMoveSong: (from: Int, to: Int) -> Unit,
    onAddSong: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    // 云端歌单不会保存设备私有的 file: 地址；按稳定身份把本机下载文件重新关联回来。
    // 只在身份一致时合并音频，歌名/歌手相同仅用于补封面，避免同名歌曲串到错误文件。
    val songs = remember(playlist, knownSongs) {
        playlist.songs.map { item ->
            val song = item.toSong()
            val itemKeys = playlistIdentityKeys(item.source, item.songId, item.mid)
            val downloaded = knownSongs.firstOrNull { candidate ->
                candidate.audioUri?.startsWith("file:", ignoreCase = true) == true &&
                    playlistIdentityKeys(candidate).intersect(itemKeys).isNotEmpty()
            }
            if (downloaded != null) {
                song.copy(
                    audioUri = downloaded.audioUri,
                    remoteId = downloaded.remoteId ?: song.remoteId,
                    mid = downloaded.mid ?: song.mid,
                    type = downloaded.type ?: song.type,
                    source = downloaded.source,
                    coverUri = downloaded.coverUri ?: song.coverUri,
                    lyricUri = downloaded.lyricUri ?: song.lyricUri,
                    lyricWordsUri = downloaded.lyricWordsUri,
                    localQuality = downloaded.localQuality,
                )
            } else if (song.coverUri.isNullOrBlank()) {
                knownSongs.firstOrNull { candidate ->
                    if (candidate.coverUri.isNullOrBlank()) return@firstOrNull false
                    val sameIdentity = playlistIdentityKeys(candidate).intersect(itemKeys).isNotEmpty()
                    val sameMetadata = playlistTextKey(candidate.title, candidate.artist) ==
                        playlistTextKey(item.title, item.artist)
                    sameIdentity || (sameMetadata && item.title.isNotBlank() && item.artist.isNotBlank())
                }?.let { candidate -> song.copy(coverUri = candidate.coverUri) } ?: song
            } else {
                song
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.screenHorizontal)) {
        SharedSectionHeader(
            title = playlist.name,
            subtitle = "${playlist.songCount} 首 · 云端歌单",
            level = SharedSectionLevel.PAGE,
            leading = { SharedBackButton(onBack) },
            trailing = {
                Row {
                    IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "重命名") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "删除歌单", tint = TaotaoCoral) }
                }
            },
        )
        if (playlist.description.isNotBlank()) {
            Text(
                playlist.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = TaotaoSpacing.xs, bottom = TaotaoSpacing.xs),
            )
        }
        Row(Modifier.fillMaxWidth().padding(bottom = TaotaoSpacing.xs), horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
            Button(
                onClick = { if (songs.isNotEmpty()) onPlayAll(songs) },
                enabled = songs.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(TaotaoSizes.iconSm))
                Spacer(Modifier.width(TaotaoSpacing.xxs))
                Text("播放全部")
            }
            OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(TaotaoSizes.iconSm))
                Spacer(Modifier.width(TaotaoSpacing.xxs))
                Text("编辑资料")
            }
        }
        OutlinedButton(
            onClick = onAddSong,
            modifier = Modifier.fillMaxWidth().padding(bottom = TaotaoSpacing.xs),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(TaotaoSizes.iconSm))
            Spacer(Modifier.width(TaotaoSpacing.xxs))
            Text("添加歌曲")
        }
        if (songs.isEmpty()) {
            PlaylistEmptyState("歌单还是空的", null, null)
        } else {
            // 与播放队列共用同一套长按拖动排序；此前的上下箭头要一格一格挪，已废弃。
            DragReorderList(
                items = songs,
                identity = ::songKeyOf,
                onMove = onMoveSong,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxs),
                contentPadding = PaddingValues(bottom = TaotaoSpacing.xl),
            ) { index, song, _, rowModifier, dragHandle ->
                SongRow(
                    song = song,
                    modifier = rowModifier,
                    onClick = { onPlaySong(songs, index) },
                    onDelete = { playlist.songs.getOrNull(index)?.let(onRemoveSong) },
                    dragHandle = dragHandle,
                )
            }
        }
    }
}

/**
 * 从本机已经出现过的歌曲中挑选并加入当前歌单。
 *
 * 候选由搜索、当前队列、收藏、历史和下载列表合并而来；稳定键缺失的条目不展示，
 * 这样点击后不会才发现服务端无法识别。歌单已有的歌曲置灰但仍可滚动查看。
 */
@Composable
fun PlaylistSongPickerDialog(
    candidates: List<Song>,
    existingSongs: List<TencentMusicApi.PlaylistSong>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (Song) -> Unit,
) {
    // 同一首歌可能先以 mid-only 形式出现，详情接口后来又补上数字 ID；同时保留
    // 两种稳定键，避免用户在这种元数据变化后重复添加。
    val existingKeys = remember(existingSongs) {
        existingSongs.flatMap { playlistIdentityKeys(it.source, it.songId, it.mid) }.toSet()
    }
    val usable = remember(candidates) {
        val seen = HashSet<String>()
        candidates.filter { song ->
            val keys = playlistIdentityKeys(song)
            keys.isNotEmpty() && keys.none { it in seen }.also { unique ->
                if (unique) seen.addAll(keys)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加歌曲") },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(TaotaoSpacing.xl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.iconMd))
                }
            } else if (usable.isEmpty()) {
                Text("暂无可添加的歌曲，请先搜索或播放音乐。", modifier = Modifier.padding(vertical = TaotaoSpacing.md))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = PickerDialogContentMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxs),
                ) {
                    items(usable, key = ::playlistCandidateKey) { song ->
                        val exists = playlistIdentityKeys(song).any { it in existingKeys }
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(TaotaoShapes.medium)
                                .clickable(enabled = !exists) { onPick(song) }
                                .padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AlbumArt(Color(song.color), TaotaoSizes.artworkRow, song.coverUri)
                            Column(Modifier.weight(1f).padding(start = TaotaoSpacing.xs)) {
                                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                if (exists) "已在歌单" else "添加",
                                color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private fun playlistCandidateKey(song: Song): String =
    playlistIdentityKeys(song).first()

private fun playlistIdentityKeys(song: Song): Set<String> = playlistIdentityKeys(
    source = song.source,
    songId = TencentMusicApi.playlistSongId(song),
    mid = song.mid,
)

private fun playlistIdentityKeys(source: String, songId: String?, mid: String?): Set<String> {
    val normalizedSource = source.trim().ifBlank { "tencent" }.lowercase(Locale.ROOT)
    return buildSet {
        songId?.trim()?.takeIf { it.isNotBlank() }?.let { add("$normalizedSource:$it") }
        mid?.trim()?.takeIf { it.isNotBlank() }?.let { add("$normalizedSource:$it") }
    }
}

private fun playlistTextKey(title: String, artist: String): String =
    "${title.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)}#" +
        artist.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)

/** 从搜索结果或播放详情打开的歌单选择器。 */
@Composable
fun PlaylistPickerDialog(
    song: Song,
    playlists: List<TencentMusicApi.Playlist>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (TencentMusicApi.Playlist) -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入歌单") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = PickerDialogContentMaxHeight)) {
                Text(
                    "将「${song.title}」保存到云端歌单",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = TaotaoSpacing.xs),
                )
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(TaotaoSpacing.lg), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TaotaoCoral, modifier = Modifier.size(TaotaoSizes.iconMd))
                    }
                } else if (playlists.isEmpty()) {
                    Text("还没有歌单，先创建一个吧", modifier = Modifier.padding(vertical = TaotaoSpacing.md))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        playlists.forEach { playlist ->
                            Row(
                                Modifier.fillMaxWidth().clip(TaotaoShapes.medium)
                                    .clickable { onPick(playlist) }
                                    .padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlaylistCover(coverUrl = playlist.coverUrl, size = TaotaoSizes.artworkRow, shape = TaotaoShapes.small)
                                Column(Modifier.weight(1f).padding(start = TaotaoSpacing.xs)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${playlist.songCount} 首",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(TaotaoSizes.iconXs))
                Spacer(Modifier.width(TaotaoSpacing.xxs))
                Text("新建歌单")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 新建/编辑歌单资料对话框。 */
@Composable
fun PlaylistEditorDialog(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    supportingText = { Text("${name.length}/80") },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("简介（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    supportingText = { Text("${description.length}/1000") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim()) },
                enabled = name.trim().isNotEmpty() && name.trim().length <= 80,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 歌单封面：优先展示服务端返回的封面（歌单自己的封面为空时会兜底为
 * 歌单内按曲目顺序第一张歌曲封面），没有封面时退回 primaryContainer 音符占位。
 * 用主题容器角色而不是透明度叠加：14% 珊瑚色在亮色白底上会淡到近乎白色。
 */
@Composable
private fun PlaylistCover(coverUrl: String?, size: Dp, shape: Shape = TaotaoShapes.medium) {
    if (coverUrl.isNullOrBlank()) {
        Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size / 2)) }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(coverUrl)
                .crossfade(AnimationDurations.FADE)
                .build(),
            contentDescription = "歌单封面",
            modifier = Modifier.size(size).clip(shape),
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: TencentMusicApi.Playlist,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(TaotaoShapes.card)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(TaotaoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(coverUrl = playlist.coverUrl, size = TaotaoSizes.artworkRow)
        Column(Modifier.weight(1f).padding(horizontal = TaotaoSpacing.md)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (playlist.description.isBlank()) "${playlist.songCount} 首歌曲" else "${playlist.songCount} 首 · ${playlist.description}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = TaotaoSpacing.xxs),
            )
        }
        IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "重命名") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "删除", tint = TaotaoCoral) }
    }
}

@Composable
private fun PlaylistEmptyState(error: String?, onRefresh: (() -> Unit)?, onCreate: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(bottom = TaotaoSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(TaotaoSizes.stateIcon))
        Text(
            if (error.isNullOrBlank()) "还没有云端歌单" else "歌单加载失败",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = TaotaoSpacing.sm),
        )
        Text(
            error ?: "创建歌单后会在所有设备同步",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = TaotaoSpacing.xxs),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
            modifier = Modifier.padding(top = TaotaoSpacing.md),
        ) {
            if (onRefresh != null) TextButton(onClick = onRefresh) { Text("重新加载") }
            if (onCreate != null) TextButton(onClick = onCreate) { Text("新建歌单") }
        }
    }
}
