package com.taotao.music.data

import com.taotao.music.data.im.ImConversationSync
import com.taotao.music.model.AudioQuality
import com.taotao.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import android.net.Uri

/** 桃桃音乐后端客户端：移动端不直接请求第三方音乐接口。 */
class TencentMusicApi(
    private val tokenProvider: TokenProvider,
    private val appVersionCode: Long = 0L,
) {
    /** 波点搜索首页热词；点击时 [keyword] 可直接进入普通搜索。 */
    data class HotSearchItem(
        val keyword: String,
        val type: Int,
        val icon: String,
        val sort: Int,
        val searchType: Int,
        val jumpUrl: String,
    )
    data class ImContact(val uid: String, val nickname: String)
    /**
     * 认证响应中携带的账号 ID 只用于本地数据分桶，绝不作为鉴权凭据使用。
     *
     * 刷新接口为了兼容已发布客户端不会重复返回 user，因此 [userId] 可以为空；
     * [AuthSession] 会保留登录或注册时拿到的 ID。
     */
    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int,
        val userId: Long? = null,
    )
    data class FavoriteLibrary(val ids: Set<String>, val songs: List<Song>)
    /** 服务端生成的稳定分享短链；客户端只负责交给系统分享面板。 */
    data class SongShare(val token: String, val url: String)

    /**
     * 云端歌单中的歌曲快照。
     *
     * 歌曲身份始终由 [source] + [songId] 组成；腾讯 songID=0 的结果会在上传前改用
     * [mid] 作为 songId，因此歌单可以在不同设备间稳定同步。音频和歌词地址只保存
     * 服务端占位地址，`file:` 本地路径不会被上传。
     */
    data class PlaylistSong(
        val source: String,
        val songId: String,
        val mid: String?,
        val title: String,
        val artist: String,
        val album: String,
        val coverUrl: String?,
        val duration: String?,
        val audioUrl: String?,
        val lyricUrl: String?,
        val type: Int?,
        val position: Int,
        val addedAt: Long,
        val updatedAt: Long,
    ) {
        /** 把云端快照还原成播放器和列表使用的共享歌曲模型。 */
        fun toSong(quality: Int = AudioQuality.Default.value): Song {
            val numericId = songId.toLongOrNull()?.takeIf { it > 0L }
            val stableAudio = audioUrl?.takeUnless { it.equals("null", ignoreCase = true) }
            val stableLyric = lyricUrl?.takeUnless { it.equals("null", ignoreCase = true) }
            return Song(
                title = title.ifBlank { "未知歌曲" },
                artist = artist.ifBlank { "未知歌手" },
                duration = duration?.ifBlank { null } ?: "网络歌曲",
                color = 0xFFFFB4A2,
                remoteId = numericId,
                mid = mid,
                type = type,
                source = source.ifBlank { "tencent" },
                album = album,
                coverUri = coverUrl,
                // 数字 ID 使用当前播放音质重新生成永不过期占位地址；mid-only 结果
                // 则沿用服务端保存的可播放地址，避免把身份误当成 Long。
                audioUri = placeholderUri(numericId, mid, type, quality, source) ?: stableAudio,
                lyricUri = stableLyric?.let { value ->
                    if (value.startsWith("http", ignoreCase = true)) value else "$ENDPOINT$value"
                } ?: lyricUri(numericId, mid, source),
            )
        }
    }

    /** 云端歌单及其版本号；列表接口只返回元数据，详情接口同时返回 songs。 */
    data class Playlist(
        val id: Long,
        val name: String,
        val description: String,
        val coverUrl: String?,
        val songCount: Int,
        val revision: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val songs: List<PlaylistSong> = emptyList(),
    )
    /** 账号资料仅由本人读取；邮箱不写入本地持久化。 */
    data class UserProfile(val username: String, val email: String?, val nickname: String, val avatarUrl: String?)
    /** 首页公告为公开数据，按服务端置顶和发布时间排序。 */
    data class Announcement(val id: Long, val title: String, val content: String, val pinned: Boolean, val publishedAt: Long)
    /** GPT Image 工作台的异步任务状态。图片 Key 始终只保留在服务端。 */
    data class ImageTask(val taskId: String, val state: String, val progress: Int, val imageUrl: String?, val error: String?)
    /**
     * 悟空 IM 的 Android 连接凭据。
     *
     * gatewayUrl 是原生 WKProto 地址（当前为 `tcp://`），不是桃桃音乐 HTTP API，也不包含
     * 悟空 IM 产品 API 的管理凭据。
     */
    data class ImSession(
        val uid: String,
        val token: String,
        val tokenExpiresAt: Long,
        val deviceFlag: Int,
        val deviceLevel: Int,
        val gatewayUrl: String,
    )
    /** 云端最近播放只持久化来源、歌曲 ID 与时间；展示资料由歌曲信息接口补全。 */
    data class RecentPlayback(
        val source: String,
        val songId: String,
        val playedAtMillis: Long,
        val firstPlayedAtMillis: Long,
        val playCount: Int,
        val completedCount: Int,
        val totalListenedMs: Long,
    )

    /** 单曲倒带日记里的一条历史播放会话。 */
    data class SongDiaryRecord(
        val startedAtMillis: Long,
        val lastPlayedAtMillis: Long,
        val listenedMs: Long,
        val completed: Boolean,
    )

    /** 近年折线的一个年份数据点。 */
    data class SongDiaryYear(
        val year: Int,
        val count: Int,
    )

    /** 单曲倒带日记：当前用户对某一首歌的完整播放画像，数据由后端现算。 */
    data class SongDiary(
        val source: String,
        val songId: String,
        /** 首次邂逅；从未播放过为 null。 */
        val firstPlayedAtMillis: Long?,
        /** 上次收听（全量，不受清空最近播放影响）；从未播放过为 null。 */
        val lastPlayedAtMillis: Long?,
        val playCount: Int,
        val completedCount: Int,
        val totalListenedMs: Long,
        /** 近一年（365 天）合格播放次数。 */
        val playsLastYear: Int,
        /** 近半年（180 天）合格播放次数。 */
        val playsLastHalfYear: Int,
        /** 狂热循环：单日播放最多的那一天起点；从未播放过为 null。 */
        val peakDayAtMillis: Long?,
        /** 狂热循环那天的播放次数。 */
        val peakDayCount: Int,
        /** 近 6 个自然年的逐年播放次数（用于折线趋势）。 */
        val yearly: List<SongDiaryYear>,
        /** 最近 180 天的逐日播放次数，从最早到今天（用于点阵热力图）。 */
        val dailyCounts: List<Int>,
        val records: List<SongDiaryRecord>,
    )

    /** 播放占位地址中恢复出的完整路由信息。数字 ID 缺失时用 mid 继续路由。 */
    data class Placeholder(
        val remoteId: Long?,
        val mid: String?,
        val type: Int?,
        val quality: Int,
        val source: String,
    )

    /** 服务端历史快照；revision 让客户端识别跨设备执行的清空。 */
    data class RecentPlaybackPage(
        val entries: List<RecentPlayback>,
        val clearedBeforeMillis: Long,
        val revision: Long,
    )

    /** 已补全歌曲资料的最近播放快照，保留清空水位供界面合并离线队列。 */
    data class PlaybackHistoryLibrary(
        val entries: List<PlaybackHistoryEntry>,
        val clearedBeforeMillis: Long,
        val revision: Long,
    )

    /**
     * 最近播放的服务端水位。revision 用来表达跨设备清空的先后关系，不能再依赖设备本地时间。
     * marker 是服务端为一次清空分配的稳定标识，供离线重试保持幂等。
     */
    data class PlaybackHistoryState(
        val revision: Long,
        val clearedAtMillis: Long,
        val marker: String?,
    )

    /** 单次会话上报的确认信息；服务端会返回会话所属与当前最新的历史代际。 */
    data class PlaybackReportResult(
        val historyRevision: Long,
        val currentHistoryRevision: Long,
    )

    /**
     * 响应头里带回的最新版本号的观察者。
     *
     * 本类没有 Context 也没有协程作用域，所以不自己触发检查，只把值交出去 ——
     * 与 [com.taotao.music.data.AuthSession.onSessionExpired] 同一套做法。
     * 回调发生在发起请求的那个线程（通常是 IO），实现方要自己切线程。
     */
    @Volatile
    var onLatestVersion: ((Long) -> Unit)? = null

    /** 与当前 APK 精确匹配的最新补丁号。 */
    @Volatile
    var onLatestPatch: ((Int) -> Unit)? = null

    /**
     * 搜索歌曲。
     *
     * [onProgress] 在解析过程中被反复调用，每次传入**当前累积的完整列表**而不是新增的一首。
     * 这样设计是因为 [authorized] 在令牌被拒时会重放整个请求、把 NDJSON 从头再读一遍：
     * 若回调语义是「追加一首」，重放就会产出重复条目；传累积快照时累积列表是下面这个
     * lambda 的局部变量，重放自然从空开始，界面直接整体赋值即可。
     *
     * [source] 是**搜索范围**，与歌曲自身的来源不是一回事，所以默认值不是 [DEFAULT_SOURCE]：
     * 传 [SEARCH_SOURCE_ALL] 时服务端按聚合白名单并发查多个音源，传具体音源名则只查那一个。
     * 服务端对缺失 / 空串 / `all` 一律归一成聚合搜索，所以这里显式传值只是为了
     * 让「用户选了什么」在客户端就有据可查，不依赖服务端的默认分支。
     *
     * ⚠️ 聚合白名单由服务端的 `AGGREGATED_SOURCES` 决定，**不等于「所有已接入音源」** ——
     * 酷我刻意不在其中，只能显式指定。别假设选了「全部」就等于三端都查了。
     */
    fun search(
        keyword: String,
        page: Int = 1,
        num: Int = 60,
        quality: Int = AudioQuality.Default.value,
        source: String = SEARCH_SOURCE_KUWO,
        onProgress: (List<Song>) -> Unit = {},
    ): SearchResult {
        val query = "?keyword=${encode(keyword)}" +
            "&page=$page&num=${num.coerceIn(1, 60)}" +
            "&quality=${quality.coerceIn(0, MAX_QUALITY)}" +
            "&source=${encode(source.ifBlank { SEARCH_SOURCE_KUWO })}"
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
                            // 服务端因拿不到播放地址而丢掉的条数。**不再是恒为 0 的装饰字段**：
                            // 酷我搜热门歌手时上游给 20 条、20 条全被预筛掉，这里就会读到 20，
                            // 而 songs 是空的 —— 那才是「搜到了但都不可播」，不是「没搜到」。
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

    /** 搜索框联想词，服务端已按波点官方顺序裁剪。 */
    fun searchSuggestions(keyword: String, limit: Int = 10): List<String> {
        if (keyword.isBlank()) return emptyList()
        return authorized(
            "/api/v1/search/suggestions?keyword=${encode(keyword.trim())}&limit=${limit.coerceIn(1, 20)}&source=kuwo",
        ) { connection ->
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(root.optInt("code") == 0) { root.optString("message", "无法获取搜索联想") }
            val data = root.optJSONArray("data") ?: JSONArray()
            buildList {
                for (index in 0 until data.length()) {
                    data.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }
    }

    /** 波点官方搜索首页热词。 */
    fun hotSearch(limit: Int = 20): List<HotSearchItem> = authorized(
        "/api/v1/search/hot?limit=${limit.coerceIn(1, 20)}&source=kuwo",
    ) { connection ->
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        check(root.optInt("code") == 0) { root.optString("message", "无法获取热搜") }
        val data = root.optJSONArray("data") ?: JSONArray()
        buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val keyword = item.optString("keyword").trim()
                if (keyword.isEmpty()) continue
                add(
                    HotSearchItem(
                        keyword = keyword,
                        type = item.optInt("type"),
                        icon = item.optString("icon"),
                        sort = item.optInt("sort"),
                        searchType = item.optInt("searchType"),
                        jumpUrl = item.optString("jumpUrl"),
                    ),
                )
            }
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
        val id = song.remoteId?.takeIf { it > 0L } ?: 0L
        require(id > 0L || !song.mid.isNullOrBlank()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val query = buildString {
            append("?quality=${quality.coerceIn(0, MAX_QUALITY)}")
            song.mid?.takeIf { it.isNotBlank() }?.let { append("&mid=${encode(it)}") }
            song.type?.let { append("&type=$it") }
            append("&source=${encode(song.source.ifBlank { DEFAULT_SOURCE })}")
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

    /** 按占位地址中的身份解析直链；兼容旧调用方的数字 ID 重载保留。 */
    fun resolveDirectUrl(remoteId: Long, quality: Int, source: String = DEFAULT_SOURCE): String =
        resolveDirectUrl(Placeholder(remoteId, null, null, quality, source))

    /** 按占位地址中的完整身份解析直链，支持腾讯 mid-only 歌曲。 */
    fun resolveDirectUrl(placeholder: Placeholder): String {
        val id = placeholder.remoteId?.takeIf { it > 0L } ?: 0L
        require(id > 0L || !placeholder.mid.isNullOrBlank()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val query = buildString {
            append("?quality=${placeholder.quality.coerceIn(0, MAX_QUALITY)}")
            placeholder.mid?.takeIf { it.isNotBlank() }?.let { append("&mid=${encode(it)}") }
            placeholder.type?.let { append("&type=$it") }
            append("&source=${encode(placeholder.source.ifBlank { DEFAULT_SOURCE })}")
        }
        return authorized("/api/v1/songs/$id/link$query") { connection ->
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.optInt("code") == 0) { result.optString("message", "无法获取播放地址") }
            result.getJSONObject("data").getString("url")
        }
    }

    /** 这首歌真实存在的音质档位，用于让选择器只列出能选的档并提示体积。 */
    fun requestQualities(song: Song): List<QualityOption> {
        val id = song.remoteId?.takeIf { it > 0L } ?: 0L
        require(id > 0L || !song.mid.isNullOrBlank()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val query = buildString {
            append("?source=${encode(song.source.ifBlank { DEFAULT_SOURCE })}")
            song.mid?.takeIf { it.isNotBlank() }?.let { append("&mid=${encode(it)}") }
        }
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

    /** 当前用户收藏的全部歌曲 ID，用于播种本地状态缓存。 */
    fun favoriteIds(): Set<String> = favoriteSongIds().toSet()

    /**
     * 从既有收藏接口读取收藏顺序，再通过歌曲信息接口补全展示数据。
     *
     * 收藏关系只有服务端这一份权威数据；[knownSongs] 只是复用客户端已经拿到的歌曲元信息，
     * 不再另建一套“收藏夹快照”。这样搜索页心形、收藏页和 `/favorites` 始终指向同一状态。
     */
    suspend fun favoriteLibrary(knownSongs: List<Song>, quality: Int = AudioQuality.Default.value): FavoriteLibrary {
        val orderedIds = withContext(Dispatchers.IO) { favoriteSongIds() }
        val knownById = knownSongs.flatMap { song -> favoriteKeysOf(song).map { it to song } }.toMap()
        // 单曲信息接口最多四路并发，避免收藏较多时串行等待，同时不给上游制造瞬时洪峰。
        val songs = orderedIds.chunked(FAVORITE_INFO_CONCURRENCY).flatMap { batch ->
            coroutineScope {
                batch.map { value ->
                    async(Dispatchers.IO) {
                        val separator = value.indexOf(':')
                        val source = if (separator > 0) value.substring(0, separator) else DEFAULT_SOURCE
                        val identity = if (separator > 0) value.substring(separator + 1) else value
                        knownById[value]?.copy(favorited = true)
                            ?: identity.toLongOrNull()?.takeIf { it > 0L }?.let {
                                requestSongInfo(it, quality, favorited = true, source = source)
                            }
                            ?: unavailableSong(identity, quality, favorited = true, source = source)
                    }
                }.awaitAll().filterNotNull()
            }
        }
        return FavoriteLibrary(orderedIds.toSet(), songs)
    }

    /** 为一首具备远端身份的歌曲生成或复用当前账号的分享短链。 */
    fun createSongShare(song: Song): SongShare {
        require(song.remoteId?.let { it > 0L } == true || !song.mid.isNullOrBlank()) {
            "歌曲缺少可分享的远端身份"
        }
        val body = JSONObject()
            .put("source", song.source.ifBlank { DEFAULT_SOURCE })
            .apply {
                song.remoteId?.takeIf { it > 0L }?.let { put("remoteId", it) }
                song.mid?.takeIf { it.isNotBlank() }?.let { put("mid", it) }
                song.type?.let { put("type", it) }
            }
        return authorizedJson("/api/v1/shares/songs", "POST", body) { data ->
            SongShare(
                token = data.getString("token"),
                url = data.getString("url"),
            )
        }
    }

    /** 一个歌曲可能同时带数字 ID 与 mid，两个键都登记以便服务端身份补全后仍能命中缓存。 */
    private fun favoriteKeysOf(song: Song): Set<String> = buildSet {
        val source = song.source.ifBlank { DEFAULT_SOURCE }
        song.remoteId?.takeIf { it > 0L }?.let { add("$source:$it") }
        song.mid?.trim()?.takeIf { it.isNotBlank() }?.let { add("$source:$it") }
    }

    private fun favoriteSongIds(): List<String> = authorized("/api/v1/favorites") { connection ->
        val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val favorites = result.optJSONArray("data") ?: return@authorized emptyList()
        (0 until favorites.length()).mapNotNull { index ->
            val item = favorites.optJSONObject(index) ?: return@mapNotNull null
                item.optString("songId").takeIf { it.isNotBlank() }?.let {
                    "${item.optString("source").ifBlank { DEFAULT_SOURCE }}:$it"
                }
        }
    }

    /**
     * 与收藏夹相同的本地优先同步方式读取最近播放；目前只有腾讯歌曲能通过歌曲资料接口补全。
     */
    suspend fun recentPlaybackLibrary(
        knownSongs: List<Song>,
        quality: Int = AudioQuality.Default.value,
    ): PlaybackHistoryLibrary {
        val page = withContext(Dispatchers.IO) { recentPlaybackPage() }
        val records = page.entries
        val knownByKey = knownSongs.flatMap { song ->
            songIdentityKeys(song).map { it to song }
        }.toMap()
        val missingNumericBySource = records
            .filter { record -> "${record.source.lowercase(Locale.ROOT)}:${record.songId}" !in knownByKey }
            .mapNotNull { record -> record.songId.toLongOrNull()?.takeIf { it > 0L }?.let { record.source.lowercase(Locale.ROOT) to it } }
            .groupBy({ it.first }, { it.second })
        // 服务端批量接口每次最多补 60 首；500 条历史只需按来源分批，不再形成逐首请求。
        val resolvedByKey = missingNumericBySource.flatMap { (source, ids) ->
            ids.distinct().chunked(BATCH_INFO_SIZE).flatMap { batch -> requestSongInfoBatch(batch, quality, source) }
        }.flatMap { song -> songIdentityKeys(song).map { it to song } }.toMap()
        val songs = knownByKey + resolvedByKey
        val entries = records.mapNotNull { record ->
            val key = "${record.source.lowercase(Locale.ROOT)}:${record.songId}"
            val song = songs[key] ?: unavailableSong(record.songId, quality, false, record.source)
            PlaybackHistoryEntry(
                song = song,
                playedAtMillis = record.playedAtMillis,
                firstPlayedAtMillis = record.firstPlayedAtMillis,
                playCount = record.playCount,
                completedCount = record.completedCount,
                totalListenedMs = record.totalListenedMs,
            )
        }
        return PlaybackHistoryLibrary(entries, page.clearedBeforeMillis, page.revision)
    }

    private fun songIdentityKeys(song: Song): Set<String> = buildSet {
        val source = song.source.trim().ifBlank { DEFAULT_SOURCE }.lowercase(Locale.ROOT)
        song.remoteId?.takeIf { it > 0L }?.let { add("$source:$it") }
        song.mid?.trim()?.takeIf { it.isNotBlank() }?.let { add("$source:$it") }
    }

    fun reportPlayback(
        sessionId: String,
        deviceId: String,
        source: String,
        songId: String,
        startedAt: Long,
        lastPlayedAt: Long,
        listenedMs: Long,
        durationSeconds: Int?,
        completed: Boolean = false,
        historyRevision: Long? = null,
    ): PlaybackReportResult {
        return authorizedJson(
            "/api/v1/playback/sessions",
            "POST",
            JSONObject()
                .put("sessionId", sessionId)
                .put("deviceId", deviceId)
                .put("source", source.ifBlank { DEFAULT_SOURCE })
                .put("songId", songId)
                .put("startedAt", startedAt)
                .put("lastPlayedAt", lastPlayedAt.coerceAtLeast(startedAt))
                .put("listenedMs", listenedMs.coerceAtLeast(0L))
                .put("completed", completed)
                .put("durationSeconds", durationSeconds)
                .apply { historyRevision?.takeIf { it >= 0L }?.let { put("historyRevision", it) } },
        ) { data ->
            val reportedRevision = data.optLong("historyRevision", historyRevision ?: 0L).coerceAtLeast(0L)
            PlaybackReportResult(
                historyRevision = reportedRevision,
                currentHistoryRevision = data.optLong("currentHistoryRevision", reportedRevision).coerceAtLeast(reportedRevision),
            )
        }
    }

    /** 兼容仍以腾讯数字 ID 调用的旧业务代码。 */
    fun reportPlayback(
        sessionId: String,
        deviceId: String,
        songId: Long,
        startedAt: Long,
        lastPlayedAt: Long,
        listenedMs: Long,
        durationSeconds: Int?,
        completed: Boolean = false,
        historyRevision: Long? = null,
    ): PlaybackReportResult = reportPlayback(
        sessionId = sessionId,
        deviceId = deviceId,
        source = DEFAULT_SOURCE,
        songId = songId.toString(),
        startedAt = startedAt,
        lastPlayedAt = lastPlayedAt,
        listenedMs = listenedMs,
        durationSeconds = durationSeconds,
        completed = completed,
        historyRevision = historyRevision,
    )

    /**
     * 清空历史并取回服务端确认的 revision。marker 是客户端持久化的 UUID，同一网络重试
     * 绝不生成第二次清空；旧服务端忽略 query 参数或返回空响应时仍可完成原有清空。
     */
    fun clearRecentPlayback(marker: String?): PlaybackHistoryState = authorized(
        "/api/v1/playback/recent" + marker?.takeIf { it.isNotBlank() }?.let { "?marker=${encode(it)}" }.orEmpty(),
        "DELETE",
    ) { connection ->
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        if (body.isBlank()) PlaybackHistoryState(0L, 0L, null) else {
            val envelope = JSONObject(body)
            (envelope.optJSONObject("data") ?: JSONObject()).toPlaybackHistoryState()
        }
    }

    /** 读取跨设备清空水位。缺失新端点时调用方可保留已经缓存的 revision。 */
    fun playbackHistoryState(): PlaybackHistoryState = authorized("/api/v1/playback/recent/state") { connection ->
        val envelope = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        (envelope.optJSONObject("data") ?: JSONObject()).toPlaybackHistoryState()
    }

    private fun recentPlaybackPage(): RecentPlaybackPage = authorized("/api/v1/playback/recent?limit=500") { connection ->
        val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val data = result.opt("data")
        val records = when (data) {
            is JSONArray -> data // 兼容尚未部署新契约的服务端。
            is JSONObject -> data.optJSONArray("entries") ?: JSONArray()
            else -> JSONArray()
        }
        val entries = (0 until records.length()).mapNotNull { index ->
            records.optJSONObject(index)?.let { item ->
                val source = item.optString("source")
                val songId = item.optString("songId")
                if (source.isBlank() || songId.isBlank()) null else RecentPlayback(
                    source = source,
                    songId = songId,
                    playedAtMillis = item.optLong("lastPlayedAt"),
                    firstPlayedAtMillis = item.optLong("firstPlayedAt"),
                    playCount = item.optInt("playCount", 0),
                    completedCount = item.optInt("completedCount", 0),
                    totalListenedMs = item.optLong("totalListenedMs", 0),
                )
            }
        }
        val legacyCompatiblePage = RecentPlaybackPage(
            entries = entries,
            clearedBeforeMillis = (data as? JSONObject)?.clearedAtMillis() ?: 0L,
            revision = (data as? JSONObject)?.optLong("revision", 0L) ?: 0L,
        )
        // recent 保持裸数组以兼容已发布客户端；新状态端点缺失时仍可正常读取历史。
        runCatching {
            authorized("/api/v1/playback/recent/state") { stateConnection ->
                val envelope = JSONObject(stateConnection.inputStream.bufferedReader().use { it.readText() })
                val state = envelope.optJSONObject("data") ?: return@authorized legacyCompatiblePage
                legacyCompatiblePage.copy(
                    clearedBeforeMillis = state.clearedAtMillis(),
                    revision = state.optLong("revision", 0L),
                )
            }
        }.getOrDefault(legacyCompatiblePage)
    }

    /** 新字段叫 clearedAt，旧服务端叫 clearedBefore；两者都只由服务端生成。 */
    private fun JSONObject.clearedAtMillis(): Long =
        optLong("clearedAt", optLong("clearedBefore", 0L)).coerceAtLeast(0L)

    private fun JSONObject.toPlaybackHistoryState(fallbackRevision: Long = 0L): PlaybackHistoryState =
        PlaybackHistoryState(
            revision = optLong("revision", fallbackRevision).coerceAtLeast(0L),
            clearedAtMillis = clearedAtMillis(),
            marker = nullableString("marker"),
        )

    /** 拉取当前用户对某首歌的单曲倒带日记；songId 与播放上报使用同一稳定键。 */
    fun songDiary(song: Song): SongDiary {
        val source = song.source.trim().ifBlank { DEFAULT_SOURCE }
        val songId = playlistSongId(song)
            ?: throw IllegalArgumentException("歌曲缺少可用于查询日记的 source 或 mid")
        return songDiary(source, songId)
    }

    fun songDiary(source: String, songId: String): SongDiary = authorized(
        "/api/v1/playback/diary?source=${encode(source.ifBlank { DEFAULT_SOURCE })}&songId=${encode(songId)}",
    ) { connection ->
        val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        check(result.optInt("code") == 0) { result.optString("message", "无法获取单曲日记") }
        result.getJSONObject("data").toSongDiary(source, songId)
    }

    private fun JSONObject.toSongDiary(source: String, songId: String): SongDiary {
        val peak = optJSONObject("peakDay")
        val recordArray = optJSONArray("records") ?: JSONArray()
        val records = (0 until recordArray.length()).mapNotNull { index ->
            recordArray.optJSONObject(index)?.let { item ->
                SongDiaryRecord(
                    startedAtMillis = item.optLong("startedAt"),
                    lastPlayedAtMillis = item.optLong("lastPlayedAt"),
                    listenedMs = item.optLong("listenedMs", 0L),
                    completed = item.optBoolean("completed", false),
                )
            }
        }
        val yearlyArray = optJSONArray("yearly") ?: JSONArray()
        val yearly = (0 until yearlyArray.length()).mapNotNull { index ->
            yearlyArray.optJSONObject(index)?.let { item ->
                SongDiaryYear(year = item.optInt("year"), count = item.optInt("count", 0))
            }
        }
        val dailyArray = optJSONArray("dailyCounts") ?: JSONArray()
        val dailyCounts = (0 until dailyArray.length()).map { index -> dailyArray.optInt(index, 0) }
        return SongDiary(
            source = optString("source").ifBlank { source },
            songId = optString("songId").ifBlank { songId },
            firstPlayedAtMillis = nullableLong("firstPlayedAt"),
            lastPlayedAtMillis = nullableLong("lastPlayedAt"),
            playCount = optInt("playCount", 0),
            completedCount = optInt("completedCount", 0),
            totalListenedMs = optLong("totalListenedMs", 0L),
            playsLastYear = optInt("playsLastYear", 0),
            playsLastHalfYear = optInt("playsLastHalfYear", 0),
            peakDayAtMillis = peak?.let { if (it.isNull("atMillis")) null else it.optLong("atMillis") },
            peakDayCount = peak?.optInt("count", 0) ?: 0,
            yearly = yearly,
            dailyCounts = dailyCounts,
            records = records,
        )
    }

    private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else optLong(name)

    /**
     * 最近播放资料只能以批量请求补全。若批次或其中的某首失败，直接给缺失项本地占位，
     * 不能回退为逐首 HTTP 请求：500 条历史在弱网下会把一次失败放大成数百次串行请求。
     */
    private fun requestSongInfoBatch(
        remoteIds: List<Long>,
        quality: Int,
        source: String = DEFAULT_SOURCE,
    ): List<Song> {
        val normalizedSource = source.ifBlank { DEFAULT_SOURCE }
        val resolvedById = runCatching {
            authorized(
                "/api/v1/songs/batch-info?ids=${remoteIds.joinToString(",")}" +
                    "&source=${encode(normalizedSource)}",
            ) { connection ->
                val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val songs = result.optJSONObject("data")?.optJSONArray("songs") ?: JSONArray()
                (0 until songs.length()).mapNotNull { index ->
                    songs.optJSONObject(index)?.let { item ->
                        item.optLong("songId", -1).takeIf { it > 0 }?.let { id ->
                            id to songFromInfo(item, id, quality, false, normalizedSource)
                        }
                    }
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        return remoteIds.map { remoteId ->
            resolvedById[remoteId] ?: unavailableSong(remoteId, quality, favorited = false, normalizedSource)
        }
    }

    /** 单首补全失败时仍保留可播放占位项，不能让服务端历史从页面上凭空消失。 */
    private fun requestSongInfo(
        remoteId: Long,
        quality: Int,
        favorited: Boolean,
        source: String = DEFAULT_SOURCE,
    ): Song = runCatching {
        val normalizedSource = source.ifBlank { DEFAULT_SOURCE }
        authorized("/api/v1/songs/$remoteId/info?source=${encode(normalizedSource)}") { connection ->
            val result = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(result.optInt("code") == 0) { result.optString("message", "无法获取收藏歌曲") }
            val data = result.getJSONObject("data")
            songFromInfo(data, remoteId, quality, favorited, normalizedSource)
        }
    }.getOrElse {
        unavailableSong(remoteId, quality, favorited, source)
    }

    /** 单首收藏和批量最近播放都复用同一套离线占位，保证列表不会因资料接口失败而消失。 */
    private fun unavailableSong(
        remoteId: Long,
        quality: Int,
        favorited: Boolean,
        source: String = DEFAULT_SOURCE,
    ): Song {
        val normalizedSource = source.ifBlank { DEFAULT_SOURCE }
        return Song(
            title = "歌曲 $remoteId",
            artist = "歌曲信息暂不可用",
            duration = "网络歌曲",
            color = 0xFFFFB4A2,
            audioUri = placeholderUri(remoteId, quality, normalizedSource),
            remoteId = remoteId,
            lyricUri = lyricUri(remoteId, normalizedSource),
            favorited = favorited,
            source = normalizedSource,
        )
    }

    /** mid-only 收藏没有数字 ID 时的可播放占位项；服务端会在取流时按 mid 解析。 */
    private fun unavailableSong(
        identity: String,
        quality: Int,
        favorited: Boolean,
        source: String,
    ): Song {
        val numericId = identity.toLongOrNull()?.takeIf { it > 0L }
        val mid = numericId?.let { null } ?: identity
        return Song(
            title = "歌曲 $identity",
            artist = "歌曲信息暂不可用",
            duration = "网络歌曲",
            color = 0xFFFFB4A2,
            audioUri = placeholderUri(numericId, mid, null, quality, source),
            remoteId = numericId,
            mid = mid,
            lyricUri = lyricUri(numericId, mid, source),
            favorited = favorited,
            source = source,
        )
    }

    private fun songFromInfo(
        data: JSONObject,
        remoteId: Long,
        quality: Int,
        favorited: Boolean,
        source: String = DEFAULT_SOURCE,
    ): Song {
        val normalizedSource = data.optString("source").ifBlank { source.ifBlank { DEFAULT_SOURCE } }
        val seconds = data.optInt("durationSeconds")
        return Song(
            title = data.optString("title", "未知歌曲"),
            artist = data.optString("artist", "未知歌手"),
            duration = if (seconds > 0) "%02d:%02d".format(seconds / 60, seconds % 60) else "网络歌曲",
            color = 0xFFFFB4A2,
            audioUri = placeholderUri(remoteId, quality, normalizedSource),
            remoteId = remoteId,
            coverUri = data.optString("coverUrl").ifBlank { null },
            lyricUri = lyricUri(remoteId, normalizedSource),
            album = data.optString("album", "未知专辑"),
            mid = data.optString("mid").ifBlank { null },
            vip = data.optBoolean("vip"),
            favorited = favorited,
            source = normalizedSource,
        )
    }

    fun setFavorite(song: Song, favorite: Boolean) {
        val id = song.remoteId?.takeIf { it > 0L }?.toString()
            ?: song.mid?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("网络歌曲缺少歌曲 ID 或 mid")
        val source = encode(song.source.ifBlank { DEFAULT_SOURCE })
        authorized("/api/v1/favorites/$source/$id", if (favorite) "POST" else "DELETE") { connection ->
            connection.inputStream.close()
        }
    }

    fun requestLyric(song: Song): String {
        val id = song.remoteId?.takeIf { it > 0L } ?: 0L
        require(id > 0L || !song.mid.isNullOrBlank()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val mid = song.mid?.takeIf { it.isNotBlank() }?.let { "&mid=${encode(it)}" }.orEmpty()
        return authorized("/api/v1/songs/$id/lyrics?source=${encode(song.source.ifBlank { DEFAULT_SOURCE })}$mid") { connection ->
            connection.inputStream.bufferedReader().use { it.readText() }
        }
    }

    /**
     * 取歌词的完整数据：`lrc` 是行级时间轴，`yrc` 是逐字时间轴。
     * 服务端默认返回纯文本以兼容旧客户端，必须显式带 `format=json` 才给到 yrc。
     */
    fun requestRichLyric(song: Song): RichLyric {
        val id = song.remoteId?.takeIf { it > 0L } ?: 0L
        require(id > 0L || !song.mid.isNullOrBlank()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val source = encode(song.source.ifBlank { DEFAULT_SOURCE })
        val mid = song.mid?.takeIf { it.isNotBlank() }?.let { "&mid=${encode(it)}" }.orEmpty()
        return authorized("/api/v1/songs/$id/lyrics?format=json&source=$source$mid") { connection ->
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
    fun register(username: String, password: String, email: String, verificationCode: String): TokenPair =
        authenticate("/api/v1/auth/register", username, password, email, verificationCode)

    /** 注册前发送邮箱验证码；接口成功时返回 204，没有响应体。 */
    fun sendRegistrationVerification(email: String) = publicJson("/api/v1/auth/email-verification", "POST", JSONObject().put("email", email))

    fun profile(): UserProfile = authorized("/api/v1/auth/profile") { connection ->
        connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("data").toProfile()
    }

    fun createImageTask(model: String, prompt: String, aspectRatio: String, imageSize: String, quality: String): ImageTask =
        authorizedJson(
            "/api/v1/draw/completions",
            "POST",
            JSONObject()
                .put("model", model)
                .put("prompt", prompt)
                .put("aspectRatio", aspectRatio)
                .put("imageSize", imageSize)
                .put("quality", quality),
        ) { data ->
            ImageTask(
                taskId = data.optString("taskId"),
                state = data.optString("status", "IN_PROGRESS"),
                progress = 0,
                imageUrl = null,
                error = null,
            )
        }

    fun requestImageTask(taskId: String): ImageTask = authorized("/api/v1/draw/result/${encode(taskId)}") { connection ->
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val data = body.optJSONObject("data") ?: JSONObject()
        ImageTask(
            taskId = data.optString("taskId"),
            state = data.optString("state", "FAILED"),
            progress = data.optInt("progress", 0),
            imageUrl = data.optJSONObject("result")?.optString("imageUrl")?.takeIf { it.isNotBlank() },
            error = data.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() },
        )
    }

    fun updateNickname(nickname: String): UserProfile = updateProfile(nickname = nickname)

    /** 资料接口支持按字段更新；头像传 null 表示清除，避免空字符串被服务端误作 URL。 */
    fun updateProfile(nickname: String? = null, avatarUrl: String? = null, clearAvatar: Boolean = false): UserProfile = authorizedJson(
        "/api/v1/auth/profile", "PATCH", JSONObject().apply {
            nickname?.let { put("nickname", it) }
            if (clearAvatar) put("avatarUrl", JSONObject.NULL) else avatarUrl?.let { put("avatarUrl", it) }
        },
    ) { data -> data.toProfile() }

    /** 上传头像到业务服务端，由服务端代为调用图床。 */
    fun uploadAvatar(uri: Uri, contentResolver: android.content.ContentResolver): UserProfile = authorizedMultipart(
        "/api/v1/auth/avatar", uri, contentResolver,
    ) { data -> data.toProfile() }

    /**
     * 向业务服务申请悟空 IM 连接凭据。
     *
     * 连接 Token 与现有访问令牌彼此独立；后者仅用于证明调用者身份，前者只交给悟空 IM
     * 客户端 SDK。凭据即将过期时由聊天模块重新调用此方法，不需要让用户重新登录。
     */
    fun requestImSession(deviceId: String): ImSession = authorizedJson(
        "/api/v1/im/session",
        "POST",
        JSONObject().put("deviceId", deviceId),
    ) { data ->
        ImSession(
            uid = data.getString("uid"),
            token = data.getString("token"),
            tokenExpiresAt = data.getLong("tokenExpiresAt"),
            deviceFlag = data.getInt("deviceFlag"),
            deviceLevel = data.getInt("deviceLevel"),
            gatewayUrl = data.getString("gatewayUrl"),
        )
    }

    /** 当前帐号退出时尽力使悟空 IM 关闭 Android 会话；网络失败不影响本地退出。 */
    fun revokeImSession() {
        runCatching {
            authorizedJson("/api/v1/im/session", "DELETE", JSONObject()) { Unit }
        }
    }

    /** 已登录用户经业务服务代理拉取悟空 IM 的最近会话，客户端不接触 5001。 */
    fun syncImConversations(lastMessageSeqs: String, messageCount: Int, version: Long): ImConversationSync = authorizedConversationSync(
        "/api/v1/im/sync/conversations",
        JSONObject().put("lastMessageSeqs", lastMessageSeqs).put("messageCount", messageCount).put("version", version),
    )

    /** 已认证用户经业务服务申请悟空原生撤回，客户端不直接接触 5001。 */
    fun revokeImMessage(channelId: String, messageId: String, clientMsgNo: String) {
        authorizedJson("/api/v1/im/messages/revoke", "POST", JSONObject()
            .put("channelId", channelId)
            .put("messageId", messageId)
            .put("clientMsgNo", clientMsgNo)) { Unit }
    }

    fun markImConversationRead(channelId: String) {
        authorizedJson("/api/v1/im/conversations/read", "POST", JSONObject().put("channelId", channelId)) { Unit }
    }

    fun imContacts(uids: List<String>): List<ImContact> {
        if (uids.isEmpty()) return emptyList()
        val encoded = java.net.URLEncoder.encode(uids.joinToString(","), "UTF-8")
        return authorized("/api/v1/im/contacts?uids=$encoded") { connection ->
            val rows = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.optJSONArray("data") ?: JSONArray()
            List(rows.length()) { index -> rows.optJSONObject(index) }.mapNotNull { row ->
                row?.optString("uid")?.takeIf { it.isNotBlank() }?.let { uid ->
                    ImContact(uid, row.optString("nickname").ifBlank { "加载昵称…" })
                }
            }
        }
    }

    /** 按悟空 IM 给出的游标同步单个频道的一页历史消息。 */
    fun syncImChannelMessages(
        channelId: String,
        startMessageSeq: Long,
        endMessageSeq: Long,
        limit: Int,
        pullMode: Int,
    ): JSONObject = authorizedJson(
        "/api/v1/im/sync/channel-messages",
        "POST",
        JSONObject()
            .put("channelId", channelId)
            .put("startMessageSeq", startMessageSeq)
            .put("endMessageSeq", endMessageSeq)
            .put("limit", limit)
            .put("pullMode", pullMode),
    ) { it }

    /** [change] 为 false 表示给旧账号补绑，为 true 表示换绑。 */
    fun sendEmailBindingVerification(email: String, change: Boolean) = authorizedJson(
        if (change) "/api/v1/auth/email/change-verification" else "/api/v1/auth/email/bind-verification",
        "POST", JSONObject().put("email", email),
    ) { Unit }

    fun confirmEmailBinding(email: String, verificationCode: String, change: Boolean) = authorizedJson(
        if (change) "/api/v1/auth/email/change" else "/api/v1/auth/email/bind",
        "POST", JSONObject().put("email", email).put("verificationCode", verificationCode),
    ) { Unit }

    fun announcements(): List<Announcement> = publicGet("/api/v1/announcements") { connection ->
        val data = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.optJSONArray("data")
            ?: return@publicGet emptyList()
        (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.let { item ->
                Announcement(
                    id = item.optLong("id"),
                    title = item.optString("title"),
                    content = item.optString("content"),
                    pinned = item.optInt("pinned") == 1,
                    publishedAt = item.optLong("published_at"),
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 云端歌单
    // ---------------------------------------------------------------------

    /** 读取当前账号的歌单元数据。服务端只返回当前账号所属的记录。 */
    fun listPlaylists(): List<Playlist> = authorized("/api/v1/playlists") { connection ->
        val envelope = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val data = envelope.optJSONArray("data")
            ?: envelope.optJSONObject("data")?.optJSONArray("playlists")
            ?: JSONArray()
        (0 until data.length()).mapNotNull { index -> data.optJSONObject(index)?.toPlaylist() }
    }

    /** 读取一个歌单的完整详情（含按 position 排序的歌曲）。 */
    fun getPlaylist(playlistId: Long): Playlist = authorized("/api/v1/playlists/${playlistId.requirePlaylistId()}") { connection ->
        val envelope = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        (envelope.optJSONObject("data") ?: envelope).toPlaylist()
    }

    /** 创建云端歌单。 */
    fun createPlaylist(name: String, description: String = "", coverUrl: String? = null): Playlist =
        authorizedJson(
            "/api/v1/playlists",
            "POST",
            JSONObject().apply {
                put("name", name)
                put("description", description)
                coverUrl?.let { put("coverUrl", it) }
            },
        ) { it.toPlaylist() }

    /** 更新歌单名称、简介或封面；未传的字段保持原值。 */
    fun updatePlaylist(
        playlistId: Long,
        name: String? = null,
        description: String? = null,
        coverUrl: String? = null,
        clearCover: Boolean = false,
    ): Playlist = authorizedJson(
        "/api/v1/playlists/${playlistId.requirePlaylistId()}",
        "PATCH",
        JSONObject().apply {
            name?.let { put("name", it) }
            description?.let { put("description", it) }
            if (clearCover) put("coverUrl", JSONObject.NULL) else coverUrl?.let { put("coverUrl", it) }
        },
    ) { it.toPlaylist() }

    /** 删除歌单；服务端以 204 返回，重复删除会报告资源不存在。 */
    fun deletePlaylist(playlistId: Long) {
        authorizedJson(
            "/api/v1/playlists/${playlistId.requirePlaylistId()}",
            "DELETE",
            JSONObject(),
        ) { Unit }
    }

    /** 添加或更新歌单中的歌曲快照；同一 source + songId 重复调用是幂等的。 */
    fun addSongToPlaylist(playlistId: Long, song: Song): Playlist = authorizedJson(
        "/api/v1/playlists/${playlistId.requirePlaylistId()}/songs",
        "POST",
        song.toPlaylistJson(),
    ) { it.toPlaylist() }

    /** 从歌单删除歌曲，返回删除后的完整详情。 */
    fun removeSongFromPlaylist(playlistId: Long, song: PlaylistSong): Playlist = authorizedJson(
        "/api/v1/playlists/${playlistId.requirePlaylistId()}/songs/${encode(song.source)}/${encode(song.songId)}",
        "DELETE",
        JSONObject(),
    ) { data ->
        (data.optJSONObject("playlist") ?: data).toPlaylist()
    }

    /** 以完整的稳定键列表调整顺序；服务端会拒绝缺歌或多歌的旧设备请求。 */
    fun reorderPlaylist(playlistId: Long, songs: List<PlaylistSong>): Playlist = authorizedJson(
        "/api/v1/playlists/${playlistId.requirePlaylistId()}/songs/order",
        "PATCH",
        JSONObject().put(
            "songs",
            JSONArray().apply {
                songs.forEach { put(JSONObject().put("source", it.source).put("songId", it.songId).put("mid", it.mid)) }
            },
        ),
    ) { it.toPlaylist() }

    /** 以完整快照替换歌单内容，用于本地拖动或离线批量同步。 */
    fun replacePlaylistSongs(playlistId: Long, songs: List<Song>): Playlist = authorizedJson(
        "/api/v1/playlists/${playlistId.requirePlaylistId()}/songs",
        "PUT",
        JSONObject().put("songs", JSONArray().apply { songs.forEach { put(it.toPlaylistJson()) } }),
    ) { it.toPlaylist() }

    /** Song -> 服务端歌曲快照；mid-only 结果使用 mid 作为稳定 songId。 */
    private fun Song.toPlaylistJson(): JSONObject {
        val songId = playlistSongId(this) ?: throw IllegalArgumentException("歌曲缺少可同步的 source 或 mid")
        return JSONObject().apply {
            put("source", source.ifBlank { DEFAULT_SOURCE })
            put("songId", songId)
            mid?.takeIf { it.isNotBlank() }?.let { put("mid", it) }
            put("title", title)
            put("artist", artist)
            put("album", album)
            coverUri?.takeUnless { it.startsWith("file:", ignoreCase = true) }?.let { put("coverUrl", it) }
            put("duration", duration)
            audioUri?.takeUnless { it.startsWith("file:", ignoreCase = true) }?.let { put("audioUrl", it) }
            lyricUri?.takeUnless { it.startsWith("file:", ignoreCase = true) }?.let { put("lyricUrl", it) }
            type?.let { put("type", it) }
        }
    }

    private fun JSONObject.toPlaylist(): Playlist {
        val songs = optJSONArray("songs")?.let { rows ->
            (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.toPlaylistSong() }
        }.orEmpty()
        return Playlist(
            id = optLong("id"),
            name = optString("name", optString("title", "未命名歌单")),
            description = optString("description"),
            coverUrl = nullableString("coverUrl"),
            songCount = optInt("songCount", songs.size).coerceAtLeast(songs.size),
            revision = optLong("revision", 1L).coerceAtLeast(1L),
            createdAt = optLong("createdAt"),
            updatedAt = optLong("updatedAt"),
            songs = songs.sortedBy { it.position },
        )
    }

    private fun JSONObject.toPlaylistSong(): PlaylistSong = PlaylistSong(
        source = optString("source").ifBlank { DEFAULT_SOURCE },
        songId = optString("songId").ifBlank { optString("id") },
        mid = nullableString("mid"),
        title = optString("title"),
        artist = optString("artist", optString("singer")),
        album = optString("album"),
        coverUrl = nullableString("coverUrl"),
        duration = nullableString("duration"),
        audioUrl = nullableString("audioUrl"),
        lyricUrl = nullableString("lyricUrl"),
        type = if (has("type") && !isNull("type")) optInt("type") else null,
        position = optInt("position", 0),
        addedAt = optLong("addedAt"),
        updatedAt = optLong("updatedAt"),
    )

    private fun Long.requirePlaylistId(): Long = takeIf { it > 0L }
        ?: throw IllegalArgumentException("歌单 ID 不合法")

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
        connection.getHeaderField(HEADER_LATEST_VERSION)?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { latest -> runCatching { onLatestVersion?.invoke(latest) } }
        connection.getHeaderField(HEADER_LATEST_PATCH)?.toIntOrNull()?.takeIf { it > 0 }
            ?.let { latestPatch -> runCatching { onLatestPatch?.invoke(latestPatch) } }
    }

    private fun authenticate(path: String, username: String, password: String, email: String? = null, verificationCode: String? = null): TokenPair =
        postJson(path, JSONObject().put("username", username).put("password", password).apply {
            email?.let { put("email", it) }
            verificationCode?.let { put("verificationCode", it) }
        }, "认证失败")

    /** 带访问令牌的 JSON 写请求；认证过期时复用与普通读取一致的续期逻辑。 */
    private fun <T> authorizedJson(path: String, method: String, body: JSONObject, read: (JSONObject) -> T): T {
        var token = tokenProvider.validToken() ?: throw SessionExpiredException()
        repeat(MAX_AUTH_ATTEMPTS) { attempt ->
            val connection = open(path, method, token).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code in 200..299) {
                noteLatestVersion(connection)
                val data = if (code == HttpURLConnection.HTTP_NO_CONTENT) JSONObject() else {
                    connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.optJSONObject("data") ?: JSONObject()
                }
                return read(data)
            }
            if (code != HttpURLConnection.HTTP_UNAUTHORIZED || attempt == MAX_AUTH_ATTEMPTS - 1) {
                throw IllegalStateException(messageOf(connection, "请求失败：HTTP $code"))
            }
            runCatching { connection.errorStream?.close() }
            token = tokenProvider.renewToken(token) ?: throw SessionExpiredException()
        }
        throw SessionExpiredException()
    }

    private fun <T> authorizedMultipart(
        path: String,
        uri: Uri,
        resolver: android.content.ContentResolver,
        read: (JSONObject) -> T,
    ): T {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取图片")
        check(bytes.size <= 5 * 1024 * 1024) { "图片不能超过 5 MB" }
        val boundary = "----TaotaoAvatar${System.currentTimeMillis()}"
        var token = tokenProvider.validToken() ?: throw SessionExpiredException()
        repeat(MAX_AUTH_ATTEMPTS) { attempt ->
            val connection = open(path, "POST", token).apply {
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            connection.outputStream.use { output ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                output.write(bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            if (code in 200..299) {
                noteLatestVersion(connection)
                val data = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.optJSONObject("data") ?: JSONObject()
                return read(data)
            }
            if (code != 401 || attempt == MAX_AUTH_ATTEMPTS - 1) throw IllegalStateException(messageOf(connection, "头像上传失败：HTTP $code"))
            runCatching { connection.errorStream?.close() }
            token = tokenProvider.renewToken(token) ?: throw SessionExpiredException()
        }
        throw SessionExpiredException()
    }

    /**
     * 兼容两代 IM 代理：旧服务将悟空原始数组放在 data，新服务固定放在 data.conversations。
     * 保持兼容能避免服务灰度期间客户端把有效离线消息误判为空。
     */
    private fun authorizedConversationSync(path: String, body: JSONObject): ImConversationSync {
        var token = tokenProvider.validToken() ?: throw SessionExpiredException()
        repeat(MAX_AUTH_ATTEMPTS) { attempt ->
            val connection = open(path, "POST", token).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code in 200..299) {
                noteLatestVersion(connection)
                return connection.inputStream.bufferedReader().use {
                    val data = JSONObject(it.readText()).opt("data")
                    when (data) {
                        is JSONArray -> ImConversationSync(uid = null, conversations = data)
                        is JSONObject -> ImConversationSync(
                            uid = data.optString("uid").takeIf { it.isNotBlank() },
                            conversations = data.optJSONArray("conversations") ?: JSONArray(),
                        )
                        else -> ImConversationSync(uid = null, conversations = JSONArray())
                    }
                }
            }
            if (code != HttpURLConnection.HTTP_UNAUTHORIZED || attempt == MAX_AUTH_ATTEMPTS - 1) {
                throw IllegalStateException(messageOf(connection, "请求失败：HTTP $code"))
            }
            runCatching { connection.errorStream?.close() }
            token = tokenProvider.renewToken(token) ?: throw SessionExpiredException()
        }
        throw SessionExpiredException()
    }

    private fun publicJson(path: String, method: String, body: JSONObject) {
        val connection = open(path, method, null).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        check(connection.responseCode in 200..299) { messageOf(connection, "请求失败") }
        runCatching { connection.inputStream.close() }
    }

    private fun <T> publicGet(path: String, read: (HttpURLConnection) -> T): T {
        val connection = open(path, "GET", null)
        check(connection.responseCode in 200..299) { messageOf(connection, "请求失败") }
        return read(connection)
    }

    private fun open(path: String, method: String, token: String?): HttpURLConnection =
        (URL(ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/x-ndjson, application/json")
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
            appVersionCode.takeIf { it > 0 }?.let { setRequestProperty(HEADER_APP_VERSION, it.toString()) }
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun JSONObject.toSong(quality: Int): Song {
        val id = optLong("id")
        val source = optString("source").ifBlank { DEFAULT_SOURCE }
        val mid = optString("mid").ifBlank { null }
        val type = if (has("type") && !isNull("type")) optInt("type") else null
        return Song(
            title = optString("title", "未知歌曲"),
            artist = optString("artist", "未知歌手"),
            duration = optString("duration", "网络歌曲"),
            color = 0xFFFFB4A2,
            remoteId = id.takeIf { it > 0 },
            // 队列里存的是永不过期的占位地址，真正的上游直链在取流那一刻才解析。
            // 服务端下发的 audioUrl 只是同样的占位地址，这里直接自己拼，音质才跟得上偏好。
            audioUri = placeholderUri(id.takeIf { it > 0 }, mid, type, quality, source)
                ?: optString("audioUrl").takeIf { it.isNotBlank() },
            coverUri = optString("coverUrl").ifBlank { null },
            lyricUri = optString("lyricUrl").ifBlank { null }?.let {
                if (it.startsWith("http")) it else "$ENDPOINT$it"
            } ?: lyricUri(id.takeIf { it > 0 }, mid, source),
            album = optString("album", "未知专辑"), subtitle = optString("subtitle"), releaseTime = optString("time"),
            mid = mid,
            type = type,
            vip = optBoolean("vip"),
            // ⚠️ 必须带默认值 true。`optBoolean(name)` 在字段缺失时返回 **false**，
            // 那会把「服务端没下发这个字段」当成「不可播」，旧服务端下整个列表全被置灰。
            playable = optBoolean("playable", true),
            favorited = optBoolean("favorited"),
            source = source,
        )
    }

    private fun JSONObject.toProfile() = UserProfile(
        username = optString("username"),
        // org.json 会把 JSON null 读成字面字符串 "null"，必须先用 isNull 分支。
        email = nullableString("email"),
        nickname = optString("nickname").ifBlank { optString("username") },
        avatarUrl = nullableString("avatarUrl"),
    )

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        /** 后端地址。热更新模块也要用，因此对包内公开，保持单一来源。 */
        const val ENDPOINT = "https://music.xydaigua.cn"
        private const val MAX_AUTH_ATTEMPTS = 2
        private const val FAVORITE_INFO_CONCURRENCY = 4
        private const val BATCH_INFO_SIZE = 60
        private const val DEFAULT_SOURCE = "tencent"

        /**
         * 搜索范围：聚合查询（服务端按 `AGGREGATED_SOURCES` 并发查多个音源）。
         *
         * 与 [DEFAULT_SOURCE] 刻意分开：后者是**歌曲身份**缺失时的兜底音源，
         * 前者是**搜索范围**。两者语义不同，混用会让「搜不到歌」和「放不出歌」互相冒充。
         */
        const val SEARCH_SOURCE_ALL = "all"
        /** Android 搜索页固定使用酷我（波点），界面不再暴露音源选择。 */
        const val SEARCH_SOURCE_KUWO = "kuwo"

        /** 网络歌曲的稳定键；remoteId=0 或 null 时回退到上游 mid。 */
        fun playlistSongId(song: Song): String? = song.remoteId?.takeIf { it > 0L }?.toString()
            ?: song.mid?.trim()?.takeIf { it.isNotBlank() }

        /** 音质取值上限。实测上游档位到 18（NAC）。 */
        const val MAX_QUALITY = 18

        /** 服务端下发的当前全量可用最高版本号。 */
        const val HEADER_LATEST_VERSION = "x-latest-version-code"
        const val HEADER_LATEST_PATCH = "x-latest-patch-version"
        const val HEADER_APP_VERSION = "x-app-version-code"

        /**
         * 队列里存的占位地址。
         *
         * 刻意用自家的 `/play` 路径而不是上游直链：直链是限时的，存进 MediaItem 或
         * 持久化队列后，冷启动恢复时早就失效了。占位地址永不过期，且 [isOwnEndpoint]
         * 认得它，取流时由 PlaybackService 的 ResolvingDataSource 换成直链；
         * 换不成就照这个地址走服务器代理，正好是天然的兜底。
         */
        fun placeholderUri(remoteId: Long, quality: Int, source: String = DEFAULT_SOURCE): String =
            requireNotNull(placeholderUri(remoteId, null, null, quality, source))

        /** 永不过期的播放占位地址；数字 ID 缺失时把 mid/type 一起写入查询参数。 */
        fun placeholderUri(
            remoteId: Long?,
            mid: String?,
            type: Int?,
            quality: Int,
            source: String = DEFAULT_SOURCE,
        ): String? {
            val id = remoteId?.takeIf { it > 0L } ?: 0L
            val validMid = mid?.trim()?.takeIf { it.isNotBlank() }
            if (id <= 0L && validMid == null) return null
            return buildString {
                append("$ENDPOINT/api/v1/songs/$id/play?quality=${quality.coerceIn(0, MAX_QUALITY)}")
                validMid?.let { append("&mid=${URLEncoder.encode(it, Charsets.UTF_8.name())}") }
                type?.let { append("&type=$it") }
                append("&source=${URLEncoder.encode(source.ifBlank { DEFAULT_SOURCE }, Charsets.UTF_8.name())}")
            }
        }

        /** 歌词地址与播放占位地址使用同一来源，避免相同 ID 被路由到另一个上游。 */
        fun lyricUri(remoteId: Long, source: String = DEFAULT_SOURCE): String =
            requireNotNull(lyricUri(remoteId, null, source))

        /** 歌词占位地址；mid-only 歌曲使用 `id=0&mid=...`。 */
        fun lyricUri(remoteId: Long?, mid: String?, source: String = DEFAULT_SOURCE): String? {
            val id = remoteId?.takeIf { it > 0L } ?: 0L
            val validMid = mid?.trim()?.takeIf { it.isNotBlank() }
            if (id <= 0L && validMid == null) return null
            return buildString {
                append("$ENDPOINT/api/v1/songs/$id/lyrics?source=")
                append(URLEncoder.encode(source.ifBlank { DEFAULT_SOURCE }, Charsets.UTF_8.name()))
                validMid?.let { append("&mid=${URLEncoder.encode(it, Charsets.UTF_8.name())}") }
            }
        }

        /** 从占位地址里取回歌曲 ID、音质和来源，供 ResolvingDataSource 解析用。 */
        fun parsePlaceholderDetails(url: String): Placeholder? = runCatching {
            val parsed = URL(url)
            if (parsed.host != URL(ENDPOINT).host) return null
            val id = Regex("""/api/v1/songs/(\d+)/play""").find(parsed.path)?.groupValues?.get(1)?.toLongOrNull()
                ?: return null
            val query = parsed.query.orEmpty()
            val quality = Regex("""(?:^|&)quality=(\d+)""").find(query)?.groupValues?.get(1)?.toIntOrNull()
                ?: AudioQuality.Default.value
            val encodedSource = Regex("""(?:^|&)source=([^&]*)""").find(query)?.groupValues?.get(1)
            val source = encodedSource
                ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
                ?.ifBlank { DEFAULT_SOURCE }
                ?: DEFAULT_SOURCE
            val encodedMid = Regex("""(?:^|&)mid=([^&]*)""").find(query)?.groupValues?.get(1)
            val mid = encodedMid?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }?.ifBlank { null }
            val type = Regex("""(?:^|&)type=(-?\d+)""").find(query)?.groupValues?.get(1)?.toIntOrNull()
            Placeholder(id.takeIf { it > 0L }, mid, type, quality, source)
        }.getOrNull()

        /** 从占位地址里取回歌曲 ID 与音质，供 ResolvingDataSource 解析用。 */
        fun parsePlaceholder(url: String): Pair<Long, Int>? =
            parsePlaceholderDetails(url)?.let { placeholder -> placeholder.remoteId?.let { it to placeholder.quality } }

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

        /**
         * 退出登录时撤销悟空 IM 的 Android 会话。该请求只使用现有访问令牌，失败不影响
         * 本地令牌清理；客户端不会因聊天基础设施暂时不可用而无法退出。
         */
        fun revokeImSession(accessToken: String) {
            runCatching {
                val connection = (URL(ENDPOINT + "/api/v1/im/session").openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("User-Agent", "TaotaoMusic/1.0")
                }
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
            return TokenPair(
                accessToken = data.getString("accessToken"),
                refreshToken = data.getString("refreshToken"),
                expiresIn = data.optInt("expiresIn", 900),
                // refresh 响应不带 user；登录/注册的 user 与令牌平铺在同一 data 中。
                userId = data.optJSONObject("user")?.optLong("id", -1L)?.takeIf { it > 0L },
            )
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
 * [dropped] 是服务端因拿不到可用播放地址而丢弃的数量（不再逐首探测，改用上游元数据预筛）。
 * **它不再恒为 0** —— 酷我搜热门歌手时上游给 20 条、20 条全被预筛掉，这里就是 20。
 * 所以「[songs] 为空」有两种含义，要靠它区分：`dropped > 0` 是「搜到了但都不可播」，
 * 该提示用户换个音源；`dropped == 0` 才是真的没搜到。
 * [hasMore] 与 [total] 服务端一直在返回，客户端以前直接丢掉，现在用来驱动滚到底加载下一页。
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
