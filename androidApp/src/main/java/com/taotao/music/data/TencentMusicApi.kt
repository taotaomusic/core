package com.taotao.music.data

import com.taotao.music.model.Song
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** 桃桃音乐后端客户端：移动端不直接请求第三方音乐接口。 */
class TencentMusicApi(private val tokenProvider: TokenProvider) {
    data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Int)

    fun search(keyword: String, page: Int = 1, num: Int = 20, quality: Int = 10): List<Song> {
        val query = "?keyword=${encode(keyword)}" +
            "&page=$page&num=${num.coerceIn(1, 60)}" +
            "&quality=${quality.coerceIn(0, 16)}"
        return authorized("/api/v1/search$query") { connection ->
            val songs = mutableListOf<Song>()
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val record = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    if (record.optString("type") == "song") {
                        record.optJSONObject("data")?.let { songs += it.toSong() }
                    }
                }
            }
            songs
        }
    }

    /**
     * 返回后端的指定品质播放流地址，不再解析第三方播放地址。
     * 播放地址需要携带访问令牌，因此这里同时确保本地令牌可用，
     * 让随后交给播放服务的令牌是新鲜的。
     */
    fun resolve(song: Song, quality: Int = 10): Song {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        tokenProvider.validToken() ?: throw SessionExpiredException()
        return song.copy(
            audioUri = "$ENDPOINT/api/v1/songs/$id/play?quality=${quality.coerceIn(0, 16)}",
            lyricUri = song.lyricUri?.let { if (it.startsWith("http")) it else "$ENDPOINT$it" }
                ?: "$ENDPOINT/api/v1/songs/$id/lyrics",
        )
    }

    fun isFavorite(song: Song): Boolean {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val result = authorized("/api/v1/favorites") { connection ->
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        }
        val favorites = result.optJSONArray("data") ?: return false
        return (0 until favorites.length()).any { index ->
            val item = favorites.optJSONObject(index)
            item?.optString("source") == "tencent" && item.optString("songId") == id.toString()
        }
    }

    fun setFavorite(song: Song, favorite: Boolean) {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        authorized("/api/v1/favorites/tencent/$id", if (favorite) "POST" else "DELETE") { connection ->
            connection.inputStream.close()
        }
    }

    fun requestLyric(song: Song): String {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        return authorized("/api/v1/songs/$id/lyrics") { connection ->
            connection.inputStream.bufferedReader().use { it.readText() }
        }
    }

    fun login(username: String, password: String): TokenPair = authenticate("/api/v1/auth/login", username, password)
    fun register(username: String, password: String): TokenPair = authenticate("/api/v1/auth/register", username, password)

    /**
     * 发起需要访问令牌的请求：令牌被服务端拒绝时自动续期并重放一次。
     * 续期失败说明刷新令牌同样失效，抛出 [SessionExpiredException] 让界面回到登录页。
     */
    private fun <T> authorized(path: String, method: String = "GET", read: (HttpURLConnection) -> T): T {
        var token = tokenProvider.validToken() ?: throw SessionExpiredException()
        repeat(MAX_AUTH_ATTEMPTS) { attempt ->
            val connection = open(path, method, token)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_UNAUTHORIZED) {
                check(code in 200..299) { messageOf(connection, "请求失败：HTTP $code") }
                return read(connection)
            }
            runCatching { connection.errorStream?.close() }
            if (attempt == MAX_AUTH_ATTEMPTS - 1) throw SessionExpiredException()
            token = tokenProvider.renewToken(token) ?: throw SessionExpiredException()
        }
        throw SessionExpiredException()
    }

    private fun authenticate(path: String, username: String, password: String): TokenPair =
        postJson(path, JSONObject().put("username", username).put("password", password), "认证失败")

    private fun open(path: String, method: String, token: String?): HttpURLConnection =
        (URL(ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/x-ndjson, application/json")
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
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
                id.takeIf { it > 0 }?.let { "$ENDPOINT/api/v1/songs/$it/play?quality=10" } ?: ""
            }.takeIf { it.isNotBlank() },
            coverUri = optString("coverUrl").ifBlank { null },
            lyricUri = optString("lyricUrl").ifBlank { null }?.let {
                if (it.startsWith("http")) it else "$ENDPOINT$it"
            } ?: id.takeIf { it > 0 }?.let { "$ENDPOINT/api/v1/songs/$it/lyrics" },
            album = optString("album", "未知专辑"), subtitle = optString("subtitle"), releaseTime = optString("time"),
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val ENDPOINT = "https://music.xydaigua.cn"
        private const val MAX_AUTH_ATTEMPTS = 2

        /** 媒体地址是否由本服务提供，只有自家地址才附带访问令牌。 */
        fun isOwnEndpoint(url: String): Boolean = runCatching { URL(url).host == URL(ENDPOINT).host }.getOrDefault(false)

        /**
         * 用刷新令牌换取新的令牌对。登录、注册和刷新都不需要访问令牌，
         * 因此做成伴生方法，避免会话层和网络客户端互相依赖。
         */
        fun refreshTokens(refreshToken: String): TokenPair =
            postJson("/api/v1/auth/refresh", JSONObject().put("refreshToken", refreshToken), "刷新令牌无效或已过期")

        /** 通知服务端撤销刷新令牌；失败不影响本地退出。 */
        fun revokeRefreshToken(refreshToken: String) {
            runCatching {
                val connection = openPost("/api/v1/auth/logout")
                connection.outputStream.use { it.write(JSONObject().put("refreshToken", refreshToken).toString().toByteArray()) }
                connection.responseCode
                runCatching { connection.errorStream?.close() }
                runCatching { connection.inputStream.close() }
            }
        }

        private fun postJson(path: String, body: JSONObject, fallback: String): TokenPair {
            val connection = openPost(path)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val message = messageOf(connection, fallback)
                // 4xx 表示凭据本身被拒绝，重试没有意义；5xx 和网络异常按可恢复错误处理。
                throw if (code in 400..499) CredentialsRejectedException(message) else IllegalStateException(message)
            }
            val result = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            check(result.optInt("code") == 0) { result.optString("message", fallback) }
            val data = result.getJSONObject("data")
            return TokenPair(data.getString("accessToken"), data.getString("refreshToken"), data.optInt("expiresIn", 900))
        }

        private fun openPost(path: String): HttpURLConnection =
            (URL(ENDPOINT + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TaotaoMusic/1.0")
            }

        /** 优先展示服务端返回的中文提示，取不到时退回默认文案。 */
        private fun messageOf(connection: HttpURLConnection, fallback: String): String {
            val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            val message = body?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }
            return message?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}

/** 用户名、密码或刷新令牌被服务端明确拒绝。 */
class CredentialsRejectedException(message: String) : IllegalStateException(message)
