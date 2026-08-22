package com.taotao.music

import android.app.Application
import com.taotao.music.data.CrashReporter

/** 应用入口：只做进程级初始化，业务状态仍由页面和播放服务各自持有。 */
class TaotaoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 测试机无法连接 adb，崩溃堆栈必须落盘才能定位问题。
        CrashReporter(this).install()
    }
}
