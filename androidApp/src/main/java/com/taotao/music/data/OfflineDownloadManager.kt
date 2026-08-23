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
     */
    fun download(song: Song, audioUrl: String): Song {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val dir = File(root, id.toString()).apply { mkdirs() }
        val audio = audioUrl.takeIf { it.startsWith("http") }?.let {
            val extension = URL(it).path.substringAfterLast('.', "").lowercase().takeIf { value -> value in AUDIO_EXTENSIONS } ?: "mp3"
            download(it.replaceFirst("http://", "https://"), File(dir, "audio.$extension"))
        }
        // 音频是离线播放的必要条件，失败直接抛出；封面和歌词属于附加内容，失败不影响下载结果。
        val cover = song.coverUri?.takeIf { it.startsWith("http") }
            ?.let { runCatching { download(it, File(dir, "cover")) }.getOrNull() }
        val lyric = song.lyricUri
            ?.takeIf { it.startsWith("http") }
            ?.let { runCatching { download(it, File(dir, "lyric.txt")) }.getOrNull() }
        Properties().apply {
            setProperty("title", song.title)
            setProperty("artist", song.artist)
            setProperty("duration", song.duration)
            setProperty("color", song.color.toString())
            setProperty("remoteId", id.toString())
            setProperty("album", song.album)
            setProperty("subtitle", song.subtitle)
            setProperty("releaseTime", song.releaseTime)
            // mid 与 type 要留着：离线歌重新联网想换音质时还得靠它们解析。
            song.mid?.let { setProperty("mid", it) }
            song.type?.let { setProperty("type", it.toString()) }
        }.also { properties ->
            File(dir, "song.properties").outputStream().use { properties.store(it, "歌曲信息") }
        }
        return song.copy(
            audioUri = audio?.toURI()?.toString() ?: song.audioUri,
            // 封面或歌词下载失败时保留原地址，避免离线歌曲反而丢掉在线资源。
            coverUri = cover?.toURI()?.toString() ?: song.coverUri,
            lyricUri = lyric?.toURI()?.toString() ?: song.lyricUri,
        )
    }

    fun listDownloaded(): List<Song> = root.listFiles()
        ?.filter { dir -> File(dir, "song.properties").isFile && dir.listFiles()?.any { it.name.startsWith("audio") && it.isFile && it.length() >= 4_096L } == true }
        ?.mapNotNull { dir ->
            runCatching {
                val properties = Properties().apply { load(File(dir, "song.properties").inputStream()) }
                val id = properties.getProperty("remoteId").toLong()
                Song(
                    title = properties.getProperty("title", "未知歌曲"),
                    artist = properties.getProperty("artist", "未知歌手"),
                    duration = properties.getProperty("duration", "网络歌曲"),
                    color = properties.getProperty("color", "0xFFFFB4A2").toLong(),
                    audioUri = dir.listFiles()!!
                        .filter { it.name.startsWith("audio") && it.isFile && it.length() >= 4_096L }
                        .sortedBy { it.name == "audio" }
                        .first()
                        .toURI()
                        .toString(),
                    remoteId = id,
                    coverUri = File(dir, "cover").takeIf(File::isFile)?.toURI()?.toString(),
                    lyricUri = File(dir, "lyric.txt").takeIf(File::isFile)?.toURI()?.toString(),
                    album = properties.getProperty("album", ""),
                    subtitle = properties.getProperty("subtitle", ""),
                    releaseTime = properties.getProperty("releaseTime", ""),
                    mid = properties.getProperty("mid"),
                    type = properties.getProperty("type")?.toIntOrNull(),
                )
            }.getOrNull()
        }
        .orEmpty()

    /**
     * 下载单个文件。自家地址附带访问令牌，令牌被拒绝时续期后重试一次，
     * 避免下载中途因为访问令牌过期而失败。
     */
    private fun download(url: String, target: File): File {
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
            val temp = File(target.parentFile, "${target.name}.part")
            try {
                connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
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
