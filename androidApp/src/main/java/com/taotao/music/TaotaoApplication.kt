package com.taotao.music

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Build
import com.taotao.music.data.CrashReporter
import com.taotao.music.hotfix.HotfixLoader

/** 应用入口：只做进程级初始化，业务状态仍由页面和播放服务各自持有。 */
class TaotaoApplication : Application() {

    /** 本次启动生效的补丁版本，0 表示没有。界面读它做展示，也用来确认补丁可用。 */
    var activePatchVersion: Int = 0
        private set

    override fun onCreate() {
        super.onCreate()
        // 测试机无法连接 adb，崩溃堆栈必须落盘才能定位问题。
        CrashReporter(this).install()
        // 热修复必须尽早：晚于第一次业务调用就白打了。放在 onCreate 而不是
        // attachBaseContext，是为了让崩溃上报先装好 —— 补丁自己崩了也要有日志。
        activePatchVersion = HotfixLoader.apply(this, versionCode())
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
