package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.Song
import org.json.JSONObject

/** 保存最近播放歌曲及其进度，应用重新打开时恢复页面状态。 */
class PlaybackStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    fun save(song: Song, positionMs: Int) {
        val data = JSONObject().apply {
            put("title", song.title); put("artist", song.artist); put("duration", song.duration)
            put("color", song.color); put("audioUri", song.audioUri); put("remoteId", song.remoteId)
            put("coverUri", song.coverUri); put("lyricUri", song.lyricUri); put("album", song.album)
            put("subtitle", song.subtitle); put("releaseTime", song.releaseTime); put("positionMs", positionMs)
        }
        preferences.edit().putString("song", data.toString()).apply()
    }

    fun read(): SavedPlaybackState? = runCatching {
        val data = JSONObject(preferences.getString("song", null) ?: return null)
        val song = Song(
            data.getString("title"),
            data.getString("artist"),
            data.getString("duration"),
            data.getLong("color"),
            data.optText("audioUri"),
            remoteId = data.optLong("remoteId").takeIf { it > 0 },
            coverUri = data.optText("coverUri"),
            lyricUri = data.optText("lyricUri"),
            album = data.optString("album"),
            subtitle = data.optString("subtitle"),
            releaseTime = data.optString("releaseTime"),
        )
        SavedPlaybackState(song, data.optInt("positionMs").coerceAtLeast(0))
    }.getOrNull()

    fun clear() { preferences.edit().remove("song").apply() }

    /** 可空字段统一处理：JSONObject 存入 null 会写成 JSONObject.NULL，取出来是字符串 "null"。 */
    private fun JSONObject.optText(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }
}

data class SavedPlaybackState(
    val song: Song,
    val positionMs: Int,
)
