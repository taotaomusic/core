package com.taotao.music.ui.common

import com.taotao.music.model.Song
import java.util.Locale

/**
 * 去掉重复条目，保留首次出现的顺序。
 *
 * 判重用「ID + 标题 + 歌手」而不是只看 ID：上游偶尔会把同一行返回两遍（线上崩过一次，
 * `LazyColumn` 对重复 key 直接抛 IllegalArgumentException），但同一个 ID 配不同标题
 * 也是可能的，只看 ID 会把两首真正不同的歌合并成一条。
 *
 * 列表的 key 用的是同一个组合，所以只要过了这一层，key 一定唯一。
 */
internal fun dedupeSongs(songs: List<Song>): List<Song> {
    val seen = HashSet<String>(songs.size)
    return songs.filter { seen.add(songKeyOf(it)) }
}

/** 列表项的稳定唯一键。必须与 [dedupeSongs] 的判重口径一致。 */
fun songKeyOf(song: Song): String = "${playbackIdentity(song)}#${song.title}#${song.artist}"

/** 播放/歌单列表的稳定身份；mid-only 歌曲不能都折叠成 remoteId=null。 */
internal fun playbackIdentity(song: Song): String = buildString {
    append(playbackSource(song))
    append(':')
    append(playbackSongId(song) ?: song.audioUri.orEmpty())
}

internal fun playbackSource(song: Song): String = song.source.trim().ifBlank { "tencent" }.lowercase(Locale.ROOT)

/** 云端播放统计使用字符串身份，数字 ID 缺失时回退到上游 mid。 */
internal fun playbackSongId(song: Song): String? = song.remoteId?.takeIf { it > 0L }?.toString()
    ?: song.mid?.trim()?.takeIf { it.isNotBlank() }

internal fun playbackStableKey(song: Song): String? = playbackSongId(song)?.let { "${playbackSource(song)}:$it" }

/** 收藏、歌单和播放列表统一按来源 + 数字 ID/mid 判断同一首歌。 */
internal fun Song.sameSongIdentity(other: Song): Boolean {
    if (!source.equals(other.source, ignoreCase = true)) return false
    val left = buildSet {
        remoteId?.takeIf { it > 0L }?.let { add(it.toString()) }
        mid?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    }
    val right = buildSet {
        other.remoteId?.takeIf { it > 0L }?.let { add(it.toString()) }
        other.mid?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    }
    return left.isNotEmpty() && left.intersect(right).isNotEmpty()
}
