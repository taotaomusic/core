package com.taotao.music.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * 全局动效规范。
 *
 * 所有动画都必须从这里取时长与曲线，页面里不要再写字面量 —— 那会让同类动作在不同页面
 * 快慢不一，观感上就是"这个应用的动画很乱"。
 *
 * 选型上分两类，对应两种语义：
 *
 * - **tween + 缓动曲线**：有明确起点终点的过渡，比如换页。需要可预期的时长。
 * - **spring**：状态切换与元素出现，比如选中、点赞、迷你播放器升起。弹簧带一点回弹，
 *   手感比匀速插值自然；而且被打断时能从当前速度接着走，不会跳。
 *
 * 时长必须是 Int：`tween` 的 `durationMillis` 是 Int，用 Long 编译不过。
 */
object AnimationDurations {
    /** 微交互：图标切换、着色、选中态。短到几乎无感，但少了就会觉得生硬。 */
    const val MICRO = 140

    /** 常规状态变化：徽标、进度归位、列表项出现。 */
    const val SHORT = 200

    /** 页面之间的过渡。再长就显得拖沓。 */
    const val PAGE = 280

    /** 详情页升起/落下。位移是整屏高度，比横向换页远，时间给足才不显得仓促。 */
    const val SHEET = 340

    /** 内容淡入淡出，比如骨架屏换成结果。 */
    const val FADE = 220

    /** 封面旋转一周。慢速匀速转，纯装饰。 */
    const val COVER_SPIN = 20_000
}

/**
 * 缓动曲线，取自 Material 动效规范的控制点。
 *
 * Compose 没有内置这几条，用 [CubicBezierEasing] 表达。
 */
object AnimationCurves {
    /** 强调入场：先快后慢，元素利落地落位。 */
    val emphasizedIn: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调离场：先慢后快，迅速让出空间。 */
    val emphasizedOut: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 标准入场，用于不需要强调的过渡。 */
    val standardIn: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** 标准离场。 */
    val standardOut: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

/** 通用 tween。泛型让同一个函数既能驱动 Float（透明度）也能驱动 IntOffset（位移）。 */
fun <T> taotaoTween(
    durationMillis: Int = AnimationDurations.PAGE,
    delayMillis: Int = 0,
    easing: Easing = AnimationCurves.standardIn,
): FiniteAnimationSpec<T> = tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)

/**
 * 状态切换用的弹簧。
 *
 * 阻尼比略低于 1 才有一点回弹；完全不回弹（DampingRatioNoBouncy）观感上和线性差不多。
 */
fun <T> taotaoSpring(
    dampingRatio: Float = 0.75f,
    stiffness: Float = Spring.StiffnessMediumLow,
): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)

/** 点按反馈用的快弹簧：要立刻跟手，不能有拖尾。 */
fun <T> taotaoSpringSnappy(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh)

/**
 * 页面的层级深度，用来判断换页是"进"还是"退"。
 *
 * 方向必须区分：进和退用同一个方向的位移时，返回就没有任何空间线索，
 * 用户感觉不到自己是"退回来了"，这是原来那套过渡最难看的地方。
 */
fun pageDepthOf(page: String): Int = when (page) {
    "home", "mine" -> 0
    "search", "settings" -> 1
    "detail" -> 2
    else -> 0
}

/**
 * 换页过渡。
 *
 * 三种语义分开处理：
 *
 * - **详情页**是"正在播放"那一层，从底部升起来盖住当前页，关闭时落回去 ——
 *   和左右换页不是一回事，音乐类应用几乎都是这个手势语言。
 * - **进入下一层**（首页 → 搜索 / 设置）：新页从右侧滑入，旧页向左退出。
 *   方向必须区分，否则返回时没有任何空间线索，用户感觉不到自己"退回来了"。
 * - **同层之间**（音乐 ⇄ 我的）：没有进退可言，纯淡入淡出，别硬造一个方向。
 *
 * 横向位移按容器宽度取比例而不是写死 dp：同一个数值在小屏和大屏上观感差别很大。
 */
fun AnimatedContentTransitionScope<String>.pageTransition(): ContentTransform {
    // 详情页升起：它必须画在旧页**之上**，否则会看到它从旧页背后钻出来。
    // zIndex 给 1，离开时新页拿默认的 0，详情页就仍然压在上面往下落。
    if (targetState == "detail") {
        return ContentTransform(
            targetContentEnter = slideInVertically(
                animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.emphasizedIn),
            ) { height -> height } + fadeIn(animationSpec = taotaoTween(AnimationDurations.MICRO)),
            // 被盖住的那页轻微缩小并淡出，做出"被压到下面去"的层次。
            initialContentExit = fadeOut(animationSpec = taotaoTween(AnimationDurations.PAGE)) +
                scaleOut(targetScale = 0.94f, animationSpec = taotaoTween(AnimationDurations.SHEET)),
            targetContentZIndex = 1f,
            sizeTransform = null,
        )
    }
    if (initialState == "detail") {
        return ContentTransform(
            targetContentEnter = fadeIn(animationSpec = taotaoTween(AnimationDurations.PAGE)) +
                scaleIn(initialScale = 0.94f, animationSpec = taotaoTween(AnimationDurations.SHEET)),
            initialContentExit = slideOutVertically(
                animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.emphasizedOut),
            ) { height -> height } + fadeOut(animationSpec = taotaoTween(AnimationDurations.SHEET)),
            sizeTransform = null,
        )
    }

    val fromDepth = pageDepthOf(initialState)
    val toDepth = pageDepthOf(targetState)
    if (fromDepth == toDepth) {
        return contentFadeIn() togetherWith contentFadeOut() using null
    }
    val direction = if (toDepth > fromDepth) 1 else -1
    val enter = slideInHorizontally(
        animationSpec = taotaoTween(AnimationDurations.PAGE, easing = AnimationCurves.emphasizedIn),
    ) { width -> direction * width / 6 } + fadeIn(
        animationSpec = taotaoTween(AnimationDurations.PAGE, easing = AnimationCurves.standardIn),
    )
    val exit = slideOutHorizontally(
        animationSpec = taotaoTween(AnimationDurations.PAGE, easing = AnimationCurves.emphasizedOut),
    ) { width -> -direction * width / 6 } + fadeOut(
        // 离场比入场快：两页同时半透明会看到背景透出来。
        animationSpec = taotaoTween(AnimationDurations.FADE - 60, easing = AnimationCurves.standardOut),
    )
    // using null 关掉尺寸动画：默认的 SizeTransform 用 spring 驱动尺寸，而位移和淡入是
    // tween，两套曲线混在一起会让页面边缘有轻微的二次抖动。
    return enter togetherWith exit using null
}

/**
 * 列表项入场。
 *
 * **刻意不按下标错开延迟。** 搜索结果是逐条流式到达的，不存在需要错开的同帧批次；
 * 按下标加延迟的结果是第 7 项之后每一项都固定晚 168 毫秒才开始淡入，
 * 表现为恒定的迟滞而不是瀑布感 —— 那正是"列表出现得很拖"的来源。
 *
 * 淡入加一点上移和缩放，短促收尾。
 */
fun listItemEnter(@Suppress("UNUSED_PARAMETER") index: Int = 0): EnterTransition =
    fadeIn(animationSpec = taotaoTween(AnimationDurations.SHORT, easing = AnimationCurves.standardIn)) +
        slideInVertically(animationSpec = taotaoSpring(dampingRatio = 0.85f)) { height -> height / 6 } +
        scaleIn(initialScale = 0.96f, animationSpec = taotaoSpring(dampingRatio = 0.85f))

/** 从下方升起，用于迷你播放器一类贴边元素。 */
fun riseIn(): EnterTransition =
    slideInVertically(animationSpec = taotaoSpring()) { height -> height } +
        fadeIn(animationSpec = taotaoTween(AnimationDurations.SHORT))

fun sinkOut(): ExitTransition =
    slideOutVertically(
        animationSpec = taotaoTween<IntOffset>(AnimationDurations.SHORT, easing = AnimationCurves.emphasizedOut),
    ) { height -> height } +
        fadeOut(animationSpec = taotaoTween(AnimationDurations.MICRO))

/** 内容整体淡入淡出，用于骨架屏 → 结果 → 空态之间的切换。 */
fun contentFadeIn(): EnterTransition = fadeIn(animationSpec = taotaoTween(AnimationDurations.FADE))

fun contentFadeOut(): ExitTransition =
    fadeOut(animationSpec = taotaoTween(AnimationDurations.FADE, easing = AnimationCurves.standardOut))
