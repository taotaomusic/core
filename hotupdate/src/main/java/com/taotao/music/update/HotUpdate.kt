package com.taotao.music.update

import android.content.Context
import com.taotao.music.hotfix.HotfixLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 热更新能力的一站式门面 —— 其他 Kotlin 客户端接入的**唯一入口**。
 *
 * `:hotupdate` 模块原本只面向桃桃音乐主 App：宿主要自己拼 `UpdateHost`、自己调
 * `HotfixLoader.apply` 启动补丁、自己在组合根创建 `UpdateManager` 并声明协程作用域。
 * 这个门面把上述三件事收拢成两个静态调用，让任何实现了 [UpdateHost] 契约的
 * Kotlin 客户端（同仓库的其它 App、外部接入方）以最小代码量获得完整热更新能力：
 *
 * ```kotlin
 * // ① Application.onCreate 里尽早调用（补丁要赶在首个业务调用前生效）：
 * HotUpdate.installPatches(this, deviceId(), myTokenProvider)
 *
 * // ② 拿到管理器后挂进状态层（Compose 状态可直接观察）：
 * val hotUpdate = HotUpdate.create(this, myHost)
 * scope.launch { hotUpdate.updateManager.check() }
 * ```
 *
 * 两条**不能省**的纪律（与桃桃主 App 的用法一致）：
 * - `installPatches` 必须在 `Application.onCreate` 里调用，晚于第一次业务调用就白打了；
 * - `confirmPatch` 必须在应用平稳跑起来之后调用（自愈机制的另一半），不要刚启动就调。
 *
 * 依赖方向保持 app -> 模块：门面只认 [UpdateHost] / [UpdateConfigStore] 契约，
 * 不依赖任何宿主类型。
 */
object HotUpdate {

    /**
     * 启动期补丁加载（等价于原宿主直接调 `HotfixLoader.apply`）。
     *
     * 在 `Application.onCreate` 里调用，返回本次启动生效的补丁版本（0 表示没有）。
     * 把返回值交给 [UpdateManager] 的 `initialPatchVersion` 或 [create] 的同名参数，
     * 避免启动时已加载的补丁被误判成「有新补丁」再下载一遍。
     */
    fun installPatches(context: Context): Int =
        HotfixLoader.apply(context, versionCodeOf(context))

    /**
     * 创建更新管理器。
     *
     * [host] 是宿主实现的依赖契约；[initialPatchVersion] 必须传 [installPatches]
     * 的返回值（或宿主自己记录的当前补丁版本），语义见 [installPatches]。
     * [scope] 默认是模块自建的 SupervisorJob 作用域，宿主传自己的作用域可统一生命周期。
     */
    fun create(
        context: Context,
        host: UpdateHost,
        initialPatchVersion: Int = 0,
        channel: String = "release",
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ): HotUpdateSession {
        val manager = UpdateManager(context, host, initialPatchVersion, channel, scope)
        return HotUpdateSession(updateManager = manager, scope = scope)
    }

    private fun versionCodeOf(context: Context): Long {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull() ?: return 0L
        if (android.os.Build.VERSION.SDK_INT >= 28) return info.longVersionCode
        @Suppress("DEPRECATION")
        return info.versionCode.toLong()
    }
}

/**
 * 门面返回的会话：更新管理器 + 一个默认的后台检查作用域。
 *
 * 界面层仍可直接拿 [updateManager] 读 Compose 状态（status/manualResult/activePatchVersion），
 * 与桃桃主 App 的用法完全一致；后台轮询等非 UI 场景用 [launchCheck] 即可。
 */
class HotUpdateSession(
    val updateManager: UpdateManager,
    private val scope: CoroutineScope,
) {
    /** 在会话作用域里执行一次检查；静默失败不打扰用户（与后台检查语义一致）。 */
    fun launchCheck(manual: Boolean = false) = scope.launch { updateManager.check(manual) }
}
