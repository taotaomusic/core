package com.taotao.music.update

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 安装包下载器。
 *
 * 支持断点续传：安装包约 14 MB，弱网下从头重下的成功率很低。
 * 已下载的字节数通过 `Range` 请求续上；服务端若返回 200 而不是 206，
 * 说明它不认 Range，此时必须丢弃旧分片从头写，否则会把新数据追加到旧数据后面得到坏包。
 *
 * 下载完成后校验 sha256，不一致就删除重来，绝不交给安装器。
 */
class UpdateDownloader(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY)

    /** 已经下载并校验通过的安装包，用于避免重复下载。 */
    fun completedApk(release: UpdateRelease): File? =
        File(directory, "${release.versionCode}.apk").takeIf { it.isFile && it.length() == release.apkSize }

    /**
     * 下载安装包。[onProgress] 回调百分比，调用方负责切到主线程更新界面。
     * 必须在后台线程调用。
     */
    fun download(release: UpdateRelease, onProgress: (Int) -> Unit): File {
        completedApk(release)?.let { return it }
        directory.mkdirs()
        // 清掉其它版本的残留分片，避免旧版本升级包长期占用存储。
        directory.listFiles()?.filter { !it.name.startsWith("${release.versionCode}.") }?.forEach { it.delete() }

        val target = File(directory, "${release.versionCode}.apk")
        val temporary = File(directory, "${release.versionCode}.apk.part")
        var downloaded = if (temporary.isFile) temporary.length() else 0L
        if (release.apkSize > 0L && downloaded > release.apkSize) {
            temporary.delete()
            downloaded = 0L
        }

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
            if (downloaded > 0L) setRequestProperty("Range", "bytes=$downloaded-")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            runCatching { connection.errorStream?.close() }
            throw IllegalStateException("下载失败：HTTP $code")
        }
        // 请求了续传但服务端返回 200，说明整包重发，旧分片必须丢掉。
        val append = downloaded > 0L && code == HttpURLConnection.HTTP_PARTIAL
        if (!append) downloaded = 0L

        val total = if (release.apkSize > 0L) release.apkSize else downloaded + connection.contentLengthLong.coerceAtLeast(0L)
        var written = downloaded
        var lastPercent = -1
        connection.inputStream.use { input ->
            java.io.FileOutputStream(temporary, append).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (total > 0L) {
                        val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                    }
                }
            }
        }

        if (release.apkSize > 0L && temporary.length() != release.apkSize) {
            temporary.delete()
            throw IllegalStateException("安装包大小不符，请重试")
        }
        val actual = sha256Of(temporary)
        if (release.apkSha256.isNotBlank() && actual != release.apkSha256) {
            temporary.delete()
            throw IllegalStateException("安装包校验失败，请重试")
        }
        target.delete()
        check(temporary.renameTo(target)) { "无法保存安装包" }
        onProgress(100)
        return target
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DIRECTORY = "update"
    }
}
