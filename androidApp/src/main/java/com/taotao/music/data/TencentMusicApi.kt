package com.taotao.music.data

import com.taotao.music.model.Song
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** 桃桃音乐后端客户端：移动端不直接请求第三方音乐接口。 */
class TencentMusicApi {
    private val endpoint = "https://music.xydaigua.cn"

    fun search(keyword: String, page: Int = 1, num: Int = 20, quality: Int = 10): List<Song> {
        val query = "?keyword=${encode(keyword)}" +
            "&page=$page&num=${num.coerceIn(1, 60)}" +
            "&quality=${quality.coerceIn(0, 16)}"
        val connection = openConnection("/api/v1/search$query")
        val songs = mutableListOf<Song>()
        connection.inputStream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val record = JSONObject(line)
                if (record.optString("type") == "song") {
                    record.optJSONObject("data")?.let { songs += it.toSong() }
                }
            }
        }
        return songs
    }

    /** 返回后端的指定品质播放流地址，不再解析第三方播放地址。 */
    fun resolve(song: Song, quality: Int = 10): Song {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        return song.copy(
            audioUri = "$endpoint/api/v1/songs/$id/play?quality=${quality.coerceIn(0, 16)}",
            lyricUri = song.lyricUri?.let { if (it.startsWith("http")) it else "$endpoint$it" },
        )
    }

    private fun openConnection(path: String): HttpURLConnection =
        (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/x-ndjson, application/json")
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
        }

    private fun JSONObject.toSong(): Song {
        val id = optLong("id")
        return Song(
            title = optString("title", "未知歌曲"),
            artist = optString("artist", "未知歌手"),
            duration = optString("duration", "网络歌曲"),
            color = 0xFFFFB4A2,
            remoteId = id.takeIf { it > 0 },
            audioUri = id.takeIf { it > 0 }?.let {
                "$endpoint/api/v1/songs/$it/play?quality=10"
            },
            coverUri = optString("coverUrl").ifBlank { null },
            lyricUri = optString("lyricUrl").ifBlank { null }?.let {
                if (it.startsWith("http")) it else "$endpoint$it"
            },
            album = optString("album", "未知专辑"), subtitle = optString("subtitle"), releaseTime = optString("time"),
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
