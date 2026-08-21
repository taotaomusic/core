package com.taotao.music.data

import com.taotao.music.model.Song
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** 桃桃音乐后端客户端：移动端不直接请求第三方音乐接口。 */
class TencentMusicApi(private val tokenProvider: () -> String? = { null }) {
    data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Int)
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
            lyricUri = song.lyricUri?.let { if (it.startsWith("http")) it else "$endpoint$it" }
                ?: "$endpoint/api/v1/songs/$id/lyrics",
        )
    }

    fun isFavorite(song: Song): Boolean {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val result = requestJson("/api/v1/favorites")
        val favorites = result.optJSONArray("data") ?: return false
        return (0 until favorites.length()).any { index ->
            val item = favorites.optJSONObject(index)
            item?.optString("source") == "tencent" && item.optString("songId") == id.toString()
        }
    }

    fun setFavorite(song: Song, favorite: Boolean) {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val connection = openConnection("/api/v1/favorites/tencent/$id", if (favorite) "POST" else "DELETE")
        check(connection.responseCode in 200..299) { "收藏操作失败：HTTP ${connection.responseCode}" }
        connection.inputStream.close()
    }

    fun login(username: String, password: String): TokenPair = authenticate("/api/v1/auth/login", username, password)
    fun register(username: String, password: String): TokenPair = authenticate("/api/v1/auth/register", username, password)
    fun refresh(refreshToken: String): TokenPair {
        val connection = openConnection("/api/v1/auth/refresh", "POST").apply { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        connection.outputStream.use { it.write(JSONObject().put("refreshToken", refreshToken).toString().toByteArray()) }
        return parseTokens(connection)
    }

    fun requestLyric(song: Song): String {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val connection = openConnection("/api/v1/songs/$id/lyrics")
        check(connection.responseCode in 200..299) { "歌词获取失败：HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun authenticate(path: String, username: String, password: String): TokenPair {
        val connection = openConnection(path, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(JSONObject().put("username", username).put("password", password).toString().toByteArray()) }
        return parseTokens(connection)
    }

    private fun parseTokens(connection: HttpURLConnection): TokenPair {
        val result = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        check(result.optInt("code") == 0) { result.optString("message", "认证失败") }
        val data = result.getJSONObject("data")
        return TokenPair(data.getString("accessToken"), data.getString("refreshToken"), data.optInt("expiresIn", 900))
    }

    private fun requestJson(path: String): JSONObject {
        val connection = openConnection(path)
        return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun openConnection(path: String, method: String = "GET"): HttpURLConnection =
        (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/x-ndjson, application/json")
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
            tokenProvider()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun JSONObject.toSong(): Song {
        val id = optLong("id")
        return Song(
            title = optString("title", "未知歌曲"),
            artist = optString("artist", "未知歌手"),
            duration = optString("duration", "网络歌曲"),
            color = 0xFFFFB4A2,
            remoteId = id.takeIf { it > 0 },
            audioUri = optString("audioUrl").ifBlank {
                id.takeIf { it > 0 }?.let { "$endpoint/api/v1/songs/$it/play?quality=10" } ?: ""
            }.takeIf { it.isNotBlank() },
            coverUri = optString("coverUrl").ifBlank { null },
            lyricUri = optString("lyricUrl").ifBlank { null }?.let {
                if (it.startsWith("http")) it else "$endpoint$it"
            } ?: id.takeIf { it > 0 }?.let { "$endpoint/api/v1/songs/$it/lyrics" },
            album = optString("album", "未知专辑"), subtitle = optString("subtitle"), releaseTime = optString("time"),
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
}
