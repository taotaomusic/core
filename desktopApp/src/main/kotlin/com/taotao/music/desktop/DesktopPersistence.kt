package com.taotao.music.desktop

import com.taotao.music.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Windows 端所有本地数据的根目录，默认位于 %APPDATA%\TaotaoMusic。 */
class DesktopStorage(
    rootOverride: File? = null,
) {
    val root: File = (rootOverride ?: File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "TaotaoMusic"))
        .apply { mkdirs() }
    private val downloadsRoot = File(root, "downloads").apply { mkdirs() }
    private val settingsFile = File(root, "settings.properties")
    private val queueFile = File(root, "queue.json")
    private val favoritesFile = File(root, "favorites.json")
    private val historyFile = File(root, "history.json")
    private val searchHistoryFile = File(root, "search-history.json")
    private val accountDataMarkerFile = File(root, "account-data.id")

    /**
     * 收藏与最近播放属于账号数据，账号变化时先清理旧的全局缓存，避免换号串数据。
     * 队列、下载和搜索历史仍是设备级数据，不在这里处理。
     */
    @Synchronized
    fun prepareAccount(accountId: Long?) {
        val normalized = accountId?.takeIf { it > 0L }?.toString().orEmpty()
        val markerExists = accountDataMarkerFile.isFile
        val previous = accountDataMarkerFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
        // 没有水位的旧版本数据只能在首次登录时归给当前账号；未登录启动则直接清掉，
        // 防止崩溃后登录页仍携带上一账号的全局缓存。
        if (!markerExists) {
            if (normalized.isBlank()) {
                favoritesFile.delete()
                historyFile.delete()
            } else {
                atomicWrite(accountDataMarkerFile, normalized)
            }
            return
        }
        if (previous != normalized) {
            favoritesFile.delete()
            historyFile.delete()
            if (normalized.isBlank()) accountDataMarkerFile.delete()
            else atomicWrite(accountDataMarkerFile, normalized)
        }
    }

    data class QueueSnapshot(val queue: List<Song>, val index: Int, val positionMs: Int)
    data class HistoryEntry(
        val song: Song,
        val playedAt: Long,
        val firstPlayedAt: Long = 0L,
        val playCount: Int = 0,
        val completedCount: Int = 0,
        val totalListenedMs: Long = 0L,
        /** 服务端最近播放清空代际；旧版本文件缺失时按 0 兼容。 */
        val historyRevision: Long = 0L,
    )

    @Synchronized
    fun loadQueue(): QueueSnapshot? = runCatching {
        if (!queueFile.isFile) return null
        val json = JSONObject(queueFile.readText())
        val array = json.optJSONArray("queue") ?: return null
        val songs = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toSong() }
        if (songs.isEmpty()) return null
        QueueSnapshot(songs, json.optInt("index").coerceIn(songs.indices), json.optInt("positionMs").coerceAtLeast(0))
    }.getOrNull()

    @Synchronized
    fun saveQueue(queue: List<Song>, index: Int, positionMs: Int) {
        if (queue.isEmpty()) return
        val json = JSONObject()
            .put("queue", JSONArray().also { array -> queue.forEach { array.put(it.toJson()) } })
            .put("index", index.coerceIn(queue.indices))
            .put("positionMs", positionMs.coerceAtLeast(0))
        atomicWrite(queueFile, json.toString())
    }

    @Synchronized
    fun clearQueue() = queueFile.delete()

    @Synchronized
    fun loadFavorites(): Map<String, Song> = runCatching {
        if (!favoritesFile.isFile) return emptyMap()
        val array = JSONArray(favoritesFile.readText())
        buildMap {
            for (index in 0 until array.length()) {
                val song = array.optJSONObject(index)?.toSong()?.copy(favorited = true) ?: continue
                put(songKey(song), song)
            }
        }
    }.getOrDefault(emptyMap())

    @Synchronized
    fun saveFavorites(songs: Collection<Song>) = atomicWrite(
        favoritesFile,
        JSONArray().also { array -> songs.forEach { array.put(it.copy(favorited = true).toJson()) } }.toString(),
    )

    @Synchronized
    fun loadHistory(): List<HistoryEntry> = runCatching {
        if (!historyFile.isFile) return emptyList()
        val array = JSONArray(historyFile.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val song = item.optJSONObject("song")?.toSong() ?: return@mapNotNull null
            HistoryEntry(
                song = song,
                playedAt = item.optLong("playedAt"),
                firstPlayedAt = item.optLong("firstPlayedAt"),
                playCount = item.optInt("playCount"),
                completedCount = item.optInt("completedCount"),
                totalListenedMs = item.optLong("totalListenedMs"),
                historyRevision = item.optLong("historyRevision").coerceAtLeast(0L),
            )
        }.sortedByDescending { it.playedAt }.take(MAX_HISTORY)
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveHistory(entries: Collection<HistoryEntry>) = atomicWrite(
        historyFile,
        JSONArray().also { array ->
            entries.sortedByDescending { it.playedAt }.take(MAX_HISTORY).forEach { entry ->
                array.put(JSONObject()
                    .put("song", entry.song.toJson())
                    .put("playedAt", entry.playedAt)
                    .put("firstPlayedAt", entry.firstPlayedAt)
                    .put("playCount", entry.playCount)
                    .put("completedCount", entry.completedCount)
                    .put("totalListenedMs", entry.totalListenedMs)
                    .put("historyRevision", entry.historyRevision.coerceAtLeast(0L)))
            }
        }.toString(),
    )

    @Synchronized
    fun loadSearchHistory(): List<String> = runCatching {
        if (!searchHistoryFile.isFile) return emptyList()
        val array = JSONArray(searchHistoryFile.readText())
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.take(MAX_SEARCH_HISTORY)
    }.getOrDefault(emptyList())

    @Synchronized
    fun saveSearchHistory(values: Collection<String>) = atomicWrite(
        searchHistoryFile,
        JSONArray(values.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_SEARCH_HISTORY)).toString(),
    )

    @Synchronized
    fun getSetting(key: String, default: String): String = Properties().run {
        if (settingsFile.isFile) settingsFile.inputStream().use(::load)
        getProperty(key, default)
    }

    @Synchronized
    fun setSetting(key: String, value: String) {
        val properties = Properties().apply { if (settingsFile.isFile) settingsFile.inputStream().use(::load) }
        properties.setProperty(key, value)
        val buffer = java.io.StringWriter()
        properties.store(buffer, "桃桃音乐 Windows 设置")
        atomicWrite(settingsFile, buffer.toString())
    }

    fun downloads(): DesktopDownloadStore = DesktopDownloadStore(downloadsRoot)

    companion object {
        const val MAX_HISTORY = 500
        const val MAX_SEARCH_HISTORY = 20

        /** 数字 ID 缺失时用上游 mid 做稳定键，不能让所有 id=0 歌曲碰撞成同一首。 */
        fun songKey(song: Song): String = DesktopMusicApi.key(
            song.source,
            song.remoteId?.takeIf { it > 0L }?.toString()
                ?: song.mid?.trim()?.takeIf { it.isNotBlank() }
                ?: song.audioUri.orEmpty(),
        )

        private fun Song.toJson(): JSONObject = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("duration", duration)
            put("color", color)
            put("audioUri", audioUri)
            put("remoteId", remoteId)
            put("coverUri", coverUri)
            put("lyricUri", lyricUri)
            put("lyricWordsUri", lyricWordsUri)
            put("album", album)
            put("subtitle", subtitle)
            put("releaseTime", releaseTime)
            put("mid", mid)
            put("type", type)
            put("vip", vip)
            put("favorited", favorited)
            put("localQuality", localQuality)
            put("source", source)
        }

        private fun JSONObject.toSong(): Song = Song(
            title = optString("title", "未知歌曲"),
            artist = optString("artist", "未知歌手"),
            duration = optString("duration", "网络歌曲"),
            color = optLong("color", 0xFFFFB4A2),
            audioUri = nullable("audioUri"),
            remoteId = optLong("remoteId").takeIf { it > 0L },
            coverUri = nullable("coverUri"),
            lyricUri = nullable("lyricUri"),
            lyricWordsUri = nullable("lyricWordsUri"),
            album = optString("album"),
            subtitle = optString("subtitle"),
            releaseTime = optString("releaseTime"),
            mid = nullable("mid"),
            type = if (has("type") && !isNull("type")) optInt("type") else null,
            vip = optBoolean("vip"),
            favorited = optBoolean("favorited"),
            localQuality = if (has("localQuality") && !isNull("localQuality")) optInt("localQuality") else null,
            source = optString("source").ifBlank { "tencent" },
        )

        private fun JSONObject.nullable(name: String): String? = optString(name).takeIf { it.isNotBlank() && it != "null" }

        private fun atomicWrite(target: File, text: String) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
            temp.writeText(text)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** 下载内容与歌曲元数据分目录保存，音频文件完成后再原子改名。 */
class DesktopDownloadStore(private val root: File) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    // 同一歌曲的旧下载可能仍在阻塞 HTTP send；文件锁保证它不会与新账号/新音质任务
    // 同时改名或清理同一个 audio.*，避免旧任务覆盖新任务的完整文件。
    private val downloadLocks = ConcurrentHashMap<String, Any>()

    fun list(): List<Song> = root.listFiles().orEmpty()
        .mapNotNull(::readSong)
        .sortedBy { it.title }

    fun find(song: Song): Song? = existingDirectory(song)?.let(::readSong)

    fun delete(song: Song): Boolean {
        val target = existingDirectory(song) ?: return false
        val directory = target.name
        return synchronized(downloadLocks.computeIfAbsent(directory) { Any() }) {
            // Windows 上解码线程刚关闭文件句柄时，第一次删除可能短暂失败；删除在 IO
            // 协程中执行，因此允许几次很短的重试，不把瞬时占用暴露成假失败。
            repeat(5) { attempt ->
                if (!target.exists()) return@synchronized true
                if (target.deleteRecursively()) return@synchronized true
                if (attempt < 4) Thread.sleep(50L * (attempt + 1))
            }
            false
        }
    }

    fun download(
        song: Song,
        audioUrl: String,
        quality: Int,
        lyric: DesktopMusicApi.RichLyric?,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true },
    ): Song = synchronized(downloadLocks.computeIfAbsent(directoryName(song)) { Any() }) {
        downloadLocked(song, audioUrl, quality, lyric, onProgress, shouldContinue)
    }

    private fun downloadLocked(
        song: Song,
        audioUrl: String,
        quality: Int,
        lyric: DesktopMusicApi.RichLyric?,
        onProgress: (Long, Long) -> Unit,
        shouldContinue: () -> Boolean,
    ): Song {
        require(song.hasRemoteIdentity()) { "网络歌曲缺少歌曲 ID 或 mid" }
        ensureDownloadActive(shouldContinue)
        val dir = File(root, directoryName(song)).apply { mkdirs() }
        val extension = extensionOf(audioUrl)
        val target = File(dir, "audio.$extension")
        downloadFile(audioUrl, target, onProgress, shouldContinue)
        ensureDownloadActive(shouldContinue)
        song.coverUri?.takeIf { it.startsWith("http") }?.let { url ->
            runCatching { downloadFile(url, File(dir, "cover"), shouldContinue = shouldContinue) }
                .onFailure { error -> if (!shouldContinue()) throw error }
        }
        ensureDownloadActive(shouldContinue)
        lyric?.lrc?.takeIf(String::isNotBlank)?.let { atomicWrite(File(dir, "lyric.lrc"), it) }
        lyric?.yrc?.takeIf(String::isNotBlank)?.let { atomicWrite(File(dir, "lyric.yrc"), it) }
        ensureDownloadActive(shouldContinue)
        val saved = song.copy(
            audioUri = target.toURI().toString(),
            coverUri = File(dir, "cover").takeIf(File::isFile)?.toURI()?.toString() ?: song.coverUri,
            lyricUri = File(dir, "lyric.lrc").takeIf(File::isFile)?.toURI()?.toString() ?: song.lyricUri,
            lyricWordsUri = File(dir, "lyric.yrc").takeIf(File::isFile)?.toURI()?.toString(),
            localQuality = quality,
        )
        // 必须先提交 song.json，再清理旧扩展名。若封面、歌词或元数据写入期间取消，
        // 旧版本仍可按旧 URI 播放，不会留下指向已删除文件的半成品目录。
        atomicWrite(File(dir, "song.json"), saved.toJson().toString())
        dir.listFiles().orEmpty().filter { it.name.startsWith("audio.") && it != target }.forEach(File::delete)
        return saved
    }

    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true },
    ) {
        ensureDownloadActive(shouldContinue)
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).header("User-Agent", "TaotaoMusicWindows/1.0").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw DesktopApiException("下载失败：HTTP ${response.statusCode()}")
        }
        val contentType = response.headers().firstValue("Content-Type").orElse("").lowercase()
        if (contentType.contains("nac")) {
            response.body().close()
            throw DesktopApiException("Windows 暂不支持 NAC 私有音频格式，请选择其他音质")
        }
        if (!shouldContinue()) {
            response.body().close()
            throw CancellationException("下载任务已取消")
        }
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
        val total = response.headers().firstValueAsLong("Content-Length").orElse(0L)
        try {
            response.body().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        ensureDownloadActive(shouldContinue)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (done - lastReport >= 256 * 1024) { lastReport = done; onProgress(done, total) }
                    }
                    onProgress(done, total)
                }
            }
            ensureDownloadActive(shouldContinue)
            if (temp.length() <= 0L) error("下载内容为空")
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun ensureDownloadActive(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw CancellationException("下载任务已取消")
    }

    /** 读取并校正本地元数据；旧版本或中断下载留下的 URI 失配时优先使用实际音频文件。 */
    private fun readSong(dir: File): Song? {
        if (!dir.isDirectory) return null
        val audio = audioFile(dir) ?: return null
        val song = runCatching { JSONObject(File(dir, "song.json").readText()).toSong() }.getOrNull() ?: return null
        val metadataAudio = song.audioUri?.let(::localFileOf)?.takeIf(::isUsableAudio)
        return song.copy(audioUri = (metadataAudio ?: audio).toURI().toString())
    }

    /** 补全资料可能把 mid 解析成正 ID，目录名改变时仍应命中原来的下载。 */
    private fun existingDirectory(song: Song): File? {
        val direct = File(root, directoryName(song))
        if (readSong(direct)?.sameRemoteSong(song) == true) return direct
        return root.listFiles().orEmpty().firstOrNull { dir -> readSong(dir)?.sameRemoteSong(song) == true }
    }

    private fun audioFile(dir: File): File? = dir.listFiles().orEmpty().firstOrNull(::isUsableAudio)

    private fun isUsableAudio(file: File): Boolean = file.isFile && file.length() > 4_096L &&
        DesktopPlayer.supports(file.toURI().toString())

    private fun localFileOf(uri: String): File? = runCatching {
        if (uri.startsWith("file:", ignoreCase = true)) File(URI(uri)) else File(uri)
    }.getOrNull()
    private fun directoryName(song: Song): String = "${song.source}-${song.remoteId?.takeIf { it > 0L } ?: song.mid?.takeIf { it.isNotBlank() } ?: song.title.hashCode()}"
        .replace(Regex("[^a-zA-Z0-9._-]"), "_")
    private fun extensionOf(url: String): String = URI.create(url).path.substringAfterLast('.', "mp3").lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JSONObject.toSong(): Song = DesktopStorage.run { /* keep codec private to storage */
        Song(
            title = optString("title", "未知歌曲"), artist = optString("artist", "未知歌手"), duration = optString("duration", "网络歌曲"),
            color = optLong("color", 0xFFFFB4A2), audioUri = optString("audioUri").takeIf(String::isNotBlank),
            remoteId = optLong("remoteId").takeIf { it > 0 }, coverUri = optString("coverUri").takeIf(String::isNotBlank),
            lyricUri = optString("lyricUri").takeIf(String::isNotBlank), lyricWordsUri = optString("lyricWordsUri").takeIf(String::isNotBlank),
            album = optString("album"), subtitle = optString("subtitle"), releaseTime = optString("releaseTime"), mid = optString("mid").takeIf(String::isNotBlank),
            type = if (has("type") && !isNull("type")) optInt("type") else null, vip = optBoolean("vip"), favorited = optBoolean("favorited"),
            localQuality = if (has("localQuality") && !isNull("localQuality")) optInt("localQuality") else null, source = optString("source").ifBlank { "tencent" },
        )
    }

    private fun Song.toJson(): JSONObject = JSONObject().apply {
        put("title", title); put("artist", artist); put("duration", duration); put("color", color); put("audioUri", audioUri); put("remoteId", remoteId)
        put("coverUri", coverUri); put("lyricUri", lyricUri); put("lyricWordsUri", lyricWordsUri); put("album", album); put("subtitle", subtitle); put("releaseTime", releaseTime)
        put("mid", mid); put("type", type); put("vip", vip); put("favorited", favorited); put("localQuality", localQuality); put("source", source)
    }

    companion object { private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "nac") }
}
