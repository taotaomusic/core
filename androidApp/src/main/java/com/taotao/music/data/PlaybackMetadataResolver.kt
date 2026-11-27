package com.taotao.music.data

import com.taotao.music.model.Song
import com.taotao.music.model.mergePlaybackMetadata
import com.taotao.music.model.metadataKey
import com.taotao.music.model.needsPlaybackMetadata
import com.taotao.music.model.supportsMv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 播放页、冷启动恢复与自动切歌共用的资料补全器。
 * 同曲并发请求合并，成功结果在本次进程内复用；失败保留未知并短暂退避，不写成“没有 MV”。
 */
internal class PlaybackMetadataResolver(
    private val fetch: suspend (Song) -> Song,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry {
        val mutex = Mutex()
        var resolved: Song? = null
        var confirmedMv: Boolean? = null
        var retryAfter = 0L
    }

    private val entries = linkedMapOf<String, Entry>()

    private fun entry(key: String): Entry = synchronized(entries) {
        entries.getOrPut(key) {
            // 只淘汰空闲项，不能让正在补全的同曲重新发起第二份请求。
            if (entries.size >= 128) {
                entries.entries.firstOrNull { !it.value.mutex.isLocked }?.key?.let(entries::remove)
            }
            Entry()
        }
    }

    suspend fun resolve(song: Song): Song {
        val key = song.metadataKey ?: return song
        if (!song.supportsMv) return song
        val entry = entry(key)
        return entry.mutex.withLock {
            entry.resolved?.let {
                return@withLock song.mergePlaybackMetadata(it.copy(hasMv = entry.confirmedMv ?: song.hasMv ?: it.hasMv))
            }
            if (!song.needsPlaybackMetadata) return@withLock song
            if (now() < entry.retryAfter) return@withLock song
            try {
                val resolved = fetch(song)
                if (resolved.metadataKey == key &&
                    (resolved.hasMv != null || resolved.artistId != null || resolved.albumId != null ||
                        resolved.refrainStartMs != null || resolved.refrainEndMs != null)) {
                    entry.resolved = resolved
                    song.mergePlaybackMetadata(resolved)
                } else {
                    entry.retryAfter = now() + 30_000L
                    song
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                entry.retryAfter = now() + 30_000L
                song
            }
        }
    }

    /** 播放接口的结果比旧搜索快照更新，确认无 MV 时也要覆盖旧的 true。 */
    suspend fun rememberMv(song: Song, hasMv: Boolean) {
        if (!song.supportsMv) return
        val entry = entry(song.metadataKey ?: return)
        entry.mutex.withLock {
            entry.resolved = (entry.resolved ?: song).copy(hasMv = hasMv)
            entry.confirmedMv = hasMv
        }
    }
}
