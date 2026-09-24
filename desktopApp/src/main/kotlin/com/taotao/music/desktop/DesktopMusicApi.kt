package com.taotao.music.desktop

import com.taotao.music.model.AudioQuality
import com.taotao.music.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/** 云端歌曲的稳定身份：优先数字 ID，没有数字 ID 时使用上游 mid。 */
internal fun Song.remoteIdentity(): String? = remoteId?.takeIf { it > 0L }?.toString()
    ?: mid?.trim()?.takeIf { it.isNotBlank() }

internal fun Song.hasRemoteIdentity(): Boolean = remoteIdentity() != null

internal fun Song.remoteIdentityAliases(): Set<String> = buildSet {
    remoteId?.takeIf { it > 0L }?.let { add(it.toString()) }
    mid?.trim()?.takeIf(String::isNotBlank)?.let(::add)
}

internal fun Song.sameRemoteSong(other: Song): Boolean =
    source.equals(other.source, ignoreCase = true) && remoteIdentityAliases().any { it in other.remoteIdentityAliases() }

/** Windows 音乐 API。只依赖 JDK HTTP 客户端，保持与 Android 后端契约一致。 */
class DesktopMusicApi(
    private val session: DesktopSession,
    val endpoint: String = DEFAULT_ENDPOINT,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    data class SearchPage(
        val songs: List<Song>,
        val hasMore: Boolean,
        val total: Int,
        val page: Int,
    )

    data class ResolvedLink(val url: String, val quality: Int, val kbps: String, val fallback: Boolean)
    data class SongShare(val token: String, val url: String)
    data class QualityOption(val quality: Int, val label: String, val size: Long)
    data class RichLyric(val lrc: String, val yrc: String, val trans: String = "")
    data class RecentPlayback(
        val source: String,
        val songId: String,
        val playedAt: Long,
        val firstPlayedAt: Long,
        val playCount: Int,
        val completedCount: Int,
        val totalListenedMs: Long,
    )
    data class PlaybackState(val revision: Long, val clearedAt: Long, val marker: String?)
    data class PlaybackReport(val currentHistoryRevision: Long)

    /** 云端歌单歌曲快照。songId 是字符串，允许数字 ID 或上游 mid。 */
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
        fun toSong(quality: Int, endpoint: String): Song {
            val numericId = songId.toLongOrNull()?.takeIf { it > 0L }
            return Song(
                title = title.ifBlank { "未知歌曲" },
                artist = artist.ifBlank { "未知歌手" },
                duration = duration?.ifBlank { null } ?: "网络歌曲",
                color = 0xFFFFB4A2,
                audioUri = DesktopMusicApi.placeholderUri(numericId, mid, type, quality, source, endpoint)
                    ?: audioUrl?.takeIf { it.isNotBlank() },
                remoteId = numericId,
                coverUri = coverUrl?.takeIf { it.isNotBlank() },
                lyricUri = DesktopMusicApi.lyricUri(numericId, mid, source, endpoint)
                    ?: lyricUrl?.takeIf { it.isNotBlank() },
                album = album,
                mid = mid,
                type = type,
                source = source,
            )
        }
    }

    /** 云端歌单摘要或详情；详情的 songs 可为空表示接口只返回摘要。 */
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
    ) {
        fun songsAsSongs(quality: Int, endpoint: String): List<Song> =
            songs.sortedBy { it.position }.map { it.toSong(quality, endpoint) }
    }

    /** 歌单列表按服务端更新时间排序；data 既兼容数组也兼容旧端对象包装。 */
    fun playlists(expectedGeneration: Long? = null): List<Playlist> {
        val response = authorizedResponse("/api/v1/playlists", "GET", expectedGeneration = expectedGeneration)
        ensureSuccess(response)
        return parsePlaylistArray(response.body()).map { it.toPlaylist() }
    }

    fun playlist(playlistId: Long, quality: Int = AudioQuality.Default.value, expectedGeneration: Long? = null): Playlist {
        require(playlistId > 0L) { "歌单 ID 不合法" }
        val data = authorizedJson("/api/v1/playlists/$playlistId", expectedGeneration = expectedGeneration)
        return data.toPlaylist(quality)
    }

    fun createPlaylist(name: String, description: String = "", expectedGeneration: Long? = null): Playlist {
        val data = authorizedJson(
            "/api/v1/playlists",
            "POST",
            JSONObject().put("name", name).put("description", description),
            expectedGeneration = expectedGeneration,
        )
        return data.toPlaylist()
    }

    fun updatePlaylist(playlistId: Long, name: String? = null, description: String? = null, expectedGeneration: Long? = null): Playlist {
        require(playlistId > 0L) { "歌单 ID 不合法" }
        val body = JSONObject().apply {
            name?.let { put("name", it) }
            description?.let { put("description", it) }
        }
        val data = authorizedJson("/api/v1/playlists/$playlistId", "PATCH", body, expectedGeneration = expectedGeneration)
        return data.toPlaylist()
    }

    fun deletePlaylist(playlistId: Long, expectedGeneration: Long? = null) {
        require(playlistId > 0L) { "歌单 ID 不合法" }
        val response = authorizedResponse("/api/v1/playlists/$playlistId", "DELETE", expectedGeneration = expectedGeneration)
        ensureSuccess(response)
    }

    fun addPlaylistSong(playlistId: Long, song: Song, expectedGeneration: Long? = null): Playlist {
        require(playlistId > 0L) { "歌单 ID 不合法" }
        val body = song.toPlaylistJson()
        val data = authorizedJson("/api/v1/playlists/$playlistId/songs", "POST", body, expectedGeneration = expectedGeneration)
        return data.toPlaylist()
    }

    fun removePlaylistSong(playlistId: Long, song: Song, expectedGeneration: Long? = null): Playlist? {
        require(playlistId > 0L) { "歌单 ID 不合法" }
        val identity = requireNotNull(song.remoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val response = authorizedResponse(
            "/api/v1/playlists/$playlistId/songs/${encode(song.source)}/${encode(identity)}",
            "DELETE",
            expectedGeneration = expectedGeneration,
        )
        ensureSuccess(response)
        val envelope = JSONObject(response.body())
        return envelope.optJSONObject("data")?.optJSONObject("playlist")?.toPlaylist()
    }

    fun reorderPlaylist(playlistId: Long, songs: List<Song>, expectedGeneration: Long? = null): Playlist {
        require(playlistId > 0L) { "歌单 ID 不合法" }
        val order = JSONArray().apply {
            songs.forEach { song ->
                val identity = requireNotNull(song.remoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
                put(JSONObject().put("source", song.source).put("songId", identity))
            }
        }
        val data = authorizedJson(
            "/api/v1/playlists/$playlistId/songs/order",
            "PATCH",
            JSONObject().put("songs", order),
            expectedGeneration = expectedGeneration,
        )
        return data.toPlaylist()
    }

    private fun Song.toPlaylistJson(): JSONObject {
        val identity = requireNotNull(remoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
        return JSONObject().apply {
            put("source", source.ifBlank { "tencent" })
            put("songId", identity)
            mid?.takeIf { it.isNotBlank() }?.let { put("mid", it) }
            put("title", title)
            put("artist", artist)
            put("album", album)
            coverUri?.takeIf { it.startsWith("http", ignoreCase = true) || it.startsWith("/") }?.let { put("coverUrl", it) }
            put("duration", duration)
            lyricUri?.takeIf { it.startsWith("http", ignoreCase = true) || it.startsWith("/") }?.let { put("lyricUrl", it) }
            type?.let { put("type", it) }
        }
    }

    private fun parsePlaylistArray(raw: String): List<JSONObject> {
        val envelope = JSONObject(raw)
        val data = envelope.opt("data")
        return when (data) {
            is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            is JSONObject -> data.optJSONArray("items")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } ?: listOf(data)
            else -> emptyList()
        }
    }

    private fun JSONObject.toPlaylist(quality: Int = AudioQuality.Default.value): Playlist {
        val songsJson = optJSONArray("songs") ?: JSONArray()
        val songs = (0 until songsJson.length()).mapNotNull { index -> songsJson.optJSONObject(index)?.toPlaylistSong() }
        return Playlist(
            id = optLong("id"),
            name = optString("name", optString("title", "未命名歌单")),
            description = optString("description"),
            coverUrl = optString("coverUrl").ifBlank { null },
            songCount = optInt("songCount", songs.size),
            revision = optLong("revision", 1L),
            createdAt = optLong("createdAt"),
            updatedAt = optLong("updatedAt"),
            songs = songs,
        )
    }

    private fun JSONObject.toPlaylistSong(): PlaylistSong = PlaylistSong(
        source = optString("source").ifBlank { "tencent" },
        songId = optString("songId", optString("id")),
        mid = optString("mid").ifBlank { null },
        title = optString("title"),
        artist = optString("artist"),
        album = optString("album"),
        coverUrl = optString("coverUrl").ifBlank { null },
        duration = optString("duration").ifBlank { null },
        audioUrl = optString("audioUrl").ifBlank { null },
        lyricUrl = optString("lyricUrl").ifBlank { null },
        type = if (has("type") && !isNull("type")) optInt("type") else null,
        position = optInt("position"),
        addedAt = optLong("addedAt"),
        updatedAt = optLong("updatedAt"),
    )

    /** 按来源批量补全歌曲资料，收藏页和最近播放页冷启动时使用。 */
    fun songsByKeys(
        keys: Collection<String>,
        quality: Int = AudioQuality.Default.value,
        expectedGeneration: Long? = null,
    ): List<Song> {
        val grouped = keys.mapNotNull { value ->
            val separator = value.indexOf(':')
            if (separator <= 0 || separator == value.lastIndex) null
            else value.substring(0, separator) to value.substring(separator + 1)
        }.groupBy({ it.first }, { it.second })
        return grouped.flatMap { (source, identities) ->
            identities.chunked(60).flatMap { batch ->
                val ids = batch.filter { identity -> identity.toLongOrNull()?.let { it > 0L } == true }
                val mids = batch.filter { identity -> identity.toLongOrNull()?.let { it > 0L } != true }
                val identityQuery = buildList {
                    if (ids.isNotEmpty()) add("ids=${encode(ids.joinToString(","))}")
                    if (mids.isNotEmpty()) add("mids=${encode(mids.joinToString(","))}")
                }.joinToString("&")
                // 批量资料失败时必须让同步层保留本地快照；静默返回空列表会把暂时的网络故障
                // 当成服务端“没有歌曲”，进而覆盖收藏或最近播放。
                val data = authorizedJson(
                    "/api/v1/songs/batch-info?$identityQuery&source=${encode(source)}",
                    expectedGeneration = expectedGeneration,
                )
                val rows = data.optJSONArray("songs") ?: JSONArray()
                (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.toSongInfo(source, quality) }
            }
        }
    }

    /** 注册页的验证码发送接口是公开的，不需要先建立会话。 */
    fun sendRegistrationVerification(email: String) {
        val request = HttpRequest.newBuilder(URI.create(endpoint + "/api/v1/auth/email-verification"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSONObject().put("email", email).toString()))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw DesktopApiException("验证码发送失败：${errorMessage(response.body())}")
    }

    /** 搜索结果逐行回调；回调运行在当前搜索协程，取消搜索时不会脱离父 Job。 */
    suspend fun search(
        keyword: String,
        page: Int = 1,
        num: Int = 60,
        quality: Int = AudioQuality.Default.value,
        source: String = "all",
        onProgress: suspend (List<Song>) -> Unit = {},
        expectedGeneration: Long? = null,
    ): SearchPage {
        val path = "/api/v1/search?keyword=${encode(keyword)}&page=${page.coerceAtLeast(1)}" +
            "&num=${num.coerceIn(1, 60)}&quality=${quality.coerceIn(0, MAX_QUALITY)}&source=${encode(source)}"
        val response = authorizedStreamResponse(
            path,
            "GET",
            accept = "application/x-ndjson, application/json",
            expectedGeneration = expectedGeneration,
        )
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw DesktopApiException("搜索失败：HTTP ${response.statusCode()}")
        }
        val songs = mutableListOf<Song>()
        var hasMore = false
        var total = 0
        response.body().bufferedReader().use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureGeneration(expectedGeneration)
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val record = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (record.optString("type")) {
                    "song" -> record.optJSONObject("data")?.let { data ->
                        songs += data.toSong(quality)
                        onProgress(songs.toList())
                    }
                    "end" -> record.optJSONObject("meta")?.let { meta ->
                        hasMore = meta.optBoolean("hasMore")
                        total = meta.optInt("total", songs.size)
                    }
                }
            }
        }
        return SearchPage(songs, hasMore, total, page)
    }

    fun resolveLink(song: Song, quality: Int, expectedGeneration: Long? = null): ResolvedLink {
        requireNotNull(song.remoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val id = song.remoteId?.takeIf { it > 0L } ?: 0L
        val requestedQuality = requestQuality(song.source, quality)
        val query = buildString {
            append("?quality=$requestedQuality")
            song.mid?.takeIf(String::isNotBlank)?.let { append("&mid=${encode(it)}") }
            song.type?.let { append("&type=$it") }
            append("&source=${encode(song.source)}")
        }
        val data = authorizedJson("/api/v1/songs/$id/link$query", expectedGeneration = expectedGeneration)
        return ResolvedLink(
            url = data.getString("url"),
            quality = data.optInt("quality", requestedQuality),
            kbps = data.optString("kbps"),
            fallback = data.optBoolean("fallback"),
        )
    }

    fun requestQualities(song: Song, expectedGeneration: Long? = null): List<QualityOption> {
        requireNotNull(song.remoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val id = song.remoteId?.takeIf { it > 0L } ?: 0L
        val mid = song.mid?.takeIf(String::isNotBlank)?.let { "&mid=${encode(it)}" }.orEmpty()
        val data = authorizedJson("/api/v1/songs/$id/info?source=${encode(song.source)}$mid", expectedGeneration = expectedGeneration)
        val values = data.optJSONArray("qualities") ?: JSONArray()
        return (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.let { item ->
                QualityOption(item.optInt("quality"), item.optString("label"), item.optLong("size"))
            }
        }
    }

    /** 由服务端生成短链，Windows UI 只负责把返回的链接交给用户。 */
    fun createSongShare(song: Song, expectedGeneration: Long? = null): SongShare {
        require(song.hasRemoteIdentity()) { "歌曲缺少可分享的远端身份" }
        val body = JSONObject()
            .put("source", song.source.ifBlank { "tencent" })
            .apply {
                song.remoteId?.takeIf { it > 0L }?.let { put("remoteId", it) }
                song.mid?.takeIf(String::isNotBlank)?.let { put("mid", it) }
                song.type?.let { put("type", it) }
            }
        val data = authorizedJson(
            "/api/v1/shares/songs",
            "POST",
            body,
            expectedGeneration = expectedGeneration,
        )
        return SongShare(data.getString("token"), data.getString("url"))
    }

    fun requestRichLyric(song: Song, expectedGeneration: Long? = null): RichLyric {
        requireNotNull(song.remoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val id = song.remoteId?.takeIf { it > 0L } ?: 0L
        val mid = song.mid?.takeIf(String::isNotBlank)?.let { "&mid=${encode(it)}" }.orEmpty()
        val path = "/api/v1/songs/$id/lyrics?format=json&source=${encode(song.source)}$mid"
        val response = authorizedResponse(path, "GET", null, accept = "application/json, text/plain", expectedGeneration = expectedGeneration)
        ensureSuccess(response)
        val raw = response.body()
        val envelope = runCatching { JSONObject(raw) }.getOrNull()
            ?: return RichLyric(raw, "")
        val data = envelope.optJSONObject("data") ?: envelope
        return RichLyric(data.optString("lrc"), data.optString("yrc"), data.optString("trans"))
    }

    /** 收藏接口的 GET 返回裸数组，不要按普通信封解析。 */
    fun favoriteKeys(expectedGeneration: Long? = null): Set<String> {
        val response = authorizedResponse("/api/v1/favorites", "GET", expectedGeneration = expectedGeneration)
        ensureSuccess(response)
        val envelope = JSONObject(response.body())
        val array = envelope.optJSONArray("data") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val source = item.optString("source").ifBlank { "tencent" }
                val id = item.optString("songId").takeIf(String::isNotBlank)
                id?.let { key(source, it) }
            }
        }.toSet()
    }

    fun setFavorite(song: Song, favorite: Boolean, expectedGeneration: Long? = null) {
        val id = requireNotNull(song.remoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
        val response = authorizedResponse(
            "/api/v1/favorites/${encode(song.source)}/${encode(id)}",
            if (favorite) "POST" else "DELETE",
            expectedGeneration = expectedGeneration,
        )
        ensureSuccess(response)
    }

    fun recentPlayback(limit: Int = 500, expectedGeneration: Long? = null): List<RecentPlayback> {
        val response = authorizedResponse(
            "/api/v1/playback/recent?limit=${limit.coerceIn(1, 500)}",
            "GET",
            expectedGeneration = expectedGeneration,
        )
        ensureSuccess(response)
        val envelope = JSONObject(response.body())
        val data = envelope.opt("data")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("entries") ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { item ->
            val source = item.optString("source")
            val id = item.optString("songId")
            if (source.isBlank() || id.isBlank()) null else RecentPlayback(
                source = source,
                songId = id,
                playedAt = item.optLong("lastPlayedAt"),
                firstPlayedAt = item.optLong("firstPlayedAt"),
                playCount = item.optInt("playCount"),
                completedCount = item.optInt("completedCount"),
                totalListenedMs = item.optLong("totalListenedMs"),
            )
        } }
    }

    fun historyState(expectedGeneration: Long? = null): PlaybackState {
        val data = authorizedJson("/api/v1/playback/recent/state", expectedGeneration = expectedGeneration)
        return PlaybackState(data.optLong("revision"), data.optLong("clearedAt", data.optLong("clearedBefore")), data.optString("marker").takeIf(String::isNotBlank))
    }

    fun clearRecent(marker: String? = null, expectedGeneration: Long? = null): PlaybackState {
        val suffix = marker?.takeIf(String::isNotBlank)?.let { "?marker=${encode(it)}" }.orEmpty()
        val response = authorizedResponse("/api/v1/playback/recent$suffix", "DELETE", expectedGeneration = expectedGeneration)
        ensureSuccess(response)
        if (response.body().isBlank()) return PlaybackState(0, 0, null)
        val envelope = JSONObject(response.body())
        val data = envelope.optJSONObject("data") ?: envelope
        return PlaybackState(data.optLong("revision"), data.optLong("clearedAt", data.optLong("clearedBefore")), data.optString("marker").takeIf(String::isNotBlank))
    }

    fun reportPlayback(
        sessionId: String,
        deviceId: String,
        song: Song,
        startedAt: Long,
        lastPlayedAt: Long,
        listenedMs: Long,
        durationSeconds: Int?,
        completed: Boolean,
        historyRevision: Long,
        expectedGeneration: Long? = null,
    ): PlaybackReport = reportPlayback(
        sessionId = sessionId,
        deviceId = deviceId,
        source = song.source,
        songId = requireNotNull(song.remoteIdentity()),
        startedAt = startedAt,
        lastPlayedAt = lastPlayedAt,
        listenedMs = listenedMs,
        durationSeconds = durationSeconds,
        completed = completed,
        historyRevision = historyRevision,
        expectedGeneration = expectedGeneration,
    )

    fun reportPlayback(
        sessionId: String,
        deviceId: String,
        source: String,
        songId: String,
        startedAt: Long,
        lastPlayedAt: Long,
        listenedMs: Long,
        durationSeconds: Int?,
        completed: Boolean,
        historyRevision: Long,
        expectedGeneration: Long? = null,
    ): PlaybackReport {
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("deviceId", deviceId)
            .put("source", source.ifBlank { "tencent" })
            .put("songId", songId)
            .put("startedAt", startedAt)
            .put("lastPlayedAt", lastPlayedAt.coerceAtLeast(startedAt))
            .put("listenedMs", listenedMs.coerceAtLeast(0L))
            .put("completed", completed)
            .put("durationSeconds", durationSeconds)
            .put("historyRevision", historyRevision.coerceAtLeast(0L))
        val data = authorizedJson("/api/v1/playback/sessions", "POST", body, expectedGeneration = expectedGeneration)
        return PlaybackReport(data.optLong("currentHistoryRevision", historyRevision))
    }

    private fun JSONObject.toSong(quality: Int): Song {
        val id = optLong("id")
        val source = optString("source").ifBlank { "tencent" }
        val mid = optString("mid").ifBlank { null }
        val type = if (has("type") && !isNull("type")) optInt("type") else null
        return Song(
            title = optString("title", "未知歌曲"),
            artist = optString("artist", "未知歌手"),
            duration = optString("duration", "网络歌曲"),
            color = 0xFFFFB4A2,
            audioUri = placeholderUri(id.takeIf { it > 0 }, mid, type, quality, source, endpoint)
                ?: optString("audioUrl").takeIf(String::isNotBlank),
            remoteId = id.takeIf { it > 0 },
            coverUri = optString("coverUrl").ifBlank { null },
            lyricUri = lyricUri(id.takeIf { it > 0 }, mid, source, endpoint)
                ?: optString("lyricUrl").ifBlank { null }?.let { if (it.startsWith("http")) it else endpoint + it },
            album = optString("album", "未知专辑"),
            subtitle = optString("subtitle"),
            releaseTime = optString("time"),
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

    private fun JSONObject.toSongInfo(source: String, quality: Int): Song {
        val id = optLong("songId", optLong("id"))
        val mid = optString("mid").ifBlank { null }
        val seconds = optInt("durationSeconds")
        return Song(
            title = optString("title", "未知歌曲"),
            artist = optString("artist", "未知歌手"),
            duration = if (seconds > 0) "%02d:%02d".format(seconds / 60, seconds % 60) else "网络歌曲",
            color = 0xFFFFB4A2,
            audioUri = placeholderUri(id.takeIf { it > 0 }, mid, null, quality, source, endpoint),
            remoteId = id.takeIf { it > 0 },
            coverUri = optString("coverUrl").ifBlank { null },
            lyricUri = lyricUri(id.takeIf { it > 0 }, mid, source, endpoint),
            album = optString("album", "未知专辑"),
            mid = mid,
            vip = optBoolean("vip"),
            favorited = true,
            source = source,
        )
    }

    private fun authorizedJson(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        expectedGeneration: Long? = null,
    ): JSONObject {
        val response = authorizedResponse(path, method, body, expectedGeneration = expectedGeneration)
        ensureSuccess(response)
        val raw = response.body()
        if (raw.isBlank()) return JSONObject()
        val envelope = JSONObject(raw)
        if (envelope.has("code") && envelope.optInt("code") != 0) throw DesktopApiException(envelope.optString("message", "请求失败"))
        return envelope.optJSONObject("data") ?: envelope
    }

    private fun authorizedResponse(
        path: String,
        method: String,
        body: JSONObject? = null,
        accept: String = "application/json",
        expectedGeneration: Long? = null,
    ): HttpResponse<String> {
        var token = session.validToken(expectedGeneration) ?: throw DesktopSessionExpiredException()
        repeat(2) { attempt ->
            ensureGeneration(expectedGeneration)
            val request = request(path, method, token, body, accept)
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            ensureGeneration(expectedGeneration)
            if (response.statusCode() != 401) return response
            if (attempt == 1) throw DesktopSessionExpiredException()
            token = session.renew(token, expectedGeneration) ?: throw DesktopSessionExpiredException()
        }
        throw DesktopSessionExpiredException()
    }

    private fun authorizedStreamResponse(
        path: String,
        method: String,
        accept: String,
        expectedGeneration: Long? = null,
    ): HttpResponse<InputStream> {
        var token = session.validToken(expectedGeneration) ?: throw DesktopSessionExpiredException()
        repeat(2) { attempt ->
            ensureGeneration(expectedGeneration)
            val request = request(path, method, token, null, accept)
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            try {
                ensureGeneration(expectedGeneration)
            } catch (error: Throwable) {
                response.body().close()
                throw error
            }
            if (response.statusCode() != 401) return response
            response.body().close()
            if (attempt == 1) throw DesktopSessionExpiredException()
            token = session.renew(token, expectedGeneration) ?: throw DesktopSessionExpiredException()
        }
        throw DesktopSessionExpiredException()
    }

    private fun ensureGeneration(expectedGeneration: Long?) {
        if (expectedGeneration != null && session.sessionGeneration != expectedGeneration) {
            throw DesktopSessionExpiredException()
        }
    }

    private fun request(path: String, method: String, token: String, body: JSONObject?, accept: String): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(endpoint + path))
            .timeout(Duration.ofSeconds(90))
            .header("Accept", accept)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "TaotaoMusicWindows/1.0")
        return if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString())).build()
    }

    private fun ensureSuccess(response: HttpResponse<String>) {
        if (response.statusCode() !in 200..299) throw DesktopApiException("请求失败：HTTP ${response.statusCode()} ${errorMessage(response.body())}")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val DEFAULT_ENDPOINT = "https://music.xydaigua.cn"
        const val MAX_QUALITY = 18

        fun key(source: String, id: String): String = "${source.lowercase()}:$id"

        fun placeholderUri(remoteId: Long, quality: Int, source: String = "tencent", endpoint: String = DEFAULT_ENDPOINT): String =
            requireNotNull(placeholderUri(remoteId, null, null, quality, source, endpoint))

        fun placeholderUri(song: Song, quality: Int, endpoint: String = DEFAULT_ENDPOINT): String? =
            placeholderUri(song.remoteId, song.mid, song.type, quality, song.source, endpoint)

        fun placeholderUri(
            remoteId: Long?,
            mid: String?,
            type: Int?,
            quality: Int,
            source: String = "tencent",
            endpoint: String = DEFAULT_ENDPOINT,
        ): String? {
            val validId = remoteId?.takeIf { it > 0L }
            val validMid = mid?.trim()?.takeIf(String::isNotBlank)
            if (validId == null && validMid == null) return null
            return buildString {
                append(endpoint)
                append("/api/v1/songs/")
                append(validId ?: 0L)
                append("/play?quality=")
                append(requestQuality(source, quality))
                append("&source=")
                append(URLEncoder.encode(source, Charsets.UTF_8))
                validMid?.let { append("&mid=${URLEncoder.encode(it, Charsets.UTF_8)}") }
                type?.let { append("&type=$it") }
            }
        }

        fun lyricUri(remoteId: Long?, mid: String?, source: String, endpoint: String = DEFAULT_ENDPOINT): String? {
            val validId = remoteId?.takeIf { it > 0L }
            val validMid = mid?.trim()?.takeIf(String::isNotBlank)
            if (validId == null && validMid == null) return null
            return buildString {
                append(endpoint)
                append("/api/v1/songs/")
                append(validId ?: 0L)
                append("/lyrics?source=")
                append(URLEncoder.encode(source, Charsets.UTF_8))
                validMid?.let { append("&mid=${URLEncoder.encode(it, Charsets.UTF_8)}") }
            }
        }

        /** 网易云资料接口把 18 保留给“最高档”；桌面母带枚举值 14 需要请求同一最高档。 */
        fun requestQuality(source: String, quality: Int): Int {
            val normalized = quality.coerceIn(0, MAX_QUALITY)
            return if (source.equals("netease", ignoreCase = true) && normalized == AudioQuality.MASTER.value) {
                18
            } else {
                normalized
            }
        }
    }
}
