package com.taotao.music.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally

/**
 * 统一动画时长，单位毫秒。
 * 必须是 Int：Compose 的 `tween` 接受 `durationMillis: Int`，用 Long 无法编译。
 */
object AnimationDurations {
    /** 点击反馈、图标切换。 */
    const val BUTTON_CLICK = 150

    /** 进度条拖动结束后的归位。 */
    const val SLIDER_COMPLETE = 200

    /** 页面之间的过渡。 */
    const val PAGE_TRANSITION = 250

    /** 列表项逐条入场。 */
    const val LIST_ITEM_ENTER = 180
}

/**
 * 缓动曲线。
 *
 * Compose 里没有 `CurveEasing`，三次贝塞尔缓动用 [CubicBezierEasing] 表达；
 * 下面四条对应 Material 的 standard / sharp 语义，控制点取自 Material 动效规范。
 */
object AnimationCurves {
    /** 入场：先快后慢，元素稳稳落位。 */
    val standardIn: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** 离场：先慢后快，迅速让出空间。 */
    val standardOut: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** 快速入场：用于点击反馈一类的短动画。 */
    val sharpIn: Easing = CubicBezierEasing(0f, 0f, 0.6f, 1f)

    /** 快速离场。 */
    val sharpOut: Easing = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
}

/** 通用 tween。泛型让同一个函数既能驱动 Float（透明度）也能驱动 IntOffset（位移）。 */
fun <T> taotaoTween(
    durationMillis: Int = AnimationDurations.PAGE_TRANSITION,
    delayMillis: Int = 0,
    easing: Easing = AnimationCurves.standardIn,
): FiniteAnimationSpec<T> = tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)

fun pageFadeIn(durationMillis: Int = AnimationDurations.PAGE_TRANSITION): EnterTransition =
    fadeIn(animationSpec = taotaoTween(durationMillis, easing = AnimationCurves.standardIn))

fun pageFadeOut(durationMillis: Int = AnimationDurations.PAGE_TRANSITION): ExitTransition =
    fadeOut(animationSpec = taotaoTween(durationMillis, easing = AnimationCurves.standardOut))

/**
 * 页面横向滑入。位移按容器宽度的比例给（默认十分之一），
 * 不写死 dp，否则同一个数值在小屏和大屏上的观感差别很大。
 */
fun pageSlideIn(durationMillis: Int = AnimationDurations.PAGE_TRANSITION, fraction: Int = 10): EnterTransition =
    slideInHorizontally(animationSpec = taotaoTween(durationMillis, easing = AnimationCurves.standardIn)) { it / fraction }

fun pageSlideOut(durationMillis: Int = AnimationDurations.PAGE_TRANSITION, fraction: Int = 10): ExitTransition =
    slideOutHorizontally(animationSpec = taotaoTween(durationMillis, easing = AnimationCurves.standardOut)) { -it / fraction }

/**
 * 列表项逐条入场：按下标错开延迟做出瀑布感。
 * 延迟最多错开 7 项，否则长列表末尾的项要等很久才出现。
 */
fun listItemEnter(index: Int): EnterTransition {
    val delayMillis = index.coerceAtMost(7) * 24
    val duration = AnimationDurations.LIST_ITEM_ENTER
    return fadeIn(animationSpec = taotaoTween(duration, delayMillis, AnimationCurves.sharpIn)) +
        slideInVertically(animationSpec = taotaoTween(duration, delayMillis, AnimationCurves.sharpIn)) { it / 12 }
}
