package com.taotao.music.playerui.skin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.taotao.music.playerui.theme.AnimationDurations
import com.taotao.music.playerui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 封面旋转状态：把「旋转由调用方持有」的驱动逻辑沉到皮肤框架里，
 * Android 详情页与 Web 端共用同一套停止条件：
 *
 * 1. 暂停即停（[isPlaying] 为 false 时不推进）；
 * 2. 离开封面页即停（[onCoverPage] 返回 false 时挂起等待、不产生动画帧）；
 * 3. reduce-motion 时固定在 0° 不动画；
 * 4. 宿主退后台（[active] 为 false）即停，回到前台接着转。
 *
 * [restartKey] 变化（换歌）时角度归零重建，避免新歌从旧歌的残留角度开始转。
 */
public class CoverRotationState internal constructor() {
    private val animatable = Animatable(0f)

    /** 当前角度。旋转类皮肤用 `graphicsLayer { rotationZ = state.degrees }` 消费。 */
    public val degrees: Float get() = animatable.value

    internal suspend fun normalize() {
        animatable.snapTo(animatable.value % 360f)
    }

    internal suspend fun spinOneTurn() {
        animatable.animateTo(
            targetValue = animatable.value + 360f,
            animationSpec = tween(AnimationDurations.COVER_SPIN, easing = LinearEasing),
        )
    }

    internal suspend fun snapTo(value: Float) {
        animatable.snapTo(value)
    }
}

/**
 * 创建并驱动 [CoverRotationState]；见其文档了解停止条件。
 *
 * [active] 供接入端桥接「宿主是否在前台」：Android 详情页传「页面处于 RESUMED」
 * （用 repeatOnLifecycle 维护），避免退后台后仍空耗动画帧；Web 分享页恒传 true。
 */
@Composable
public fun rememberCoverRotationState(
    isPlaying: Boolean,
    active: Boolean,
    onCoverPage: () -> Boolean,
    restartKey: Any?,
): CoverRotationState {
    val reduceMotion = LocalReduceMotion.current
    val state = remember(restartKey) { CoverRotationState() }
    if (reduceMotion) {
        LaunchedEffect(state) { state.snapTo(0f) }
        return state
    }
    LaunchedEffect(isPlaying, active) {
        if (!isPlaying || !active) return@LaunchedEffect
        while (isActive) {
            // 不在封面页 / 宿主退后台时挂起等待，不产生动画帧 ——
            // 与原 Android 实现的 snapshotFlow 收敛语义一致。
            while (isActive && (!active || !onCoverPage())) {
                delay(PAGE_CHECK_INTERVAL_MS)
            }
            state.normalize()
            state.spinOneTurn()
        }
    }
    return state
}

/** 不在封面页时的轮询间隔（毫秒）：足够灵敏，又远低于动画帧率。 */
private const val PAGE_CHECK_INTERVAL_MS = 120L
