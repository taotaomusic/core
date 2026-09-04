package com.taotao.music.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.hotfix.HotfixInstaller
import com.taotao.music.data.DeviceIdStore
import com.taotao.music.data.RemoteConfigStore
import com.taotao.music.data.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 热更新流程编排：检查、下载、安装。
 *
 * 状态以 Compose 状态暴露，界面只读不写。所有网络与磁盘动作都在 IO 线程执行，
 * 状态回写发生在主线程。
 */
class UpdateManager(
    context: Context,
    tokenProvider: TokenProvider?,
    initialPatchVersion: Int = 0,
    private val channel: String = "release",
) {
    private val appContext = context.applicationContext
    private val api = AppUpdateApi(tokenProvider)
    private val downloader = UpdateDownloader(appContext)
    private val installer = UpdateInstaller(appContext)
    private val deviceIdStore = DeviceIdStore(appContext)
    private val hotfix = HotfixInstaller(appContext)

    /** 远程配置缓存，界面可直接读取，取不到时用代码内默认值。 */
    val remoteConfig = RemoteConfigStore(appContext)

    /** 用户已划掉的版本号，跨启动记住。 */
    private val preferences = appContext.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    /** 同一时刻只允许一次检查。原来靠读 status 判断，两个协程可能同时通过。 */
    private val checkLock = Mutex()


    var status by mutableStateOf(UpdateStatus())
        private set

    /**
     * 手动检查的结果，供界面弹一次提示后自行清空。
     * 后台检查刻意不写它 —— 网络不好不该打扰用户。
     */
    var manualResult by mutableStateOf<String?>(null)
        private set

    /** 本机已安装的版本号，用于上报和比对。 */
    val installedVersionCode: Long = versionCodeOf(packageInfo())
    val installedVersionName: String = packageInfo()?.versionName ?: "未知"

    /**
     * 当前生效的热修复补丁版本，0 表示没有。
     *
     * 初值来自 Application 启动时的加载结果，**必须在构造时就填上**：
     * `check()` 是网络调用，可能比"平稳运行后确认"那个 5 秒延迟先跑完。
     * 初值留 0 的话，启动时已经加载好的补丁会被判成"有新补丁"再下载一遍。
     *
     * **必须是 Compose 状态**：界面靠它的变化重新触发确认效果。做成普通 var 的话，
     * 会话中途装上的补丁永远等不到确认 —— 尝试计数一直是 1，下次启动就被判成
     * "加载后启动失败"而回滚。表现是"点重试能生效，但一重启就没了"。
     */
    var activePatchVersion by mutableStateOf(initialPatchVersion)
        private set

    /**
     * 确认热修复补丁可用。
     *
     * 必须在应用**平稳跑起来之后**调用，不是刚启动就调 —— 这是自愈机制的另一半：
     * 加载前记了尝试计数，只有走到这里才清零；太早调等于把保护关掉。
     *
     * [activeVersion] 是**启动时**加载到的版本。会话中途装上的补丁不能被它覆盖回去，
     * 所以这里取两者较大的那个。
     */
    fun confirmPatch(activeVersion: Int) {
        activePatchVersion = maxOf(activePatchVersion, activeVersion)
        hotfix.confirm()
    }

    /**
     * 检查更新并落盘远程配置。
     *
     * [manual] 为真表示用户主动点了「检查更新」：此时必须给出可见结果，
     * 因为静默失败在按钮上的表现是「点了没反应」。后台检查仍然保持安静 ——
     * 网络不好不该让用户看到一个错误弹窗。
     *
     * 检查是**非破坏性**的：失败时保留已经发现的更新，不把 AVAILABLE 抹回 UP_TO_DATE。
     * 会话中途会被响应头触发重查，抹掉状态等于让用户刚看到的更新提示消失。
     */
    suspend fun check(manual: Boolean = false) {
        if (status.stage == UpdateStage.DOWNLOADING) return
        // tryLock 而不是 lock：重复触发直接丢弃，不排队。
        if (!checkLock.tryLock()) return
        try {
            val previous = status
            status = status.copy(stage = UpdateStage.CHECKING)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.bootstrap(installedVersionCode, Build.VERSION.SDK_INT, deviceIdStore.deviceId(), channel)
                }
            }.getOrElse {
                // 失败时回到检查前的状态，而不是造一个全新的 UP_TO_DATE。
                status = if (previous.stage == UpdateStage.CHECKING) UpdateStatus(stage = UpdateStage.UP_TO_DATE) else previous
                if (manual) manualResult = "检查更新失败，请稍后再试"
                return
            }

            withContext(Dispatchers.IO) { remoteConfig.save(result.config, result.configVersion) }

            // 热修复始终后台静默安装；用户只关心整包版本，不展示补丁状态或失败细节。
            result.patch?.let { patch ->
                val ok = withContext(Dispatchers.IO) {
                    hotfix.install(patch, installedVersionCode, activePatchVersion)
                }
                if (ok) {
                    activePatchVersion = patch.patchVersion
                }
            }

            val release = result.release
            if (release == null || release.versionCode <= installedVersionCode) {
                // 服务端不该下发不高于当前版本的包，真出现时按无更新处理，避免死循环提示。
                status = UpdateStatus(stage = UpdateStage.UP_TO_DATE)
                if (manual) manualResult = "已是最新版本 $installedVersionName"
                return
            }
            // 用户划掉过这个版本就不再自动弹；手动检查视为明确想看，忽略这条记录。
            if (!manual && !result.forced && release.versionCode == dismissedVersionCode()) {
                status = UpdateStatus(stage = UpdateStage.UP_TO_DATE)
                return
            }
            val ready = withContext(Dispatchers.IO) { downloader.completedApk(release) }
            status = if (ready != null) {
                UpdateStatus(stage = UpdateStage.READY, release = release, forced = result.forced, progressPercent = 100, apk = ready)
            } else {
                UpdateStatus(stage = UpdateStage.AVAILABLE, release = release, forced = result.forced)
            }
        } finally {
            checkLock.unlock()
        }
    }

    /**
     * 响应头带回的版本号提示。
     *
     * 服务端在每个响应上下发当前全量可用的最高版本号，比本机高时才检查整包。
     * 头只是提示，是否真的要更新仍由 `/app/bootstrap` 判定。
     */
    suspend fun onLatestVersionHint(latestVersionCode: Long) {
        if (latestVersionCode <= installedVersionCode) return
        if (latestVersionCode == dismissedVersionCode()) return
        // 已经发现同一个版本就不用再查了。
        if (status.release?.versionCode == latestVersionCode) return
        if (status.stage == UpdateStage.DOWNLOADING || status.stage == UpdateStage.READY) return
        check()
    }

    /**
     * 正常业务响应带回“本 APK 对应的最新补丁号”。只有它严格高于本地补丁时才检查，
     * 所以每次请求只做两个整数比较，不会把 Bootstrap 变成高频轮询。
     */
    suspend fun onLatestPatchHint(latestPatchVersion: Int) {
        if (latestPatchVersion <= activePatchVersion) return
        check()
    }

    fun consumeManualResult(): String? = manualResult?.also { manualResult = null }

    /** 下载安装包。完成后停在 [UpdateStage.READY]，由用户点击触发安装。 */
    suspend fun download() {
        val release = status.release ?: return
        if (status.stage == UpdateStage.DOWNLOADING) return
        status = status.copy(stage = UpdateStage.DOWNLOADING, progressPercent = 0, error = null)
        runCatching {
            withContext(Dispatchers.IO) {
                downloader.download(release) { percent ->
                    // 进度回调在 IO 线程，Compose 状态写入是线程安全的，重组会被调度到主线程。
                    status = status.copy(progressPercent = percent)
                }
            }
        }.onSuccess { apk ->
            status = status.copy(stage = UpdateStage.READY, progressPercent = 100, apk = apk)
        }.onFailure { error ->
            status = status.copy(stage = UpdateStage.FAILED, error = error.message ?: "下载失败，请重试")
        }
    }

    /** 唤起安装。失败时把原因写进状态供界面提示。 */
    fun install() {
        val apk = status.apk ?: return
        installer.install(apk)?.let { reason ->
            status = status.copy(stage = UpdateStage.FAILED, error = reason)
        }
    }

    /**
     * 用户忽略本次可选更新；强制更新不允许忽略。
     * 记下版本号并持久化：否则会话中途的重查会让刚划掉的弹窗立刻复活。
     */
    fun dismiss() {
        if (status.forced) return
        status.release?.versionCode?.let { preferences.edit().putLong(KEY_DISMISSED, it).apply() }
        status = UpdateStatus(stage = UpdateStage.UP_TO_DATE)
    }

    private fun dismissedVersionCode(): Long = preferences.getLong(KEY_DISMISSED, 0L)

    /** 失败后重试：已下载的分片会被续传复用。 */
    suspend fun retry() {
        if (status.release == null) check(manual = true) else download()
    }

    private fun packageInfo(): PackageInfo? = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    private fun versionCodeOf(info: PackageInfo?): Long = when {
        info == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
        else -> @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_version_code"
    }
}
