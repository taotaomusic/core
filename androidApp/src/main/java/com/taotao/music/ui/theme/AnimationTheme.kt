package com.taotao.music.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/**
 * 系统「关闭动画」是否开启。关闭后只保留透明度，去掉位移和缩放。
 */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * 读取 [Settings.Global.ANIMATOR_DURATION_SCALE]。缩放到 0 即视为关闭动画。
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

    /** 按钮按压反馈。 */
    const val PRESS = 160

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
 * 缓动曲线。
 *
 * 入场用 Material emphasized decelerate；离场用更冲的 ease-out，关闭动作一开始就动。
 * 两条都不能用 ease-in：那会把用户盯着的第一下拖慢。
 */
object AnimationCurves {
    /** 强调入场：先快后慢，元素利落地落位。 */
    val emphasizedIn: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调离场：同样先快，比入场更冲，返回立刻有反馈。 */
    val emphasizedOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** 标准入场，用于不需要额外强调的过渡。 */
    val standardIn: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 标准离场。 */
    val standardOut: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
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

/**
 * 队列拖拽松手回位。bounce 约 0.2：能感到一点弹性，又不会把邻行弹乱。
 */
fun <T> taotaoSettleSpring(): SpringSpec<T> =
    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

/**
 * 页面的层级深度，用来判断换页是"进"还是"退"。
 *
 * 方向必须区分：进和退用同一个方向的位移时，返回就没有任何空间线索，
 * 用户感觉不到自己是"退回来了"，这是原来那套过渡最难看的地方。
 */
fun pageDepthOf(page: String): Int = when (page) {
    "home", "mine", "ai", "chat" -> 0
    "search", "settings", "mine-favorites", "mine-history", "mine-local", "mine-playlists" -> 1
    // 账号管理从设置页继续进入，不能和设置页标成同层，否则会被导航策略瞬切。
    // 歌单详情从我的歌单继续进入，不能和我的歌单标成同层。
    // 单曲日记从收藏/历史等列表的歌曲菜单继续进入，同样属于更深一层。
    "profile", "detail", "playlist-detail", "song-diary" -> 2
    else -> 0
}

/**
 * 换页过渡。
 *
 * 三种语义分开处理：
 *
 * - **详情页**是"正在播放"那一层，从底部升起来盖住当前页，关闭时落回去 ——
 *   和左右换页不是一回事，音乐类应用几乎都是这个手势语言。
 * - **进入下一层**（首页 → 搜索 / 设置 / 收藏）：新页从右侧滑入，旧页向左退出。
 *   方向必须区分，否则返回时没有任何空间线索，用户感觉不到自己"退回来了"。
 * - **同层之间**（音乐 ⇄ 我的 ⇄ AI）：没有进退关系，直接完成切换，避免高频导航产生等待。
 *
 * 横向位移按容器宽度取比例而不是写死 dp：同一个数值在小屏和大屏上观感差别很大。
 */
fun AnimatedContentTransitionScope<String>.pageTransition(
    reduceMotion: Boolean = false,
): ContentTransform {
    if (reduceMotion) {
        val fromDepth = pageDepthOf(initialState)
        val toDepth = pageDepthOf(targetState)
        if (targetState != "detail" && initialState != "detail" && fromDepth == toDepth) {
            return EnterTransition.None togetherWith ExitTransition.None using null
        }
        return ContentTransform(
            targetContentEnter = fadeIn(animationSpec = taotaoTween(AnimationDurations.FADE)),
            initialContentExit = fadeOut(
                animationSpec = taotaoTween(AnimationDurations.FADE, easing = AnimationCurves.standardOut),
            ),
            targetContentZIndex = if (targetState == "detail") 1f else 0f,
            sizeTransform = null,
        )
    }
    // 详情页升起：它必须画在旧页**之上**，否则会看到它从旧页背后钻出来。
    // zIndex 给 1，离开时新页拿默认的 0，详情页就仍然压在上面往下落。
    if (targetState == "detail") {
        return ContentTransform(
            targetContentEnter = slideInVertically(
                animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.emphasizedIn),
            ) { height -> height } + fadeIn(animationSpec = taotaoTween(AnimationDurations.MICRO)),
            // 被盖住的那页轻微缩小并淡出，做出"被压到下面去"的层次。
            initialContentExit = fadeOut(
                animationSpec = taotaoTween(AnimationDurations.PAGE, easing = AnimationCurves.standardOut),
            ) +
                scaleOut(
                    targetScale = 0.94f,
                    animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.emphasizedOut),
                ),
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
            ) { height -> height } + fadeOut(
                animationSpec = taotaoTween(AnimationDurations.SHEET, easing = AnimationCurves.standardOut),
            ),
            sizeTransform = null,
        )
    }

    val fromDepth = pageDepthOf(initialState)
    val toDepth = pageDepthOf(targetState)
    if (fromDepth == toDepth) {
        return EnterTransition.None togetherWith ExitTransition.None using null
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

/** 从下方升起，用于迷你播放器一类贴边元素。 */
fun riseIn(reduceMotion: Boolean = false): EnterTransition =
    if (reduceMotion) {
        fadeIn(animationSpec = taotaoTween(AnimationDurations.SHORT))
    } else {
        slideInVertically(animationSpec = taotaoSpring()) { height -> height } +
            fadeIn(animationSpec = taotaoTween(AnimationDurations.SHORT))
    }

fun sinkOut(reduceMotion: Boolean = false): ExitTransition =
    if (reduceMotion) {
        fadeOut(animationSpec = taotaoTween(AnimationDurations.MICRO, easing = AnimationCurves.standardOut))
    } else {
        slideOutVertically(
            animationSpec = taotaoTween<IntOffset>(AnimationDurations.SHORT, easing = AnimationCurves.emphasizedOut),
        ) { height -> height } +
            fadeOut(animationSpec = taotaoTween(AnimationDurations.MICRO, easing = AnimationCurves.standardOut))
    }

/** 内容整体淡入淡出，用于骨架屏 → 结果 → 空态之间的切换。 */
fun contentFadeIn(): EnterTransition = fadeIn(animationSpec = taotaoTween(AnimationDurations.FADE))

fun contentFadeOut(): ExitTransition =
    fadeOut(animationSpec = taotaoTween(AnimationDurations.FADE, easing = AnimationCurves.standardOut))

