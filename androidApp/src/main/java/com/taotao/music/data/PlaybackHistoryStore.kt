package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只保存在当前设备的播放记录。
 *
 * 同一首歌再次播放时移动到最前面，不累计重复行；这样「最近播放」表达的是最近听过什么，
 * 而不是把暂停后继续播放也记成一条新记录。最多保留 [MAX_ENTRIES] 首，避免偏好文件无限增长。
 */
class PlaybackHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_history", Context.MODE_PRIVATE)

    fun read(): List<PlaybackHistoryEntry> = runCatching {
        val array = JSONArray(preferences.getString(KEY_ENTRIES, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val song = SongCodec.decode(item.optString("song")) ?: return@mapNotNull null
            PlaybackHistoryEntry(
                song = song,
                playedAtMillis = item.optLong("playedAtMillis").coerceAtLeast(0L),
            )
        }
    }.getOrDefault(emptyList())

    fun record(song: Song, playedAtMillis: Long = System.currentTimeMillis()): List<PlaybackHistoryEntry> {
        val songKey = keyOf(song)
        val updated = buildList {
            add(PlaybackHistoryEntry(song, playedAtMillis.coerceAtLeast(0L)))
            addAll(read().filterNot { keyOf(it.song) == songKey })
        }.take(MAX_ENTRIES)
        write(updated)
        return updated
    }

    fun clear() {
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    private fun write(entries: List<PlaybackHistoryEntry>) {
        val encoded = JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("song", SongCodec.encode(entry.song))
                    put("playedAtMillis", entry.playedAtMillis)
                })
            }
        }
        preferences.edit().putString(KEY_ENTRIES, encoded.toString()).apply()
    }

    private fun keyOf(song: Song): String = song.remoteId?.let { "remote:$it" }
        ?: "local:${song.audioUri.orEmpty()}#${song.title}#${song.artist}"

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 50
    }
}

data class PlaybackHistoryEntry(
    val song: Song,
    val playedAtMillis: Long,
)
