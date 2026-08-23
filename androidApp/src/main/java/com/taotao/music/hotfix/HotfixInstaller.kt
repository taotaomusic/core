package com.taotao.music.hotfix

import android.content.Context
import android.util.Log
import com.taotao.music.update.AvailablePatch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 下载并应用热修复补丁。
 *
 * 由更新检查驱动：`bootstrap` 顺带告知有没有补丁，这里判断要不要拿、拿回来验一遍、
 * 然后**立即生效**，不必等重启。
 *
 * 每一步都有明确的拒绝条件，宁可不打补丁也不能装一个来路不明或对不上版本的：
 * 唯一的修复通道本身就是更新通道。
 */
class HotfixInstaller(context: Context) {
    private val appContext = context.applicationContext
    private val store = HotfixStore(appContext)

    /**
     * 处理一个服务端下发的补丁。返回是否有新补丁生效。
     *
     * @param installedVersionCode 本机 versionCode，必须与补丁的目标版本严格相等。
     * @param activePatchVersion 本次启动已生效的补丁版本，用于跳过重复下载。
     */
    fun install(patch: AvailablePatch, installedVersionCode: Long, activePatchVersion: Int): Boolean {
        if (patch.targetVersionCode != installedVersionCode) {
            Log.i(TAG, "补丁 ${patch.patchVersion} 面向版本 ${patch.targetVersionCode}，本机 $installedVersionCode，忽略")
            return false
        }
        if (patch.patchVersion <= activePatchVersion) return false
        // 加载失败过的补丁不再重试，否则会陷入「下载 → 崩 → 回滚 → 再下载」的循环。
        if (patch.patchVersion == store.failedPatchVersion()) {
            Log.w(TAG, "补丁 ${patch.patchVersion} 之前加载失败过（${store.failureReason()}），不再重试")
            return false
        }
        if (patch.sha256.isBlank()) {
            Log.w(TAG, "补丁 ${patch.patchVersion} 没有校验值，拒绝安装")
            return false
        }

        val target = HotfixLoader.patchFile(appContext, patch.patchVersion)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            download(patch.url, temporary)
            val actual = sha256Of(temporary)
            if (actual != patch.sha256) {
                Log.w(TAG, "补丁 ${patch.patchVersion} 校验不一致，期望 ${patch.sha256}，实际 $actual")
                return false
            }
            if (!temporary.renameTo(target)) {
                Log.w(TAG, "补丁 ${patch.patchVersion} 无法落盘")
                return false
            }
        } catch (error: Throwable) {
            Log.w(TAG, "补丁 ${patch.patchVersion} 下载失败", error)
            return false
        } finally {
            temporary.delete()
        }

        // 先记状态再加载：pendingAttempt 为真意味着"这次加载还没被确认"，
        // 万一它把当前进程搞崩，下次启动就会立刻回滚。
        store.install(patch.patchVersion, patch.targetVersionCode, pendingAttempt = true)
        return HotfixLoader.applyNow(appContext, patch.patchVersion)
    }

    /**
     * 确认补丁可用。
     *
     * 应在应用平稳跑起来之后调用（不是刚启动就调）—— 这个调用是自愈机制的另一半，
     * 太早调等于把保护关掉。
     */
    fun confirm() = store.confirm()

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "TaotaoMusic/1.0")
        }
        val code = connection.responseCode
        check(code in 200..299) { "下载补丁失败：HTTP $code" }
        // 补丁只有几 KB，不做断点续传，失败直接重下更简单也更不容易出错。
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        check(target.length() > 0L) { "补丁内容为空" }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "Hotfix"
    }
}
