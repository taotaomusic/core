package com.taotao.music.data

import com.taotao.music.model.AudioQuality
import com.taotao.music.model.Song
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** 桃桃音乐后端客户端：移动端不直接请求第三方音乐接口。 */
class TencentMusicApi(private val tokenProvider: TokenProvider) {
    data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Int)

    /**
     * 响应头里带回的最新版本号的观察者。
     *
     * 本类没有 Context 也没有协程作用域，所以不自己触发检查，只把值交出去 ——
     * 与 [com.taotao.music.data.AuthSession.onSessionExpired] 同一套做法。
     * 回调发生在发起请求的那个线程（通常是 IO），实现方要自己切线程。
     */
    @Volatile
    var onLatestVersion: ((Long) -> Unit)? = null

    /**
     * 搜索歌曲。
     *
     * [onProgress] 在解析过程中被反复调用，每次传入**当前累积的完整列表**而不是新增的一首。
     * 这样设计是因为 [authorized] 在令牌被拒时会重放整个请求、把 NDJSON 从头再读一遍：
     * 若回调语义是「追加一首」，重放就会产出重复条目；传累积快照时累积列表是下面这个
     * lambda 的局部变量，重放自然从空开始，界面直接整体赋值即可。
     */
    fun search(
        keyword: String,
        page: Int = 1,
        num: Int = 60,
        quality: Int = AudioQuality.Default.value,
        onProgress: (List<Song>) -> Unit = {},
    ): SearchResult {
        val query = "?keyword=${encode(keyword)}" +
            "&page=$page&num=${num.coerceIn(1, 60)}" +
            "&quality=${quality.coerceIn(0, MAX_QUALITY)}"
        return authorized("/api/v1/search$query") { connection ->
            val songs = mutableListOf<Song>()
            var dropped = 0
            var hasMore = false
            var total = 0
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val record = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    when (record.optString("type")) {
                        "song" -> record.optJSONObject("data")?.let {
                            songs += it.toSong(quality)
                            onProgress(songs.toList())
                        }
                        "end" -> record.optJSONObject("meta")?.let { meta ->
                            // dropped 现在恒为 0（服务端不再逐首探测），仍然读它是为了兼容旧服务端。
                            dropped = meta.optInt("dropped")
                            hasMore = meta.optBoolean("hasMore")
                            total = meta.optInt("total")
                        }
                    }
                }
            }
            SearchResult(songs, dropped, hasMore, total, page)
        }
    }

    /**
     * 解析播放地址。
     *
     * 返回的是**上游直链**，音频字节不再经过我们的服务器。所以调用方拿到的地址
     * 是限时的，不能持久化 —— 队列里存的始终是 [placeholderUri] 那种永不过期的占位地址，
     * 真正的直链在取流的那一刻才换上。
     */
    fun resolveLink(song: Song, quality: Int): ResolvedLink {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val query = buildString {
            append("?quality=${quality.coerceIn(0, MAX_QUALITY)}")
            song.mid?.takeIf { it.isNotBlank() }?.let { append("&mid=${encode(it)}") }
            song.type?.let { append("&type=$it") }
        }
        return authorized("/api/v1/songs/$id/link$query") { connection ->
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.optInt("code") == 0) { result.optString("message", "无法获取播放地址") }
            val data = result.getJSONObject("data")
            ResolvedLink(
                url = data.getString("url"),
                quality = data.optInt("quality", quality),
                kbps = data.optString("kbps"),
                fallback = data.optBoolean("fallback"),
            )
        }
    }

    /**
     * 只按歌曲 ID 解析直链，供取流时使用。
     *
     * 取流时手上只有占位地址,拿不到 mid 与 type;但占位地址只在 `remoteId > 0` 时才生成,
     * 所以按 ID 解析一定够用。
     */
    fun resolveDirectUrl(remoteId: Long, quality: Int): String =
        authorized("/api/v1/songs/$remoteId/link?quality=${quality.coerceIn(0, MAX_QUALITY)}") { connection ->
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.optInt("code") == 0) { result.optString("message", "无法获取播放地址") }
            result.getJSONObject("data").getString("url")
        }

    /** 这首歌真实存在的音质档位，用于让选择器只列出能选的档并提示体积。 */
    fun requestQualities(song: Song): List<QualityOption> {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val query = song.mid?.takeIf { it.isNotBlank() }?.let { "?mid=${encode(it)}" } ?: ""
        return authorized("/api/v1/songs/$id/info$query") { connection ->
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.optInt("code") == 0) { result.optString("message", "无法获取音质列表") }
            val list = result.getJSONObject("data").optJSONArray("qualities") ?: return@authorized emptyList()
            (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                QualityOption(item.optInt("quality"), item.optString("label"), item.optLong("size"))
            }
        }
    }

    /** 当前用户收藏的全部歌曲 ID，用于播种本地缓存。 */
    fun favoriteIds(): Set<String> = authorized("/api/v1/favorites") { connection ->
        val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val favorites = result.optJSONArray("data") ?: return@authorized emptySet()
        (0 until favorites.length()).mapNotNullTo(mutableSetOf()) { index ->
            val item = favorites.optJSONObject(index) ?: return@mapNotNullTo null
            item.optString("songId").takeIf { it.isNotBlank() && item.optString("source") == "tencent" }
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

    /**
     * 取歌词的完整数据：`lrc` 是行级时间轴，`yrc` 是逐字时间轴。
     * 服务端默认返回纯文本以兼容旧客户端，必须显式带 `format=json` 才给到 yrc。
     */
    fun requestRichLyric(song: Song): RichLyric {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        return authorized("/api/v1/songs/$id/lyrics?format=json") { connection ->
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // 服务端若还没部署 format=json，旧版本会忽略该参数直接返回纯 LRC 文本。
            // 这里把解析失败的响应体当作 LRC 使用，避免客户端先发版时所有歌词都变成「暂无歌词」。
            val result = runCatching { JSONObject(body) }.getOrNull()
                ?: return@authorized RichLyric(lrc = body, yrc = "")
            check(result.optInt("code") == 0) { result.optString("message", "获取歌词失败") }
            val data = result.getJSONObject("data")
            RichLyric(lrc = data.optString("lrc"), yrc = data.optString("yrc"))
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
                // 服务端在每个响应上带回当前全量可用的最高版本号，据此在会话中途也能发现更新。
                // 放在 401 重放之后，所以只读成功那次的头；responseCode 已经把响应头
                // 读进来了，这里不产生额外 I/O。
                noteLatestVersion(connection)
                return read(connection)
            }
            runCatching { connection.errorStream?.close() }
            if (attempt == MAX_AUTH_ATTEMPTS - 1) throw SessionExpiredException()
            token = tokenProvider.renewToken(token) ?: throw SessionExpiredException()
        }
        throw SessionExpiredException()
    }

    /** 把响应头里的最新版本号交给观察者。解析失败或没有这个头时什么都不做。 */
    private fun noteLatestVersion(connection: HttpURLConnection) {
        val latest = connection.getHeaderField(HEADER_LATEST_VERSION)?.toLongOrNull() ?: return
        if (latest > 0) runCatching { onLatestVersion?.invoke(latest) }
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

    private fun JSONObject.toSong(quality: Int): Song {
        val id = optLong("id")
        return Song(
            title = optString("title", "未知歌曲"),
            artist = optString("artist", "未知歌手"),
            duration = optString("duration", "网络歌曲"),
            color = 0xFFFFB4A2,
            remoteId = id.takeIf { it > 0 },
            // 队列里存的是永不过期的占位地址，真正的上游直链在取流那一刻才解析。
            // 服务端下发的 audioUrl 只是同样的占位地址，这里直接自己拼，音质才跟得上偏好。
            audioUri = id.takeIf { it > 0 }?.let { placeholderUri(it, quality) }
                ?: optString("audioUrl").takeIf { it.isNotBlank() },
            coverUri = optString("coverUrl").ifBlank { null },
            lyricUri = optString("lyricUrl").ifBlank { null }?.let {
                if (it.startsWith("http")) it else "$ENDPOINT$it"
            } ?: id.takeIf { it > 0 }?.let { "$ENDPOINT/api/v1/songs/$it/lyrics" },
            album = optString("album", "未知专辑"), subtitle = optString("subtitle"), releaseTime = optString("time"),
            mid = optString("mid").ifBlank { null },
            type = if (has("type") && !isNull("type")) optInt("type") else null,
            vip = optBoolean("vip"),
            favorited = optBoolean("favorited"),
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        /** 后端地址。热更新模块也要用，因此对包内公开，保持单一来源。 */
        const val ENDPOINT = "https://music.xydaigua.cn"
        private const val MAX_AUTH_ATTEMPTS = 2

        /** 音质取值上限。实测上游档位到 18（NAC）。 */
        const val MAX_QUALITY = 18

        /** 服务端下发的当前全量可用最高版本号。 */
        const val HEADER_LATEST_VERSION = "x-latest-version-code"

        /**
         * 队列里存的占位地址。
         *
         * 刻意用自家的 `/play` 路径而不是上游直链：直链是限时的，存进 MediaItem 或
         * 持久化队列后，冷启动恢复时早就失效了。占位地址永不过期，且 [isOwnEndpoint]
         * 认得它，取流时由 PlaybackService 的 ResolvingDataSource 换成直链；
         * 换不成就照这个地址走服务器代理，正好是天然的兜底。
         */
        fun placeholderUri(remoteId: Long, quality: Int): String =
            "$ENDPOINT/api/v1/songs/$remoteId/play?quality=${quality.coerceIn(0, MAX_QUALITY)}"

        /** 从占位地址里取回歌曲 ID 与音质，供 ResolvingDataSource 解析用。 */
        fun parsePlaceholder(url: String): Pair<Long, Int>? = runCatching {
            val parsed = URL(url)
            if (parsed.host != URL(ENDPOINT).host) return null
            val id = Regex("""/api/v1/songs/(\d+)/play""").find(parsed.path)?.groupValues?.get(1)?.toLong() ?: return null
            val quality = Regex("""(?:^|&)quality=(\d+)""").find(parsed.query ?: "")?.groupValues?.get(1)?.toIntOrNull()
            id to (quality ?: AudioQuality.Default.value)
        }.getOrNull()

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

/** 歌词原始数据。[yrc] 为空表示这首歌没有逐字时间轴，界面退化为整行高亮。 */
data class RichLyric(val lrc: String, val yrc: String)

/**
 * 搜索结果。
 *
 * [dropped] 是服务端因拿不到可用播放地址而丢弃的数量，服务端不再逐首探测后恒为 0，
 * 保留只为兼容尚未升级的服务端。[hasMore] 与 [total] 服务端一直在返回，
 * 客户端以前直接丢掉，现在用来驱动滚到底加载下一页。
 */
data class SearchResult(
    val songs: List<Song>,
    val dropped: Int,
    val hasMore: Boolean = false,
    val total: Int = 0,
    val page: Int = 1,
)

/**
 * 解析出来的播放地址。
 *
 * [url] 是上游直链，**限时**，不能持久化。[quality] 是实际拿到的档位 ——
 * 上游不会自动降级，服务端按阶梯往下试，所以可能低于请求的档位，[fallback] 即表示发生了降级。
 */
data class ResolvedLink(val url: String, val quality: Int, val kbps: String, val fallback: Boolean)

/** 一个可选音质档位。[size] 是字节数，用于在下载前提示体积。 */
data class QualityOption(val quality: Int, val label: String, val size: Long)
