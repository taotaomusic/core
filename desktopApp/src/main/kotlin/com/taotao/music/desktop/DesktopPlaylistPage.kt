package com.taotao.music.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taotao.music.model.AudioQuality
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.TaotaoTypeScale

// ---- 本文件私有的页面 / 组件尺寸 ----
//
// 描述「这一屏占多宽」「这一块多大」的值不进 player-ui 的 TaotaoSizes 刻度 ——
// 那个对象只收三端复用的元素固有尺寸。判据与 DesktopShell.kt 一致。

/** 左侧歌单列表的固定宽度。 */
private val PlaylistListWidth = 270.dp

/** 歌单 / 歌曲列表行里方形图标块的边长。原本两处分别是 34 与 38，收敛为一档。 */
private val ListTileSize = 36.dp

/** 歌曲列表序号列的固定宽度，避免序号到两位数时整行右移。 */
private val SongIndexWidth = 28.dp

/** Windows 云端歌单页。歌单元数据来自服务端，歌曲快照可直接转成播放队列。 */
@Composable
internal fun DesktopPlaylistsPage(
    playlists: List<DesktopMusicApi.Playlist>,
    selectedPlaylistId: Long?,
    selectedPlaylist: DesktopMusicApi.Playlist?,
    availableSongs: List<Song>,
    endpoint: String,
    quality: AudioQuality,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelect: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onAddSong: (Long, Song) -> Unit,
    onRemoveSong: (Long, Song) -> Unit,
    onMoveSong: (Long, List<Song>) -> Unit,
    onPlay: (Song, List<Song>, Int) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DesktopMusicApi.Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<DesktopMusicApi.Playlist?>(null) }
    Row(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.xl, vertical = TaotaoSpacing.xl)) {
        Column(Modifier.width(PlaylistListWidth).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的歌单", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("云端同步 · ${playlists.size} 个歌单", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "刷新歌单") }
                IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "新建歌单") }
            }
            Spacer(Modifier.height(TaotaoSpacing.md))
            if (loading && playlists.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Coral)
            error?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = TaotaoSpacing.xs)) }
            if (playlists.isEmpty() && !loading) {
                Text("还没有云端歌单", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(TaotaoSpacing.sm))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxs)) {
                    items(playlists, key = { it.id }) { playlist ->
                        val selected = playlist.id == selectedPlaylistId
                        Row(
                            Modifier.fillMaxWidth().clip(TaotaoShapes.button)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onSelect(playlist.id) }.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(ListTileSize).clip(TaotaoShapes.small).background(Coral.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.LibraryMusic, "歌单", tint = Coral, modifier = Modifier.size(TaotaoSizes.iconSm))
                            }
                            Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) {
                                Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                Text("${playlist.songCount} 首 · v${playlist.revision}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Divider(Modifier.fillMaxHeight().width(TaotaoStroke.thin).padding(horizontal = TaotaoSpacing.lg))
        if (selectedPlaylist == null) {
            EmptyPlaylistDetail()
        } else {
            val songs = selectedPlaylist.songsAsSongs(quality.value, endpoint)
            PlaylistDetail(
                playlist = selectedPlaylist,
                songs = songs,
                availableSongs = availableSongs,
                loading = loading,
                onRename = { renameTarget = selectedPlaylist },
                onDelete = { deleteTarget = selectedPlaylist },
                onAddSong = { song -> onAddSong(selectedPlaylist.id, song) },
                onRemoveSong = { song -> onRemoveSong(selectedPlaylist.id, song) },
                onMoveSong = { reordered -> onMoveSong(selectedPlaylist.id, reordered) },
                onPlay = onPlay,
            )
        }
    }
    if (showCreate) {
        PlaylistNameDialog(
            title = "新建歌单",
            confirmLabel = "创建",
            onDismiss = { showCreate = false },
            onConfirm = { name -> showCreate = false; onCreate(name) },
        )
    }
    renameTarget?.let { target ->
        PlaylistNameDialog(
            title = "重命名歌单",
            initial = target.name,
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name -> renameTarget = null; onRename(target.id, name) },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除歌单？") },
            text = { Text("将删除“${target.name}”及其中的 ${target.songCount} 首歌曲，其他设备也会同步移除。") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; onDelete(target.id) }) { Text("删除", color = Coral) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PlaylistDetail(
    playlist: DesktopMusicApi.Playlist,
    songs: List<Song>,
    availableSongs: List<Song>,
    loading: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddSong: (Song) -> Unit,
    onRemoveSong: (Song) -> Unit,
    onMoveSong: (List<Song>) -> Unit,
    onPlay: (Song, List<Song>, Int) -> Unit,
) {
    var addExpanded by remember(playlist.id, playlist.revision) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = TaotaoSpacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${songs.size} 首 · 云端版本 ${playlist.revision}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "重命名") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "删除歌单", tint = Coral) }
        }
        if (playlist.description.isNotBlank()) Text(playlist.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = TaotaoSpacing.xs))
        Spacer(Modifier.height(TaotaoSpacing.md))
        Box {
            OutlinedButton(onClick = { addExpanded = !addExpanded }, enabled = availableSongs.isNotEmpty()) {
                Icon(Icons.Default.Add, "加入歌曲", modifier = Modifier.size(TaotaoSizes.iconSm)); Spacer(Modifier.width(TaotaoSpacing.xxs)); Text("从搜索结果加入")
            }
            androidx.compose.material3.DropdownMenu(expanded = addExpanded, onDismissRequest = { addExpanded = false }) {
                val existing = songs.mapNotNull { it.remoteIdentity()?.let { id -> DesktopMusicApi.key(it.source, id) } }.toSet()
                availableSongs.filter { song -> song.remoteIdentity()?.let { DesktopMusicApi.key(song.source, it) } !in existing }.take(40).forEach { song ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Column { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        onClick = { addExpanded = false; onAddSong(song) },
                    )
                }
                if (availableSongs.isEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text("先在音乐页搜索歌曲") }, onClick = { addExpanded = false })
            }
        }
        Spacer(Modifier.height(TaotaoSpacing.sm))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Coral)
        if (songs.isEmpty()) {
            EmptyPlaylistDetail()
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                itemsIndexed(songs, key = { _, song -> DesktopStorage.songKey(song) }) { index, song ->
                    Row(
                        Modifier.fillMaxWidth().clip(TaotaoShapes.button).clickable { onPlay(song, songs, index) }.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(SongIndexWidth))
                        Box(Modifier.size(ListTileSize).clip(TaotaoShapes.small).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.LibraryMusic, "歌曲", tint = Coral, modifier = Modifier.size(TaotaoSizes.iconSm)) }
                        Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${song.artist} · ${song.source}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onPlay(song, songs, index) }) { Icon(Icons.Default.PlayArrow, "播放") }
                        IconButton(onClick = { onMoveSong(songs.toMutableList().apply { if (index > 0) add(index - 1, removeAt(index)) }) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "上移", modifier = Modifier.size(TaotaoSizes.iconSm)) }
                        IconButton(onClick = { onMoveSong(songs.toMutableList().apply { if (index < lastIndex) add(index + 1, removeAt(index)) }) }, enabled = index < songs.lastIndex) { Icon(Icons.Default.ArrowDownward, "下移", modifier = Modifier.size(TaotaoSizes.iconSm)) }
                        IconButton(onClick = { onRemoveSong(song) }) { Icon(Icons.Default.Remove, "移除", tint = Coral, modifier = Modifier.size(TaotaoSizes.iconSm)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("歌单名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.trim().isNotEmpty()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EmptyPlaylistDetail() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.LibraryMusic, "歌单", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(TaotaoSizes.stateIcon))
        Text("选择一个歌单", fontWeight = FontWeight.Bold, style = TaotaoTypeScale.sectionTitle, modifier = Modifier.padding(top = TaotaoSpacing.sm))
        Text("云端歌单会在登录后的 Android 与 Windows 之间同步", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = TaotaoSpacing.xxs))
    }
}
