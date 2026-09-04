package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.Song
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.util.Properties

/** 将网络歌曲及其封面、歌词保存到应用私有目录，避免申请外部存储权限。 */
class OfflineDownloadManager(context: Context, private val tokenProvider: TokenProvider) {
    private val root = File(context.filesDir, "offline_music").apply { mkdirs() }

    /**
     * 下载一首歌。
     *
     * [audioUrl] 是调用方**先解析好**的上游直链 —— 队列里存的是不带扩展名的占位地址，
     * 直接拿它下载会一律落成 `.mp3`，而无损其实是 flac，扩展名错了播放器会认错容器。
     * 直链里带着真实文件名，扩展名从它推断。
     *
     * [lrc] / [yrc] 同样由调用方先取好。**两个都要存**：早先这里只下载纯文本歌词接口，
     * 那个接口只返回行级 LRC，于是离线播放永远没有逐字高亮，看起来像"歌词没下全"。
     * 云端播放走的是 `?format=json`，两条时间轴都有。
     *
     * [onProgress] 报告音频文件的下载进度，`total` 为 0 表示上游没给 Content-Length。
     * 只在音频这一步报告：封面和歌词加起来通常不到一百 KB，报了反而让进度条乱跳。
     */
    fun download(
        song: Song,
        audioUrl: String,
        quality: Int,
        lrc: String? = null,
        yrc: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Song {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val dir = File(root, id.toString()).apply { mkdirs() }
        val audio = audioUrl.takeIf { it.startsWith("http") }?.let {
            val extension = URL(it).path.substringAfterLast('.', "").lowercase().takeIf { value -> value in AUDIO_EXTENSIONS } ?: "mp3"
            val target = File(dir, "audio.$extension")
            // 换音质重新下载时先清掉旧的音频文件：一首歌只留一个。
            // 否则目录里会同时存在 audio.mp3 与 audio.flac，读取时只能任选一个，
            // 于是「播的是哪一档」和 properties 里记的音质对不上。
            audioFilesIn(dir).filter { file -> file != target }.forEach(File::delete)
            download(it.replaceFirst("http://", "https://"), target, onProgress)
        }
        // 音频是离线播放的必要条件，失败直接抛出；封面和歌词属于附加内容，失败不影响下载结果。
        val cover = song.coverUri?.takeIf { it.startsWith("http") }
            ?.let { runCatching { download(it, File(dir, "cover")) }.getOrNull() }
        val lyric = lrc?.takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { File(dir, "lyric.txt").apply { writeText(text) } }.getOrNull() }
        val lyricWords = yrc?.takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { File(dir, "lyric.yrc").apply { writeText(text) } }.getOrNull() }
        Properties().apply {
            setProperty("title", song.title)
            setProperty("artist", song.artist)
            setProperty("duration", song.duration)
            setProperty("color", song.color.toString())
            setProperty("remoteId", id.toString())
            setProperty("album", song.album)
            setProperty("subtitle", song.subtitle)
            setProperty("releaseTime", song.releaseTime)
            // 记下实际下载的音质，否则离线播放时无从得知手里这份是哪一档。
            setProperty("quality", quality.toString())
            if (song.vip) setProperty("vip", "true")
            // mid 与 type 要留着：离线歌重新联网想换音质时还得靠它们解析。
            song.mid?.let { setProperty("mid", it) }
            song.type?.let { setProperty("type", it.toString()) }
            setProperty("source", song.source.ifBlank { "tencent" })
        }.also { properties ->
            File(dir, "song.properties").outputStream().use { properties.store(it, "歌曲信息") }
        }
        return song.copy(
            audioUri = audio?.toURI()?.toString() ?: song.audioUri,
            // 封面或歌词下载失败时保留原地址，避免离线歌曲反而丢掉在线资源。
            coverUri = cover?.toURI()?.toString() ?: song.coverUri,
            lyricUri = lyric?.toURI()?.toString() ?: song.lyricUri,
            lyricWordsUri = lyricWords?.toURI()?.toString(),
            localQuality = quality,
        )
    }

    fun listDownloaded(): List<Song> = root.listFiles()
        ?.filter { dir -> File(dir, "song.properties").isFile && audioFilesIn(dir).isNotEmpty() }
        ?.mapNotNull { dir -> runCatching { songIn(dir) }.getOrNull() }
        .orEmpty()

    /**
     * 已下载的这首歌，没有则返回 null。
     *
     * 搜索结果里的歌与本地已下载的是同一个 `remoteId`，播放前必须先查这里 ——
     * 否则明明下载过却还是从云端拉流，白费流量。
     */
    fun findDownloaded(remoteId: Long?): Song? {
        val dir = File(root, (remoteId ?: return null).toString())
        if (!File(dir, "song.properties").isFile || audioFilesIn(dir).isEmpty()) return null
        return runCatching { songIn(dir) }.getOrNull()
    }

    private fun songIn(dir: File): Song {
        val properties = Properties().apply { load(File(dir, "song.properties").inputStream()) }
        return Song(
            title = properties.getProperty("title", "未知歌曲"),
            artist = properties.getProperty("artist", "未知歌手"),
            duration = properties.getProperty("duration", "网络歌曲"),
            color = properties.getProperty("color", "0xFFFFB4A2").toLong(),
            // 一首歌只留一个音频文件，所以这里不存在"挑哪个"的问题。
            audioUri = audioFilesIn(dir).first().toURI().toString(),
            remoteId = properties.getProperty("remoteId").toLong(),
            coverUri = File(dir, "cover").takeIf(File::isFile)?.toURI()?.toString(),
            lyricUri = File(dir, "lyric.txt").takeIf(File::isFile)?.toURI()?.toString(),
            // 早于本版本下载的歌没有 .yrc，逐字高亮会退化成整行高亮而不是报错。
            lyricWordsUri = File(dir, "lyric.yrc").takeIf(File::isFile)?.toURI()?.toString(),
            album = properties.getProperty("album", ""),
            subtitle = properties.getProperty("subtitle", ""),
            releaseTime = properties.getProperty("releaseTime", ""),
            mid = properties.getProperty("mid"),
            type = properties.getProperty("type")?.toIntOrNull(),
            vip = properties.getProperty("vip") == "true",
            // 旧版本下载的歌没有记音质，显示成未知而不是猜一个。
            localQuality = properties.getProperty("quality")?.toIntOrNull(),
            source = properties.getProperty("source", "tencent"),
        )
    }

    /** 目录里可用的音频文件。太小的当作写坏的残留忽略。 */
    private fun audioFilesIn(dir: File): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("audio") && !it.name.endsWith(".part") && it.length() >= 4_096L }
        .orEmpty()

    /**
     * 下载单个文件。自家地址附带访问令牌，令牌被拒绝时续期后重试一次，
     * 避免下载中途因为访问令牌过期而失败。
     */
    private fun download(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        if (target.exists() && target.length() > 0L) return target
        val ownEndpoint = TencentMusicApi.isOwnEndpoint(url)
        var token = if (ownEndpoint) tokenProvider.validToken() ?: throw SessionExpiredException() else null
        repeat(MAX_ATTEMPTS) { attempt ->
            val connection = open(url, token)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED && ownEndpoint && attempt < MAX_ATTEMPTS - 1) {
                runCatching { connection.errorStream?.close() }
                token = tokenProvider.renewToken(token) ?: throw SessionExpiredException()
                return@repeat
            }
            check(code in 200..299) { "下载失败：HTTP $code" }
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            val temp = File(target.parentFile, "${target.name}.part")
            try {
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            // 每 256KB 报一次就够了：通知栏刷得太勤会被系统限流，还白耗电。
                            if (downloaded - lastReported >= 256 * 1024) {
                                lastReported = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                        onProgress(downloaded, total)
                    }
                }
                check(temp.length() > 0L) { "下载内容为空" }
                check(temp.renameTo(target)) { "无法保存下载文件" }
            } catch (error: Throwable) {
                temp.delete()
                throw error
            }
            return target
        }
        throw SessionExpiredException()
    }

    /** 删除一首已下载的歌，连同封面和歌词。 */
    fun delete(song: Song): Boolean {
        val id = song.remoteId ?: return false
        val dir = File(root, id.toString())
        if (!dir.isDirectory) return false
        return dir.deleteRecursively()
    }

    private fun open(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private companion object {
        const val MAX_ATTEMPTS = 2

        /** 上游会给出这些容器；无损是 flac，扩展名认错会让播放器解析失败。 */
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "nac")
    }
}
