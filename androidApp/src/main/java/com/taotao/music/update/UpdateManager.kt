package com.taotao.music.update

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taotao.music.data.DeviceIdStore
import com.taotao.music.data.RemoteConfigStore
import com.taotao.music.data.TokenProvider
import kotlinx.coroutines.Dispatchers
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
    private val channel: String = "release",
) {
    private val appContext = context.applicationContext
    private val api = AppUpdateApi(tokenProvider)
    private val downloader = UpdateDownloader(appContext)
    private val installer = UpdateInstaller(appContext)
    private val deviceIdStore = DeviceIdStore(appContext)

    /** 远程配置缓存，界面可直接读取，取不到时用代码内默认值。 */
    val remoteConfig = RemoteConfigStore(appContext)

    var status by mutableStateOf(UpdateStatus())
        private set

    /** 本机已安装的版本号，用于上报和比对。 */
    val installedVersionCode: Long = versionCodeOf(packageInfo())
    val installedVersionName: String = packageInfo()?.versionName ?: "未知"

    /**
     * 检查更新并落盘远程配置。
     * 检查失败时不打扰用户：非强制流程直接回到 [UpdateStage.UP_TO_DATE]，
     * 因为「网络不好」不该让用户看到一个错误弹窗。
     */
    suspend fun check() {
        if (status.stage == UpdateStage.CHECKING || status.stage == UpdateStage.DOWNLOADING) return
        status = status.copy(stage = UpdateStage.CHECKING)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                api.bootstrap(installedVersionCode, Build.VERSION.SDK_INT, deviceIdStore.deviceId(), channel)
            }
        }.getOrElse {
            status = UpdateStatus(stage = UpdateStage.UP_TO_DATE)
            return
        }

        withContext(Dispatchers.IO) { remoteConfig.save(result.config, result.configVersion) }

        val release = result.release
        if (release == null || release.versionCode <= installedVersionCode) {
            // 服务端不该下发不高于当前版本的包，真出现时按无更新处理，避免死循环提示。
            status = UpdateStatus(stage = UpdateStage.UP_TO_DATE)
            return
        }
        val ready = withContext(Dispatchers.IO) { downloader.completedApk(release) }
        status = if (ready != null) {
            UpdateStatus(stage = UpdateStage.READY, release = release, forced = result.forced, progressPercent = 100, apk = ready)
        } else {
            UpdateStatus(stage = UpdateStage.AVAILABLE, release = release, forced = result.forced)
        }
    }

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

    /** 用户忽略本次可选更新；强制更新不允许忽略。 */
    fun dismiss() {
        if (status.forced) return
        status = UpdateStatus(stage = UpdateStage.UP_TO_DATE)
    }

    /** 失败后重试：已下载的分片会被续传复用。 */
    suspend fun retry() {
        if (status.release == null) check() else download()
    }

    private fun packageInfo(): PackageInfo? = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    private fun versionCodeOf(info: PackageInfo?): Long = when {
        info == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
        else -> @Suppress("DEPRECATION") info.versionCode.toLong()
    }
}
