package com.taotao.music.data

import android.content.Context
import com.taotao.music.data.db.LocalDb
import com.taotao.music.data.db.PlaybackHistoryRow
import com.taotao.music.model.Song

/**
 * 只保存在当前设备的最近播放展示缓存，落在本地 Room 库的 playback_history 表。
 *
 * 云端统计以服务端为准，本地缓存只为首屏离线显示。缓存按已认证用户 ID 分桶，避免账号 A
 * 退出后账号 B 在网络同步前短暂看到 A 的歌曲和听歌数据。旧版本的无账号全局 key 不迁移，
 * 因为无法安全判断它属于哪个账号。
 *
 * 相比旧的整串 JSON：同曲更新变成单行 upsert（不再全量重写），单行损坏只丢一首。
 * 所有方法都做磁盘读写，禁止在主线程调用。
 */
class PlaybackHistoryStore(context: Context) {
    private val db by lazy { LocalDb.ready(context) }

    fun read(accountId: Long?): List<PlaybackHistoryEntry> {
        val account = accountId.validAccountId() ?: return emptyList()
        return db.playbackHistoryDao().listByAccount(account).mapNotNull { row ->
            val song = SongCodec.decode(row.songJson) ?: return@mapNotNull null
            PlaybackHistoryEntry(
                song = song,
                playedAtMillis = row.playedAt,
                firstPlayedAtMillis = row.firstPlayedAt,
                playCount = row.playCount,
                completedCount = row.completedCount,
                totalListenedMs = row.totalListenedMs,
            )
        }
    }

    fun record(
        accountId: Long?,
        song: Song,
        playedAtMillis: Long = System.currentTimeMillis(),
    ): List<PlaybackHistoryEntry> {
        val account = accountId.validAccountId() ?: return emptyList()
        val songKey = keyOf(song)
        // 本地先把歌曲移动到最近播放首位时，不能抹掉上一次云端同步带回的累计统计。
        // 弱网下用户会先看到本地乐观更新，保留这些字段才能避免“播放次数突然归零”。
        val previous = db.playbackHistoryDao().getByKey(account, songKey)?.let { row ->
            SongCodec.decode(row.songJson)?.let { cachedSong ->
                PlaybackHistoryEntry(
                    song = cachedSong,
                    playedAtMillis = row.playedAt,
                    firstPlayedAtMillis = row.firstPlayedAt,
                    playCount = row.playCount,
                    completedCount = row.completedCount,
                    totalListenedMs = row.totalListenedMs,
                )
            }
        }
        val refreshed = PlaybackHistoryPolicy.refreshEntry(previous, song, playedAtMillis)
        db.runInTransaction {
            val dao = db.playbackHistoryDao()
            dao.upsert(refreshed.toRow(account, songKey))
            dao.trimToLimit(account, MAX_ENTRIES)
        }
        return read(account)
    }

    fun clear(accountId: Long?) {
        accountId.validAccountId()?.let { account -> db.playbackHistoryDao().deleteByAccount(account) }
    }

    /** 云端同步后覆盖当前账号的展示缓存；仍由同一首歌去重规则保证历史不会重复。 */
    fun replace(accountId: Long?, entries: List<PlaybackHistoryEntry>): List<PlaybackHistoryEntry> {
        val account = accountId.validAccountId() ?: return emptyList()
        val unique = entries.sortedByDescending { it.playedAtMillis }
            .fold(linkedMapOf<String, PlaybackHistoryEntry>()) { result, entry ->
                result.putIfAbsent(keyOf(entry.song), entry)
                result
            }
            .values
            .take(MAX_ENTRIES)
        db.runInTransaction {
            val dao = db.playbackHistoryDao()
            dao.deleteByAccount(account)
            dao.insertAll(unique.mapIndexed { index, entry -> entry.toRow(account, keyOf(entry.song)) })
        }
        return unique
    }

    private fun PlaybackHistoryEntry.toRow(accountId: Long, songKey: String) = PlaybackHistoryRow(
        accountId = accountId,
        songKey = songKey,
        songJson = SongCodec.encode(song),
        playedAt = playedAtMillis,
        firstPlayedAt = firstPlayedAtMillis,
        playCount = playCount,
        completedCount = completedCount,
        totalListenedMs = totalListenedMs,
    )

    private fun keyOf(song: Song): String {
        val source = song.source.ifBlank { "tencent" }
        val identity = song.remoteId?.takeIf { it > 0L }?.toString()
            ?: song.mid?.trim()?.takeIf { it.isNotBlank() }
            ?: "local:${song.audioUri.orEmpty()}#${song.title}#${song.artist}"
        return "$source:$identity"
    }

    private fun Long?.validAccountId(): Long? = this?.takeIf { it > 0L }

    private companion object {
        /** 最近播放保留 500 首；同曲会原地更新，不会随暂停/续播无限膨胀。 */
        const val MAX_ENTRIES = 500
    }
}

data class PlaybackHistoryEntry(
    val song: Song,
    val playedAtMillis: Long,
    val firstPlayedAtMillis: Long = 0,
    val playCount: Int = 0,
    val completedCount: Int = 0,
    val totalListenedMs: Long = 0,
)

/** 本地乐观更新的纯规则，防止云端统计在移动到最近播放首位时被重置。 */
object PlaybackHistoryPolicy {
    fun refreshEntry(
        previous: PlaybackHistoryEntry?,
        song: Song,
        playedAtMillis: Long,
    ): PlaybackHistoryEntry = PlaybackHistoryEntry(
        song = song,
        playedAtMillis = playedAtMillis.coerceAtLeast(0L),
        firstPlayedAtMillis = previous?.firstPlayedAtMillis ?: 0L,
        playCount = previous?.playCount ?: 0,
        completedCount = previous?.completedCount ?: 0,
        totalListenedMs = previous?.totalListenedMs ?: 0L,
    )
}
