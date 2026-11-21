package com.taotao.music.data

import com.taotao.music.model.Song
import java.util.Locale

/**
 * 在本机下载列表里查找同一首歌。
 *
 * 云端歌单不会保存设备私有的 `file:` 地址，播放前必须用稳定身份重新绑定本地文件。
 * 优先匹配数字 ID；旧歌单或上游只给 mid 时回退到 mid。音源始终参与匹配，避免腾讯和
 * 网易碰巧使用相同数字 ID 时串歌。
 */
internal fun findDownloadedSong(song: Song, downloadedSongs: List<Song>): Song? {
    val source = song.normalizedSource()
    val remoteId = song.remoteId?.takeIf { it > 0L }
    val mid = song.mid?.trim()?.takeIf { it.isNotBlank() }
    if (remoteId == null && mid == null) return null

    return downloadedSongs.firstOrNull { downloaded ->
        downloaded.audioUri?.startsWith("file:", ignoreCase = true) == true &&
            downloaded.normalizedSource() == source &&
            (
                (remoteId != null && downloaded.remoteId == remoteId) ||
                    (mid != null && downloaded.mid?.trim() == mid)
                )
    }
}

/**
 * 绑定本地文件时合并封面，口径与歌单详情页一致：本地文件优先（离线也有封面），
 * 本地缺失时退回在线 Song 刚从服务端拿到的封面地址，两者都没有才是真正的无封面。
 * 此前的实现只保留本地结果，本地封面当年没下下来时，明明在线地址是好的也一起丢了。
 */
internal fun Song.withCoverFallback(local: Song): Song = copy(
    favorited = favorited,
    coverUri = local.coverUri?.takeIf { it.startsWith("file:", ignoreCase = true) }
        ?: coverUri?.takeIf { it.isNotBlank() }
        ?: local.coverUri,
)

/** 把来源队列中的每一项都绑定到本机下载文件；未下载的项目保持原样走在线播放。 */
internal fun bindDownloadedSongs(queue: List<Song>, downloadedSongs: List<Song>): List<Song> =
    queue.map { song ->
        findDownloadedSong(song, downloadedSongs)
            // 绑到本地文件只该换音频与歌词，不该把在线 Song 手里的好封面一起丢掉。
            ?.let { song.withCoverFallback(it) } ?: song
    }

private fun Song.normalizedSource(): String =
    source.trim().ifBlank { "tencent" }.lowercase(Locale.ROOT)
