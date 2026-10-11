package com.taotao.music

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Build
import com.taotao.music.data.CrashReporter
import com.taotao.music.data.db.LocalDb
import com.taotao.music.hotfix.HotfixLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.thread

/** 应用入口：只做进程级初始化，业务状态由全局容器、页面和播放服务各自持有。 */
class TaotaoApplication : Application() {

    /** 本次启动生效的补丁版本，0 表示没有。界面读它做展示，也用来确认补丁可用。 */
    var activePatchVersion: Int = 0
        private set

    companion object {
        /**
         * 进程级全局状态容器（播放、收藏、搜索、IM 等的唯一实例）。
         *
         * 惰性创建：首个访问者是主界面组合（[com.taotao.music.ui.app.rememberTaotaoAppState]），
         * 行为与旧的 remember 版本等价；延迟是为了不拖慢冷启动路径。
         * 挂在 Application 上是为了让播放详情 Activity 能拿到与主界面**同一份**状态：
         * 详情页拆成独立 Activity 后，播放队列、收藏与弹窗开关都必须跨 Activity 共享。
         *
         * scope 用独立的 Main 作用域（SupervisorJob 隔离单个任务失败），刻意不用任何
         * 组合作用域：组合作用域随 Activity 销毁而取消，会连带取消播放状态同步等
         * 本应活过页面切换的后台任务。
         */
        // internal：TaotaoAppState 是模块内类型，公共属性不能暴露它（Kotlin 可见性规则）。
        internal val appState: com.taotao.music.ui.app.TaotaoAppState by lazy {
            val app = application
            com.taotao.music.ui.app.TaotaoAppState(
                app.applicationContext,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }

        /** onCreate 时赋值；lazy 求值只会发生在首个组合里，必然晚于 onCreate。 */
        private lateinit var application: TaotaoApplication
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        // 测试机无法连接 adb，崩溃堆栈必须落盘才能定位问题。
        CrashReporter(this).install()
        // 热修复必须尽早：晚于第一次业务调用就白打了。放在 onCreate 而不是
        // attachBaseContext，是为了让崩溃上报先装好 —— 补丁自己崩了也要有日志。
        activePatchVersion = HotfixLoader.apply(this, versionCode())
        // 本地 Room 库首次打开要在后台完成旧 SharedPreferences 的一次性迁移（几十毫秒），
        // 先在这里热身，避免首个交互方（收藏判断/播放恢复）在主线程等待建库与迁移。
        thread(name = "local-db-warmup", isDaemon = true) {
            runCatching { LocalDb.ready(applicationContext) }
                .onFailure { android.util.Log.w("TaotaoApplication", "本地库预热失败", it) }
        }
    }

    private fun versionCode(): Long {
        val info: PackageInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull() ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }
}
