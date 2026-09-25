package com.taotao.music.ui.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.Song
import com.taotao.music.ui.library.MineLibrarySection
import com.taotao.music.ui.playlist.PlaylistEditorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云端歌单域状态与操作：列表、详情、选择器、编辑器与全部写操作。
 *
 * 所有请求都绑定「当前账号 + 代次」：退出或换号时代次递增并取消在途请求，
 * 旧账号的响应一律拒绝回写。
 */
internal class PlaylistState(
    private val scope: CoroutineScope,
    private val musicApi: TencentMusicApi,
    private val accountIdProvider: () -> Long?,
    private val signedInProvider: () -> Boolean,
    private val onOpenSection: (MineLibrarySection) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    /** 云端歌单列表；详情单独缓存，避免列表页每次重组都请求网络。 */
    var items by mutableStateOf(emptyList<TencentMusicApi.Playlist>())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<TencentMusicApi.Playlist?>(null)
    var showPicker by mutableStateOf(false)
    var pickerSong by mutableStateOf<Song?>(null)
    var showSongPicker by mutableStateOf(false)
    var songPickerTarget by mutableStateOf<TencentMusicApi.Playlist?>(null)
    // 从歌曲菜单新建歌单时暂存原歌曲；创建成功后重新打开选择器，避免两个弹窗叠在一起。
    var pendingSongAfterCreate by mutableStateOf<Song?>(null)
    var editorMode by mutableStateOf<PlaylistEditorMode?>(null)
    var busy by mutableStateOf(false)
    // 歌单请求绑定当前账号代际；退出/换号时取消旧请求并拒绝其回写。
    private var requestGeneration by mutableIntStateOf(0)
    private var requestJob by mutableStateOf<Job?>(null)

    private fun beginRequest(): Int {
        requestJob?.cancel()
        requestGeneration += 1
        loading = false
        busy = false
        return requestGeneration
    }

    private fun isCurrentRequest(account: Long, generation: Int): Boolean =
        signedInProvider() && accountIdProvider() == account && requestGeneration == generation

    private fun finishRequest(generation: Int) {
        if (requestGeneration == generation) requestJob = null
    }

    /** 账号退出或会话失效时清掉歌单内存快照，避免下一账号看到上一账号的云端资料。 */
    fun clearState() {
        requestGeneration += 1
        requestJob?.cancel()
        requestJob = null
        items = emptyList()
        loading = false
        error = null
        selected = null
        showPicker = false
        pickerSong = null
        showSongPicker = false
        songPickerTarget = null
        pendingSongAfterCreate = null
        editorMode = null
        busy = false
    }

    /** 关闭所有叠放状态（详情、选择器与编辑器），不清列表数据；切底部标签时用。 */
    fun closeAll() {
        selected = null
        showPicker = false
        pickerSong = null
        showSongPicker = false
        songPickerTarget = null
        editorMode = null
        pendingSongAfterCreate = null
    }

    /** 打开歌单库分区并顺手刷新列表。 */
    fun openLibrary() {
        selected = null
        onOpenSection(MineLibrarySection.PLAYLISTS)
        refresh()
    }

    fun refresh() {
        val account = accountIdProvider() ?: return
        if (loading) return
        val generation = beginRequest()
        loading = true
        error = null
        val job = scope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { musicApi.listPlaylists() } }
                    .onSuccess { result ->
                        if (isCurrentRequest(account, generation)) items = result
                    }
                    .onFailure { err ->
                        if (isCurrentRequest(account, generation)) {
                            error = err.message ?: "歌单加载失败"
                        }
                    }
            } finally {
                if (isCurrentRequest(account, generation)) {
                    loading = false
                    finishRequest(generation)
                }
            }
        }
        requestJob = job
    }

    /** 打开歌单详情时重新读取歌曲，列表元数据仍保留以便快速返回。 */
    fun open(playlist: TencentMusicApi.Playlist) {
        selected = playlist
        val account = accountIdProvider() ?: return
        val generation = beginRequest()
        val job = scope.launch {
            try {
                val detail = runCatching { withContext(Dispatchers.IO) { musicApi.getPlaylist(playlist.id) } }
                    .getOrElse {
                        if (isCurrentRequest(account, generation)) {
                            error = it.message ?: "歌单读取失败"
                        }
                        return@launch
                    }
                if (isCurrentRequest(account, generation) && items.any { it.id == detail.id }) {
                    items = items.map { if (it.id == detail.id) detail else it }
                    selected = detail
                }
            } finally {
                finishRequest(generation)
            }
        }
        requestJob = job
    }

    fun saveEditor(name: String, description: String) {
        if (name.isBlank() || busy) return
        val mode = editorMode ?: return
        val account = accountIdProvider() ?: return
        val generation = beginRequest()
        busy = true
        val job = scope.launch {
            try {
                runCatching {
                    withContext(Dispatchers.IO) {
                        when (mode) {
                            PlaylistEditorMode.CREATE -> musicApi.createPlaylist(name, description)
                            is PlaylistEditorMode.EDIT -> musicApi.updatePlaylist(mode.playlist.id, name, description)
                        }
                    }
                }.onSuccess { result ->
                    if (!isCurrentRequest(account, generation)) return@onSuccess
                    val pendingSong = if (mode == PlaylistEditorMode.CREATE) pendingSongAfterCreate else null
                    items = when (mode) {
                        PlaylistEditorMode.CREATE -> listOf(result) + items
                        is PlaylistEditorMode.EDIT -> items.map { if (it.id == result.id) result else it }
                    }
                    if (selected?.id == result.id) selected = result
                    editorMode = null
                    pendingSongAfterCreate = null
                    // 歌曲菜单里的“新建歌单”完成后回到原来的歌单选择器，
                    // 用户只需再点一次新歌单即可完成添加，不会丢掉刚才的歌曲。
                    if (pendingSong != null) {
                        pickerSong = pendingSong
                        showPicker = true
                    }
                    onMessage(if (mode == PlaylistEditorMode.CREATE) "歌单已创建" else "歌单已更新")
                }.onFailure {
                    if (isCurrentRequest(account, generation)) onMessage(it.message ?: "歌单保存失败")
                }
            } finally {
                if (isCurrentRequest(account, generation)) {
                    busy = false
                    finishRequest(generation)
                }
            }
        }
        requestJob = job
    }

    fun remove(playlist: TencentMusicApi.Playlist) {
        if (busy) return
        val account = accountIdProvider() ?: return
        val generation = beginRequest()
        busy = true
        val job = scope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { musicApi.deletePlaylist(playlist.id) } }
                    .onSuccess {
                        if (!isCurrentRequest(account, generation)) return@onSuccess
                        items = items.filterNot { it.id == playlist.id }
                        if (selected?.id == playlist.id) selected = null
                        onMessage("已删除歌单「${playlist.name}」")
                    }
                    .onFailure { if (isCurrentRequest(account, generation)) onMessage(it.message ?: "歌单删除失败") }
            } finally {
                if (isCurrentRequest(account, generation)) {
                    busy = false
                    finishRequest(generation)
                }
            }
        }
        requestJob = job
    }

    fun addSong(playlist: TencentMusicApi.Playlist, song: Song) {
        if (busy) return
        val account = accountIdProvider() ?: return
        val generation = beginRequest()
        busy = true
        val job = scope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { musicApi.addSongToPlaylist(playlist.id, song) } }
                    .onSuccess { updated ->
                        if (!isCurrentRequest(account, generation)) return@onSuccess
                        items = items.map { if (it.id == updated.id) updated.copy(songs = emptyList()) else it }
                        if (selected?.id == updated.id) selected = updated
                        showPicker = false
                        pickerSong = null
                        showSongPicker = false
                        songPickerTarget = null
                        onMessage("已加入「${updated.name}」")
                    }
                    .onFailure { if (isCurrentRequest(account, generation)) onMessage(it.message ?: "加入歌单失败") }
            } finally {
                if (isCurrentRequest(account, generation)) {
                    busy = false
                    finishRequest(generation)
                }
            }
        }
        requestJob = job
    }

    /** 从歌曲列表打开“加入歌单”选择器，并确保详情页的另一套选择器已关闭。 */
    fun requestAddSong(song: Song) {
        if (!signedInProvider()) {
            onMessage("登录后才能加入歌单")
            return
        }
        if (TencentMusicApi.playlistSongId(song) == null) return
        showSongPicker = false
        songPickerTarget = null
        pickerSong = song
        showPicker = true
        if (items.isEmpty()) refresh()
    }

    /** 歌单详情页的“添加歌曲”：打开候选选择器并指向当前详情。 */
    fun openSongPickerFor(playlist: TencentMusicApi.Playlist) {
        showPicker = false
        pickerSong = null
        songPickerTarget = playlist
        showSongPicker = true
    }

    fun removeSong(item: TencentMusicApi.PlaylistSong) {
        val playlist = selected ?: return
        if (busy) return
        val account = accountIdProvider() ?: return
        val generation = beginRequest()
        busy = true
        val job = scope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { musicApi.removeSongFromPlaylist(playlist.id, item) } }
                    .onSuccess { updated ->
                        if (!isCurrentRequest(account, generation)) return@onSuccess
                        selected = updated
                        items = items.map { if (it.id == updated.id) updated.copy(songs = emptyList()) else it }
                    }
                    .onFailure { if (isCurrentRequest(account, generation)) onMessage(it.message ?: "移除歌曲失败") }
            } finally {
                if (isCurrentRequest(account, generation)) {
                    busy = false
                    finishRequest(generation)
                }
            }
        }
        requestJob = job
    }

    fun moveSong(from: Int, to: Int) {
        val playlist = selected ?: return
        if (to !in playlist.songs.indices || from !in playlist.songs.indices) return
        val account = accountIdProvider() ?: return
        val reordered = playlist.songs.toMutableList().apply { add(to, removeAt(from)) }
        // 先更新界面，再把完整稳定键列表交给服务端；失败时回读详情恢复权威顺序。
        // 不再用 busy 拦截连续拖动：拖把已经把行挪到位并回调了 onMove，这里若提前 return，
        // 界面顺序会和权威 selected.songs 岔开，在途请求回写后被拖动的行会弹回原位。
        // 并发安全由代次令牌兜底：beginRequest 会取消上一在途请求并递增代次，
        // 陈旧响应一律被 isCurrentRequest 拒写；reordered 基于已乐观更新的 selected，连续拖动可叠加。
        selected = playlist.copy(songs = reordered)
        val generation = beginRequest()
        busy = true
        val job = scope.launch {
            try {
                runCatching { withContext(Dispatchers.IO) { musicApi.reorderPlaylist(playlist.id, reordered) } }
                    .onSuccess { updated -> if (isCurrentRequest(account, generation)) selected = updated }
                    .onFailure {
                        if (!isCurrentRequest(account, generation)) return@onFailure
                        onMessage(it.message ?: "歌单排序失败")
                        runCatching { withContext(Dispatchers.IO) { musicApi.getPlaylist(playlist.id) } }
                            .onSuccess { detail -> if (isCurrentRequest(account, generation)) selected = detail }
                    }
            } finally {
                if (isCurrentRequest(account, generation)) {
                    busy = false
                    finishRequest(generation)
                }
            }
        }
        requestJob = job
    }
}
