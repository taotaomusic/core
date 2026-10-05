package com.taotao.music.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
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

/**
 * 共享动效符号的转发（typealias / 包装函数）：十几个页面文件仍从
 * `com.taotao.music.ui.theme` 导入这些名字，动效系统本体已下沉到
 * player-ui 的 SharedMotion.kt（三端共享）。新代码请直接从
 * `com.taotao.music.playerui.theme` 导入。
 */
typealias AnimationDurations = com.taotao.music.playerui.theme.AnimationDurations
typealias AnimationCurves = com.taotao.music.playerui.theme.AnimationCurves

fun <T> taotaoTween(
    durationMillis: Int = com.taotao.music.playerui.theme.AnimationDurations.PAGE,
    delayMillis: Int = 0,
    easing: Easing = com.taotao.music.playerui.theme.AnimationCurves.standardIn,
): FiniteAnimationSpec<T> = com.taotao.music.playerui.theme.taotaoTween(durationMillis, delayMillis, easing)

fun <T> taotaoSpring(
    dampingRatio: Float = 0.75f,
    stiffness: Float = Spring.StiffnessMediumLow,
): SpringSpec<T> = com.taotao.music.playerui.theme.taotaoSpring(dampingRatio, stiffness)

fun <T> taotaoSettleSpring(): SpringSpec<T> = com.taotao.music.playerui.theme.taotaoSettleSpring()

fun riseIn(reduceMotion: Boolean = false): EnterTransition = com.taotao.music.playerui.theme.riseIn(reduceMotion)

fun sinkOut(reduceMotion: Boolean = false): ExitTransition = com.taotao.music.playerui.theme.sinkOut(reduceMotion)

fun contentFadeIn(): EnterTransition = com.taotao.music.playerui.theme.contentFadeIn()

fun contentFadeOut(): ExitTransition = com.taotao.music.playerui.theme.contentFadeOut()

fun pageDepthOf(page: String): Int = com.taotao.music.playerui.theme.pageDepthOf(page)

fun AnimatedContentTransitionScope<String>.pageTransition(
    reduceMotion: Boolean = false,
): ContentTransform = with(com.taotao.music.playerui.theme) { pageTransition(reduceMotion) }
