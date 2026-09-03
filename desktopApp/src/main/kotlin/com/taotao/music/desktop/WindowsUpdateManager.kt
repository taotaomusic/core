package com.taotao.music.desktop

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Properties
import java.util.UUID

/** Windows 清单中的一个模块，以及可选的旧版本二进制差分。 */
data class WindowsUpdateFile(
    val path: String,
    val category: String,
    val size: Long,
    val sha256: String,
    val url: String,
    val patch: WindowsUpdatePatch? = null,
)

data class WindowsUpdatePatch(
    val algorithm: String,
    val fromSha256: String,
    val size: Long,
    val sha256: String,
    val url: String,
)

data class WindowsUpdateRelease(
    val forced: Boolean,
    val versionCode: Int,
    val versionName: String,
    val entrypoint: String,
    val releaseNote: String,
    val totalSize: Long,
    val files: List<WindowsUpdateFile>,
)

sealed interface WindowsUpdateResult {
    data object Current : WindowsUpdateResult
    data class Available(val release: WindowsUpdateRelease) : WindowsUpdateResult
    data class Staged(val release: WindowsUpdateRelease, val downloadedBytes: Long) : WindowsUpdateResult
    data class Unsupported(val reason: String) : WindowsUpdateResult
}

/**
 * Windows 模块化更新管理器。
 *
 * 应用进程只做检查和下载；文件替换由 updater 在本进程退出后完成，启动失败则由
 * launcher 在下次启动前恢复 backup。这样不会在 JVM 仍持有 JAR 时修改自身文件。
 */
class WindowsUpdateManager(
    private val endpoint: String = DesktopMusicApi.DEFAULT_ENDPOINT,
    private val versionCode: Int = DesktopBuildInfo.versionCode,
    private val architecture: String = "windows-x64",
    private val installRoot: Path? = installationRoot(),
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun check(deviceId: String, channel: String = "release"): WindowsUpdateResult {
        val query = listOf(
            "channel" to channel,
            "architecture" to architecture,
            "versionCode" to versionCode.toString(),
            "deviceId" to deviceId,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val request = HttpRequest.newBuilder(URI.create("$endpoint/api/v1/desktop/bootstrap?$query"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", "TaotaoMusicWindows/${DesktopBuildInfo.versionName}")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw DesktopApiException("检查 Windows 更新失败：HTTP ${response.statusCode()}")
        val envelope = JSONObject(response.body())
        val data = envelope.optJSONObject("data") ?: envelope
        val update = data.optJSONObject("update") ?: return WindowsUpdateResult.Current
        if (!update.optBoolean("available")) return WindowsUpdateResult.Current
        val release = parseRelease(update)
        return WindowsUpdateResult.Available(release)
    }

    fun stage(release: WindowsUpdateRelease, onProgress: (Long, Long) -> Unit = { _, _ -> }): WindowsUpdateResult {
        val root = installRoot ?: return WindowsUpdateResult.Unsupported("当前不是由稳定启动器启动，不能原地更新")
        val current = root.resolve("current")
        if (!Files.isDirectory(current)) return WindowsUpdateResult.Unsupported("安装目录缺少 current")
        val staging = root.resolve("staging-${release.versionCode}")
        deleteRecursively(staging)
        Files.createDirectories(staging)
        val plan = Properties().apply {
            setProperty("versionCode", release.versionCode.toString())
            setProperty("versionName", release.versionName)
            setProperty("entrypoint", release.entrypoint)
            setProperty("staging", root.relativize(staging).toString())
            setProperty("file.count", release.files.size.toString())
        }
        var downloaded = 0L
        release.files.forEachIndexed { index, file ->
            val currentFile = checkedChild(current, file.path)
            val currentHash = currentFile.takeIf(Files::isRegularFile)?.let(::sha256)
            val prefix = "file.$index."
            plan.setProperty(prefix + "path", file.path)
            plan.setProperty(prefix + "sha256", file.sha256)
            when {
                currentHash == file.sha256 -> plan.setProperty(prefix + "mode", "reuse")
                file.patch != null && currentHash == file.patch.fromSha256 && file.patch.algorithm in SUPPORTED_PATCHES -> {
                    val source = "downloads/$index.patch"
                    val target = checkedChild(staging, source)
                    download(file.patch.url, target, file.patch.size, file.patch.sha256) { done ->
                        onProgress(downloaded + done, release.totalSize)
                    }
                    downloaded += file.patch.size
                    plan.setProperty(prefix + "mode", "patch")
                    plan.setProperty(prefix + "algorithm", file.patch.algorithm)
                    plan.setProperty(prefix + "source", source)
                }
                else -> {
                    val source = "downloads/$index.bin"
                    val target = checkedChild(staging, source)
                    download(file.url, target, file.size, file.sha256) { done ->
                        onProgress(downloaded + done, release.totalSize)
                    }
                    downloaded += file.size
                    plan.setProperty(prefix + "mode", "full")
                    plan.setProperty(prefix + "source", source)
                }
            }
        }
        val planFile = root.resolve("update-${release.versionCode}.properties")
        atomicStore(planFile, plan)
        return WindowsUpdateResult.Staged(release, downloaded)
    }

    /** 启动独立更新器；成功后调用方必须立即结束应用进程。 */
    fun launchUpdater(release: WindowsUpdateRelease) {
        val root = requireNotNull(installRoot) { "当前不是稳定安装布局" }
        val plan = root.resolve("update-${release.versionCode}.properties")
        require(Files.isRegularFile(plan)) { "更新计划不存在：$plan" }
        val updaterExe = root.resolve("updater.exe")
        val command = if (Files.isRegularFile(updaterExe)) {
            listOf(updaterExe.toString(), root.toString(), plan.toString(), ProcessHandle.current().pid().toString())
        } else {
            val updaterJar = root.resolve("taotao-updater.jar")
            require(Files.isRegularFile(updaterJar)) { "安装目录缺少 updater.exe/taotao-updater.jar" }
            val java = root.resolve("runtime/bin/java.exe").takeIf(Files::isRegularFile)
                ?: Path.of(System.getProperty("java.home"), "bin", "java.exe")
            listOf(
                java.toString(), "-jar", updaterJar.toString(), root.toString(), plan.toString(),
                ProcessHandle.current().pid().toString(),
            )
        }
        ProcessBuilder(command).directory(root.toFile()).start()
    }

    private fun download(url: String, target: Path, expectedSize: Long, expectedSha256: String, progress: (Long) -> Unit) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".part")
        val offset = if (Files.isRegularFile(temporary)) {
            Files.size(temporary).takeIf { it in 1 until expectedSize } ?: 0L
        } else {
            0L
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(15))
            .header("User-Agent", "TaotaoMusicWindows/${DesktopBuildInfo.versionName}")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val append = offset > 0 && response.statusCode() == HttpURLConnection.HTTP_PARTIAL
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw DesktopApiException("更新文件下载失败：HTTP ${response.statusCode()}")
        }
        val start = if (append) offset else 0L
        Files.newOutputStream(
            temporary,
            java.nio.file.StandardOpenOption.WRITE,
            if (append) java.nio.file.StandardOpenOption.APPEND else java.nio.file.StandardOpenOption.CREATE,
            if (!append) java.nio.file.StandardOpenOption.TRUNCATE_EXISTING else java.nio.file.StandardOpenOption.CREATE,
        ).use { output ->
            response.body().use { input ->
                val buffer = ByteArray(64 * 1024)
                var done = start
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    done += count
                    progress(done)
                }
            }
        }
        if (Files.size(temporary) != expectedSize || sha256(temporary) != expectedSha256) {
            Files.deleteIfExists(temporary)
            throw DesktopApiException("更新文件大小或 sha256 校验失败")
        }
        move(temporary, target)
    }

    private fun parseRelease(update: JSONObject): WindowsUpdateRelease {
        val array = update.getJSONArray("files")
        val files = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val patch = item.optJSONObject("patch")?.let { value ->
                WindowsUpdatePatch(
                    algorithm = value.getString("algorithm"),
                    fromSha256 = value.getString("fromSha256"),
                    size = value.getLong("size"),
                    sha256 = value.getString("sha256"),
                    url = value.getString("url"),
                )
            }
            WindowsUpdateFile(
                path = item.getString("path"),
                category = item.optString("category", "app"),
                size = item.getLong("size"),
                sha256 = item.getString("sha256"),
                url = item.getString("url"),
                patch = patch,
            )
        }
        return WindowsUpdateRelease(
            forced = update.optBoolean("forced"),
            versionCode = update.getInt("versionCode"),
            versionName = update.getString("versionName"),
            entrypoint = update.getString("entrypoint"),
            releaseNote = update.optString("releaseNote"),
            totalSize = update.getLong("totalSize"),
            files = files,
        )
    }

    companion object {
        private val SUPPORTED_PATCHES = setOf("bsdiff", "courgette")

        fun installationRoot(): Path? = System.getProperty("taotao.install.root")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }

        fun confirmStableRun() {
            if (System.getProperty("taotao.launched.by.stable.launcher") != "true") return
            runCatching {
                val result = ProcessBuilder(
                    "reg.exe", "add", "HKCU\\Software\\TaotaoMusic", "/v", "UpdateAttempts",
                    "/t", "REG_DWORD", "/d", "0", "/f",
                ).redirectErrorStream(true).start()
                result.inputStream.readAllBytes()
                check(result.waitFor() == 0) { "清除更新尝试计数失败" }
            }
        }

        private fun checkedChild(root: Path, relative: String): Path {
            val target = root.resolve(relative.replace('\\', '/')).normalize()
            require(target.startsWith(root.normalize())) { "更新路径越界：$relative" }
            return target
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        private fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun atomicStore(target: Path, properties: Properties) {
            val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
            Files.newOutputStream(temporary).use { properties.store(it, "桃桃音乐 Windows 更新计划") }
            move(temporary, target)
        }

        private fun move(source: Path, target: Path) {
            runCatching { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) }
        }

        private fun deleteRecursively(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/** 构建任务生成的版本资源；IDE 运行缺失时保持可诊断的默认值。 */
object DesktopBuildInfo {
    private val values = Properties().apply {
        DesktopBuildInfo::class.java.getResourceAsStream("/desktop-version.properties")?.use(::load)
    }

    val versionCode: Int = values.getProperty("versionCode")?.toIntOrNull() ?: 1
    val versionName: String = values.getProperty("versionName") ?: "0.0.0-dev"
}
