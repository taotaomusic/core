package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.Song

/** 保存最近的播放队列及进度，应用重新打开时恢复整条队列而不只是当前一首。 */
class PlaybackStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    fun save(queue: List<Song>, index: Int, positionMs: Int) {
        if (queue.isEmpty()) return
        preferences.edit()
            .putString(KEY_QUEUE, SongCodec.encodeList(queue))
            .putInt(KEY_INDEX, index.coerceIn(queue.indices))
            .putInt(KEY_POSITION, positionMs.coerceAtLeast(0))
            .remove(KEY_LEGACY_SONG)
            .apply()
    }

    fun read(): SavedPlaybackState? {
        val queue = SongCodec.decodeList(preferences.getString(KEY_QUEUE, null))
        if (queue.isNotEmpty()) {
            return SavedPlaybackState(
                queue = queue,
                index = preferences.getInt(KEY_INDEX, 0).coerceIn(queue.indices),
                positionMs = preferences.getInt(KEY_POSITION, 0).coerceAtLeast(0),
            )
        }
        // 兼容旧版本只存单曲的格式，读到后按单曲队列恢复。
        val legacy = SongCodec.decode(preferences.getString(KEY_LEGACY_SONG, null)) ?: return null
        val legacyPosition = runCatching {
            org.json.JSONObject(preferences.getString(KEY_LEGACY_SONG, null)!!).optInt("positionMs")
        }.getOrDefault(0)
        return SavedPlaybackState(listOf(legacy), 0, legacyPosition.coerceAtLeast(0))
    }

    fun clear() {
        preferences.edit().remove(KEY_QUEUE).remove(KEY_INDEX).remove(KEY_POSITION).remove(KEY_LEGACY_SONG).apply()
    }

    private companion object {
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "queue_index"
        const val KEY_POSITION = "position_ms"
        const val KEY_LEGACY_SONG = "song"
    }
}

data class SavedPlaybackState(
    val queue: List<Song>,
    val index: Int,
    val positionMs: Int,
) {
    /** 当前曲目，便于界面直接取用。 */
    val song: Song get() = queue[index.coerceIn(queue.indices)]
}
