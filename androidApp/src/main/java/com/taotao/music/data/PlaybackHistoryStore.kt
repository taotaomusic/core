package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只保存在当前设备的最近播放展示缓存。
 *
 * 云端统计以服务端为准，本地缓存只为首屏离线显示。缓存按已认证用户 ID 分桶，避免账号 A
 * 退出后账号 B 在网络同步前短暂看到 A 的歌曲和听歌数据。旧版本的无账号全局 key 不迁移，
 * 因为无法安全判断它属于哪个账号。
 */
class PlaybackHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("playback_history", Context.MODE_PRIVATE)

    fun read(accountId: Long?): List<PlaybackHistoryEntry> {
        val key = entriesKey(accountId) ?: return emptyList()
        return runCatching {
            val array = JSONArray(preferences.getString(key, null) ?: return emptyList())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val song = SongCodec.decode(item.optString("song")) ?: return@mapNotNull null
                PlaybackHistoryEntry(
                    song = song,
                    playedAtMillis = item.optLong("playedAtMillis").coerceAtLeast(0L),
                    firstPlayedAtMillis = item.optLong("firstPlayedAtMillis").coerceAtLeast(0L),
                    playCount = item.optInt("playCount", 0).coerceAtLeast(0),
                    completedCount = item.optInt("completedCount", 0).coerceAtLeast(0),
                    totalListenedMs = item.optLong("totalListenedMs", 0).coerceAtLeast(0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun record(
        accountId: Long?,
        song: Song,
        playedAtMillis: Long = System.currentTimeMillis(),
    ): List<PlaybackHistoryEntry> {
        val safeAccountId = accountId.validAccountId() ?: return emptyList()
        val existing = read(safeAccountId)
        val songKey = keyOf(song)
        // 本地先把歌曲移动到最近播放首位时，不能抹掉上一次云端同步带回的累计统计。
        // 弱网下用户会先看到本地乐观更新，保留这些字段才能避免“播放次数突然归零”。
        val previous = existing.firstOrNull { keyOf(it.song) == songKey }
        val refreshed = PlaybackHistoryPolicy.refreshEntry(previous, song, playedAtMillis)
        val updated = buildList {
            add(refreshed)
            addAll(existing.filterNot { keyOf(it.song) == songKey })
        }.take(MAX_ENTRIES)
        write(safeAccountId, updated)
        return updated
    }

    fun clear(accountId: Long?) {
        entriesKey(accountId)?.let { key -> preferences.edit().remove(key).commit() }
    }

    /** 云端同步后覆盖当前账号的展示缓存；仍由同一首歌去重规则保证历史不会重复。 */
    fun replace(accountId: Long?, entries: List<PlaybackHistoryEntry>): List<PlaybackHistoryEntry> {
        val safeAccountId = accountId.validAccountId() ?: return emptyList()
        val unique = entries.sortedByDescending { it.playedAtMillis }
            .fold(linkedMapOf<String, PlaybackHistoryEntry>()) { result, entry ->
                result.putIfAbsent(keyOf(entry.song), entry)
                result
            }
            .values
            .take(MAX_ENTRIES)
        write(safeAccountId, unique)
        return unique
    }

    private fun write(accountId: Long, entries: List<PlaybackHistoryEntry>) {
        val encoded = JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("song", SongCodec.encode(entry.song))
                    put("playedAtMillis", entry.playedAtMillis)
                    put("firstPlayedAtMillis", entry.firstPlayedAtMillis)
                    put("playCount", entry.playCount)
                    put("completedCount", entry.completedCount)
                    put("totalListenedMs", entry.totalListenedMs)
                })
            }
        }
        preferences.edit().putString(entriesKey(accountId)!!, encoded.toString()).commit()
    }

    private fun entriesKey(accountId: Long?): String? = accountId.validAccountId()?.let { "account.$it.entries" }

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
