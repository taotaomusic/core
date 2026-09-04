package com.taotao.music.data

import com.taotao.music.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Song] 与 JSON 的互转。
 *
 * 播放队列既要写进 SharedPreferences（冷启动恢复），又要塞进 MediaMetadata 的 extras
 * （界面重建但播放服务仍在时，从播放器反查队列），两处共用同一套编解码避免字段漂移。
 */
object SongCodec {

    fun encode(song: Song): String = toJson(song).toString()

    fun decode(text: String?): Song? = runCatching {
        fromJson(JSONObject(text ?: return null))
    }.getOrNull()

    fun encodeList(songs: List<Song>): String =
        JSONArray().apply { songs.forEach { put(toJson(it)) } }.toString()

    fun decodeList(text: String?): List<Song> = runCatching {
        val array = JSONArray(text ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::fromJson)
        }
    }.getOrDefault(emptyList())

    private fun toJson(song: Song): JSONObject = JSONObject().apply {
        put("title", song.title)
        put("artist", song.artist)
        put("duration", song.duration)
        put("color", song.color)
        put("audioUri", song.audioUri)
        put("remoteId", song.remoteId)
        put("coverUri", song.coverUri)
        put("lyricUri", song.lyricUri)
        put("lyricWordsUri", song.lyricWordsUri)
        put("album", song.album)
        put("subtitle", song.subtitle)
        put("releaseTime", song.releaseTime)
        // mid 与 type 必须一起持久化：冷启动恢复队列后还要靠它们去解析播放地址。
        put("mid", song.mid)
        put("type", song.type)
        put("vip", song.vip)
        put("favorited", song.favorited)
        put("localQuality", song.localQuality)
        put("source", song.source)
    }

    private fun fromJson(data: JSONObject): Song = Song(
        title = data.optString("title", "未知歌曲"),
        artist = data.optString("artist", "未知歌手"),
        duration = data.optString("duration", "网络歌曲"),
        color = data.optLong("color", 0xFFFFB4A2),
        audioUri = data.optText("audioUri"),
        remoteId = data.optLong("remoteId").takeIf { it > 0 },
        coverUri = data.optText("coverUri"),
        lyricUri = data.optText("lyricUri"),
        lyricWordsUri = data.optText("lyricWordsUri"),
        album = data.optString("album"),
        subtitle = data.optString("subtitle"),
        releaseTime = data.optString("releaseTime"),
        mid = data.optText("mid"),
        type = if (data.has("type") && !data.isNull("type")) data.optInt("type") else null,
        vip = data.optBoolean("vip"),
        favorited = data.optBoolean("favorited"),
        localQuality = if (data.has("localQuality") && !data.isNull("localQuality")) data.optInt("localQuality") else null,
        // 旧版本缓存没有来源字段，按服务端兼容规则继续视为 QQ 音乐。
        source = data.optString("source").ifBlank { "tencent" },
    )

    /** 可空字段统一处理：JSONObject 存入 null 会写成 JSONObject.NULL，取出来是字符串 "null"。 */
    private fun JSONObject.optText(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }
}
