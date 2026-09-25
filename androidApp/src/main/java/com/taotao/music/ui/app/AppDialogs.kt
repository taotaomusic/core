package com.taotao.music.ui.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.AudioQuality
import com.taotao.music.ui.account.AnnouncementDialog
import com.taotao.music.ui.player.QualityChoice
import com.taotao.music.ui.player.QualitySheet
import com.taotao.music.ui.player.QualitySheetKind
import com.taotao.music.ui.player.SleepTimerSheet
import com.taotao.music.ui.player.staticQualityChoices
import com.taotao.music.ui.playlist.PlaylistEditorDialog
import com.taotao.music.ui.playlist.PlaylistEditorMode
import com.taotao.music.ui.playlist.PlaylistPickerDialog
import com.taotao.music.ui.playlist.PlaylistSongPickerDialog
import com.taotao.music.ui.theme.TaotaoCoral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局弹窗与底部面板装配：公告、定时关闭、歌单三件套、删除确认与音质面板。
 * 显示状态全部来自全局状态容器；这里只负责把状态桥接到各弹窗组件。
 */
@Composable
internal fun TaotaoAppDialogs(state: TaotaoAppState) {
    if (state.showAnnouncementDialog) {
        AnnouncementDialog(announcements = state.announcements, onDismiss = { state.showAnnouncementDialog = false })
    }
    if (state.showSleepTimerDialog) {
        SleepTimerSheet(
            remainingMs = state.audioPlayer.sleepTimerRemainingMs,
            waitingSongEnd = state.audioPlayer.sleepTimerWaitingSongEnd,
            lastMinutes = state.sleepTimerLastMinutes,
            waitForSongEnd = state.sleepTimerWaitForSongEnd,
            onSet = { minutes ->
                state.sleepTimerLastMinutes = minutes
                state.sleepTimerStore.setLastMinutes(minutes)
                state.audioPlayer.setSleepTimer(minutes, state.sleepTimerWaitForSongEnd)
                state.showSleepTimerDialog = false
                state.message = "已设置 ${minutes} 分钟后停止播放"
            },
            onCancelTimer = {
                state.audioPlayer.cancelSleepTimer()
                state.showSleepTimerDialog = false
                state.message = "已取消定时关闭"
            },
            onToggleWaitForSongEnd = { wait ->
                state.sleepTimerWaitForSongEnd = wait
                state.sleepTimerStore.setWaitForSongEnd(wait)
                state.audioPlayer.setSleepTimerWaitForSongEnd(wait)
            },
            onDismiss = { state.showSleepTimerDialog = false },
        )
    }
    state.playlist.pickerSong?.let { song ->
        if (state.playlist.showPicker) {
            PlaylistPickerDialog(
                song = song,
                playlists = state.playlist.items,
                loading = state.playlist.loading || state.playlist.busy,
                onDismiss = {
                    state.playlist.showPicker = false
                    state.playlist.pickerSong = null
                },
                onPick = { playlist -> state.playlist.addSong(playlist, song) },
                onCreate = {
                    state.playlist.pendingSongAfterCreate = song
                    state.playlist.showPicker = false
                    state.playlist.editorMode = PlaylistEditorMode.CREATE
                },
            )
        }
    }
    state.playlist.songPickerTarget?.let { target ->
        if (state.playlist.showSongPicker) {
            PlaylistSongPickerDialog(
                candidates = state.playlistCandidates,
                existingSongs = target.songs,
                loading = state.playlist.busy,
                onDismiss = {
                    state.playlist.showSongPicker = false
                    state.playlist.songPickerTarget = null
                },
                onPick = { song ->
                    state.playlist.showSongPicker = false
                    state.playlist.songPickerTarget = null
                    state.playlist.addSong(target, song)
                },
            )
        }
    }
    state.playlist.editorMode?.let { mode ->
        PlaylistEditorDialog(
            title = if (mode == PlaylistEditorMode.CREATE) "新建歌单" else "编辑歌单",
            initialName = (mode as? PlaylistEditorMode.EDIT)?.playlist?.name.orEmpty(),
            initialDescription = (mode as? PlaylistEditorMode.EDIT)?.playlist?.description.orEmpty(),
            onDismiss = {
                state.playlist.editorMode = null
                state.playlist.pendingSongAfterCreate = null
            },
            onConfirm = { name, description -> state.playlist.saveEditor(name, description) },
        )
    }
    state.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { state.pendingDelete = null },
            title = { Text("删除下载") },
            text = { Text("将删除「${target.title}」的音频、封面和歌词，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    state.pendingDelete = null
                    state.deleteDownloaded(target)
                }) { Text("删除", color = TaotaoCoral) }
            },
            dismissButton = { TextButton(onClick = { state.pendingDelete = null }) { Text("取消") } },
        )
    }

    // 下载前的音质面板：查一次 /song/info 拿到这首歌真实存在的档位与体积。
    val pendingDownload = state.downloadTarget
    if (pendingDownload != null) {
        LaunchedEffect(pendingDownload.first.remoteId) {
            state.qualitiesLoading = true
            state.songQualities = runCatching {
                withContext(Dispatchers.IO) { state.musicApi.requestQualities(pendingDownload.first) }
            }.map { options ->
                options.map { QualityChoice(it.quality, it.label, it.size) }
            }.getOrDefault(staticQualityChoices())
            state.qualitiesLoading = false
        }
        QualitySheet(
            title = "下载音质",
            choices = state.songQualities,
            selected = state.downloadQuality.value,
            loading = state.qualitiesLoading,
            note = pendingDownload.first.title,
            onPick = { picked ->
                val (song, index) = pendingDownload
                state.downloadTarget = null
                state.startDownload(song, index, picked)
            },
            onDismiss = { state.downloadTarget = null },
        )
    }

    when (state.qualitySheet) {
        QualitySheetKind.CURRENT_SONG -> {
            val current = state.playbackSongs.getOrNull(state.selectedIndex)
            LaunchedEffect(current?.remoteId) {
                if (current == null) return@LaunchedEffect
                state.qualitiesLoading = true
                state.songQualities = runCatching {
                    withContext(Dispatchers.IO) { state.musicApi.requestQualities(current) }
                }.map { options -> options.map { QualityChoice(it.quality, it.label, it.size) } }
                    .getOrDefault(staticQualityChoices())
                state.qualitiesLoading = false
            }
            QualitySheet(
                title = "音质",
                choices = state.songQualities,
                selected = current?.audioUri?.let { TencentMusicApi.parsePlaceholder(it)?.second }
                    ?: state.playbackQuality.value,
                loading = state.qualitiesLoading,
                note = "只对这一首生效，不改默认设置。",
                onPick = { picked ->
                    state.qualitySheet = null
                    state.switchCurrentQuality(picked)
                },
                onDismiss = { state.qualitySheet = null },
            )
        }
        QualitySheetKind.PLAYBACK_DEFAULT -> QualitySheet(
            title = "默认播放音质",
            choices = staticQualityChoices(),
            selected = state.playbackQuality.value,
            note = "越高越费流量。某首歌没有所选档位时会自动降到最接近的可用档。",
            onPick = { picked ->
                state.playbackQuality = AudioQuality.of(picked)
                state.qualityStore.setPlaybackQuality(state.playbackQuality)
                state.qualitySheet = null
            },
            onDismiss = { state.qualitySheet = null },
        )
        QualitySheetKind.DOWNLOAD_DEFAULT -> QualitySheet(
            title = "默认下载音质",
            choices = staticQualityChoices(),
            selected = state.downloadQuality.value,
            note = "下载只花一次流量，可以选得比播放更高。",
            onPick = { picked ->
                state.downloadQuality = AudioQuality.of(picked)
                state.qualityStore.setDownloadQuality(state.downloadQuality)
                state.qualitySheet = null
            },
            onDismiss = { state.qualitySheet = null },
        )
        null -> Unit
    }
}
