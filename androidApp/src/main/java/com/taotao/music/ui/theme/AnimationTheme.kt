package com.taotao.music.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 全局动效规范的共享实现已下沉到 player-ui（`com.taotao.music.playerui.theme.SharedMotion`），
 * 三端共用同一套时长与曲线。本文件保留 Android 专属的部分：系统「关闭动画」的读取，
 * 以及一批 typealias / 转发，让既有 `com.taotao.music.ui.theme` 的导入路径继续可用
 * （十几个页面文件都在引用，批量改导入不值得）。
 */
val LocalReduceMotion = com.taotao.music.playerui.theme.LocalReduceMotion

/**
 * 读取 [Settings.Global.ANIMATOR_DURATION_SCALE]。缩放到 0 即视为关闭动画，
 * 并把结果写入共享的 [LocalReduceMotion]，全应用所有动画统一响应。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled(): Boolean =
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    var reduced by remember { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = disabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
