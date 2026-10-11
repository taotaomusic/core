package com.taotao.music.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.taotao.music.data.SongCodec
import com.taotao.music.model.Song
import java.io.File

/** MediaMetadata extras 中存放整首歌 JSON 的键；AudioPlayer 与 PlaybackService 共用。 */
internal const val EXTRA_SONG = "com.taotao.music.SONG"

/**
 * Song → Media3 MediaItem 的唯一装配点。
 *
 * 元数据 extras 里带整首歌 JSON，播放器时间线能反查出队列（AudioPlayer 的队列
 * 恢复与 PlaybackService 的歌词发布都依赖它）；媒体面板「继续播放」重建队列时
 * 也必须走同一装配，否则 extras 缺失会让界面读不到队列。
 */
internal fun playbackMediaItemOf(song: Song): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artist)
        .setAlbumTitle(song.album.takeIf { it.isNotBlank() })
        .setArtworkUri(song.coverUri?.takeIf { it.isNotBlank() }?.let(Uri::parse))
        // 队列需要能从播放器反查，把整首歌的字段一起带上。
        .setExtras(Bundle().apply { putString(EXTRA_SONG, SongCodec.encode(song)) })
        .build()
    return MediaItem.Builder()
        .setUri(song.audioUri)
        .setMediaId(song.remoteId?.toString() ?: song.audioUri.orEmpty())
        .setMimeType(mimeTypeOf(song.audioUri.orEmpty()))
        .setMediaMetadata(metadata)
        .build()
}

/** 离线文件可能下载中断，过小的文件直接跳过，避免播放器反复报错。 */
internal fun isPlayableFile(uri: String): Boolean {
    val localFile = Uri.parse(uri).path?.let(::File)
    return localFile?.isFile == true && localFile.length() >= 4_096L
}

internal fun mimeTypeOf(uri: String): String? = when (uri.substringBefore('?').substringAfterLast('.').lowercase()) {
    "mp3" -> MimeTypes.AUDIO_MPEG
    "m4a" -> MimeTypes.AUDIO_MP4
    "aac" -> MimeTypes.AUDIO_AAC
    "flac" -> MimeTypes.AUDIO_FLAC
    "ogg" -> MimeTypes.AUDIO_OGG
    "wav" -> MimeTypes.AUDIO_WAV
    else -> null
}
