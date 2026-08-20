package com.taotao.music.data

import android.content.Context
import com.taotao.music.model.Song
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.util.Properties

/** 将网络歌曲及其封面、歌词保存到应用私有目录，避免申请外部存储权限。 */
class OfflineDownloadManager(context: Context) {
    private val root = File(context.filesDir, "offline_music").apply { mkdirs() }

    fun download(song: Song): Song {
        val id = requireNotNull(song.remoteId) { "网络歌曲缺少歌曲 ID" }
        val dir = File(root, id.toString()).apply { mkdirs() }
        val audio = song.audioUri?.takeIf { it.startsWith("http") }?.let { download(it.replaceFirst("http://", "https://"), File(dir, "audio")) }
        val cover = song.coverUri?.takeIf { it.startsWith("http") }?.let { download(it, File(dir, "cover")) }
        val lyric = song.lyricUri?.takeIf { it.startsWith("http") }?.let { download(it, File(dir, "lyric.txt")) }
        Properties().apply {
            setProperty("title", song.title)
            setProperty("artist", song.artist)
            setProperty("duration", song.duration)
            setProperty("color", song.color.toString())
            setProperty("remoteId", id.toString())
            setProperty("album", song.album)
            setProperty("subtitle", song.subtitle)
            setProperty("releaseTime", song.releaseTime)
        }.store(File(dir, "song.properties").outputStream(), "歌曲信息")
        return song.copy(audioUri = audio?.toURI()?.toString() ?: song.audioUri, coverUri = cover?.toURI()?.toString(), lyricUri = lyric?.toURI()?.toString())
    }

    fun listDownloaded(): List<Song> = root.listFiles()
        ?.filter { File(it, "song.properties").isFile && File(it, "audio").isFile }
        ?.mapNotNull { dir ->
            runCatching {
                val properties = Properties().apply { load(File(dir, "song.properties").inputStream()) }
                val id = properties.getProperty("remoteId").toLong()
                Song(
                    title = properties.getProperty("title", "未知歌曲"),
                    artist = properties.getProperty("artist", "未知歌手"),
                    duration = properties.getProperty("duration", "网络歌曲"),
                    color = properties.getProperty("color", "0xFFFFB4A2").toLong(),
                    audioUri = File(dir, "audio").toURI().toString(),
                    remoteId = id,
                    coverUri = File(dir, "cover").takeIf(File::isFile)?.toURI()?.toString(),
                    lyricUri = File(dir, "lyric.txt").takeIf(File::isFile)?.toURI()?.toString(),
                    album = properties.getProperty("album", ""),
                    subtitle = properties.getProperty("subtitle", ""),
                    releaseTime = properties.getProperty("releaseTime", ""),
                )
            }.getOrNull()
        }
        .orEmpty()

    private fun download(url: String, target: File): File {
        if (target.exists() && target.length() > 0L) return target
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000; requestMethod = "GET" }
        check(connection.responseCode in 200..299) { "下载失败：HTTP ${connection.responseCode}" }
        val temp = File(target.parentFile, "${target.name}.part")
        connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
        check(temp.length() > 0L) { "下载内容为空" }
        check(temp.renameTo(target)) { "无法保存下载文件" }
        return target
    }
}
