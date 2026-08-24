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
     * 每个"不装"的分支都会 [HotfixStore.note] 一句人能读的结论 —— 原来它们只写
     * `Log.w` 就静默返回，而测试机连不上 adb，表现成"点了检查更新什么也没发生"，
     * 完全无法判断卡在哪一步。
     *
     * @param installedVersionCode 本机 versionCode，必须与补丁的目标版本严格相等。
     * @param activePatchVersion 本次启动已生效的补丁版本，用于跳过重复下载。
     */
    fun install(patch: AvailablePatch, installedVersionCode: Long, activePatchVersion: Int): Boolean {
        if (patch.targetVersionCode != installedVersionCode) {
            val message = "补丁 ${patch.patchVersion} 是给版本 ${patch.targetVersionCode} 的，本机是 $installedVersionCode，不适用"
            Log.i(TAG, message)
            store.note(message)
            return false
        }
        if (patch.patchVersion <= activePatchVersion) {
            store.note("补丁 ${patch.patchVersion} 已经在生效中，无需重复安装")
            return false
        }
        // 加载失败过的补丁不再重试，否则会陷入「下载 → 崩 → 回滚 → 再下载」的循环。
        // 判断带上宿主版本：补丁号按宿主版本各自从 1 开始，旧宿主上失败的 v1
        // 不该拦住新宿主上的 v1。
        if (patch.patchVersion == store.failedPatchVersion(installedVersionCode)) {
            val message = "补丁 ${patch.patchVersion} 之前加载失败过（${store.failureReason() ?: "原因未记录"}），已停止重试。可在设置里清除失败记录"
            Log.w(TAG, message)
            store.note(message)
            return false
        }
        if (patch.sha256.isBlank()) {
            store.note("补丁 ${patch.patchVersion} 没有下发校验值，拒绝安装")
            return false
        }

        val target = HotfixLoader.patchFile(appContext, patch.patchVersion)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            download(patch.url, temporary)
            val actual = sha256Of(temporary)
            if (actual != patch.sha256) {
                val message = "补丁 ${patch.patchVersion} 校验不一致，期望 ${patch.sha256.take(12)}… 实际 ${actual.take(12)}…"
                Log.w(TAG, message)
                store.note(message)
                return false
            }
            if (!temporary.renameTo(target)) {
                store.note("补丁 ${patch.patchVersion} 无法写入存储")
                return false
            }
            // Android 14（API 34）的「Safer dynamic code loading」：targetSdk ≥ 34 时
            // 所有动态加载的 dex/jar/apk **必须先标记只读**，否则加载时系统直接抛
            //   SecurityException: Writable dex file '...' is not allowed
            // 本项目 targetSdk = 35，所以这一步是必需的，不是可选的加固。
            //
            // 漏掉它的表现极其隐蔽：下载、校验、落盘全部成功，只在 DexClassLoader
            // 构造时抛异常；异常被 applyNow 捕获后补丁被标记为「失败过」而永不重试，
            // 界面上没有任何提示。真实踩过一次，查了很久。
            if (!target.setReadOnly()) {
                store.note("补丁 ${patch.patchVersion} 无法设为只读，Android 14+ 会拒绝加载可写的 dex")
                target.delete()
                return false
            }
        } catch (error: Throwable) {
            val message = "补丁 ${patch.patchVersion} 下载失败：${error.message ?: error.javaClass.simpleName}"
            Log.w(TAG, message, error)
            store.note(message)
            return false
        } finally {
            temporary.delete()
        }

        // 先记状态再加载：pendingAttempt 为真意味着"这次加载还没被确认"，
        // 万一它把当前进程搞崩，下次启动就会立刻回滚。
        store.install(patch.patchVersion, patch.targetVersionCode, pendingAttempt = true)
        val ok = HotfixLoader.applyNow(appContext, patch.patchVersion)
        store.note(
            if (ok) "补丁 ${patch.patchVersion} 已加载生效"
            else "补丁 ${patch.patchVersion} 加载失败：${store.failureReason() ?: "原因未记录"}",
        )
        return ok
    }

    /**
     * 确认补丁可用。
     *
     * 应在应用平稳跑起来之后调用（不是刚启动就调）—— 这个调用是自愈机制的另一半，
     * 太早调等于把保护关掉。
     */
    fun confirm() = store.confirm()

    /** 当前状态快照，给设置页展示。 */
    fun diagnose(installedVersionCode: Long, activePatchVersion: Int) = HotfixDiagnostics(
        installedVersionCode = installedVersionCode,
        activePatchVersion = activePatchVersion,
        targetVersionCode = store.targetVersionCode(),
        failedPatchVersion = store.failedPatchVersion(installedVersionCode),
        failureReason = store.failureReason(),
        lastOutcome = store.lastOutcome(),
        lastOutcomeAt = store.lastOutcomeAt(),
    )

    /** 清除"失败过"的记忆，让下次检查更新重新尝试同一个补丁。 */
    fun forgetFailure() = store.forgetFailure()

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
