package com.taotao.music.ui.common

import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.taotaoSettleSpring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * 统一的长按拖动排序列表。
 *
 * 播放队列与歌单详情共用同一套手势与动画，此前两个页面各写一套
 * （队列是长按拖动，歌单是上下箭头逐格挪动），体验和维护成本都差：
 *
 * - 长按右侧拖把后整行跟随手指，跨过半行高度就与相邻行交换位置，列表首尾有阻尼；
 * - 松手后拖行回弹落位，让位的相邻行用 [Modifier.animateItem] 弹簧补间；
 * - 系统开启「减弱动态效果」时（[LocalReduceMotion]）取消回弹与补间，直接落位；
 * - 拖动期间列表完全由本地副本驱动，外部数据（播放器队列、云端歌单）只在空闲时同步，
 *   避免回调在手势中打断画面；
 * - 行的身份由内部自增 id 承担：外部列表更新时按 [identity] 回收旧 id，
 *   重复出现的条目（队列里同一首歌可能出现两次）也不会因换位而丢失手势。
 *
 * 放在 androidApp 而不是 player-ui：手势依赖 [LocalReduceMotion] 与
 * [taotaoSettleSpring]，它们目前是 Android 侧的动画设施；桌面端需要拖动排序时
 * 再把这套设施一起上移。
 *
 * @param items 权威数据。组件在空闲时用它重建行，拖动与落位期间忽略它的变化。
 * @param identity 行的稳定身份键，用于跨更新找回行的 id（同时也是动画 key 的依据）。
 * @param onMove 松手落位后提交；[from] 与 [to] 是本次手势开始与结束时的下标。
 * @param activeIndex 权威列表中需要高亮的行下标（如「正在播放」）；拖动换位时高亮跟着行走，
 *   传 -1 表示没有需要跟踪的行。
 * @param row 单行内容。[rowModifier] 承载拖动位移与补位动画，必须挂到行根布局上；
 *   [dragHandle] 是长按手势的拖把，通常传给 [SongRow] 的同名参数。
 */
@Composable
fun <T> DragReorderList(
    items: List<T>,
    identity: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    activeIndex: Int = -1,
    row: @Composable (
        index: Int,
        item: T,
        isActive: Boolean,
        rowModifier: Modifier,
        dragHandle: @Composable (Modifier) -> Unit,
    ) -> Unit,
) {
    val initialRows = remember { items.mapIndexed { index, item -> DragRow(index.toLong(), item) } }
    var visualRows by remember { mutableStateOf(initialRows) }
    var nextRowId by remember { mutableLongStateOf(initialRows.size.toLong()) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var draggedDistance by remember { mutableFloatStateOf(0f) }
    var visualActiveIndex by remember { mutableIntStateOf(activeIndex) }
    var settling by remember { mutableStateOf(false) }
    var settleOffset by remember { mutableStateOf(Animatable(0f)) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current

    // 非拖动阶段才接收外部列表；拖动中完全由本地列表驱动，避免回调打断手势帧。
    LaunchedEffect(items, activeIndex, draggedIndex, settling) {
        if (draggedIndex == -1 && !settling) {
            val reusableRows = visualRows.toMutableList()
            visualRows = items.map { item ->
                val matchIndex = reusableRows.indexOfFirst { existing ->
                    identity(existing.value) == identity(item)
                }
                if (matchIndex >= 0) {
                    reusableRows.removeAt(matchIndex).copy(value = item)
                } else {
                    DragRow(nextRowId++, item)
                }
            }
            visualActiveIndex = activeIndex
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
    ) {
        itemsIndexed(visualRows, key = { _, rowItem -> rowItem.id }) { index, rowItem ->
            // pointerInput 只按 rowItem.id 建协程，行换位后协程不重启；直接捕获 index
            // 会拿到换位前的旧下标（表现为拖这行动了那行、松手后顺序被错误提交）。
            // 用 State 转发最新下标，手势闭包每次读取都拿到当前值。
            val liveIndex by rememberUpdatedState(index)
            val isActive = index == visualActiveIndex
            val isDragged = index == draggedIndex
            val rowOffsetY = when {
                !isDragged -> 0f
                settling -> settleOffset.value
                else -> draggedDistance
            }
            row(
                index,
                rowItem.value,
                isActive,
                Modifier
                    .onSizeChanged { size ->
                        if (size.height > 0) rowHeightPx = size.height.toFloat()
                    }
                    .zIndex(if (isDragged) 1f else 0f)
                    .animateItem(
                        // 被拖行直接跟随手指；只让让位的相邻行补间，避免两套位移互相拉扯。
                        placementSpec = if (isDragged || reduceMotion) {
                            null
                        } else {
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium,
                            )
                        },
                    )
                    .graphicsLayer { translationY = rowOffsetY },
                { handleModifier ->
                    Icon(
                        Icons.Default.DragHandle,
                        "长按拖动调整顺序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = handleModifier.pointerInput(rowItem.id, reduceMotion) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    settleOffset = Animatable(0f)
                                    dragStartIndex = liveIndex
                                    draggedIndex = liveIndex
                                    draggedDistance = 0f
                                    settling = false
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = {
                                    val fromIndex = dragStartIndex
                                    val toIndex = draggedIndex
                                    val fromOffset = draggedDistance
                                    if (reduceMotion) {
                                        draggedDistance = 0f
                                        settling = false
                                        draggedIndex = -1
                                        dragStartIndex = -1
                                    } else {
                                        val animation = Animatable(fromOffset)
                                        settleOffset = animation
                                        draggedDistance = 0f
                                        settling = true
                                        dragScope.launch {
                                            animation.animateTo(0f, taotaoSettleSpring())
                                            if (settleOffset === animation) {
                                                settling = false
                                                draggedIndex = -1
                                                dragStartIndex = -1
                                            }
                                        }
                                    }
                                    // visualRows 是状态读取，松手时取到的是当前行数；
                                    // 捕获的 items 可能是手势开始前的旧列表。
                                    if (fromIndex in visualRows.indices && toIndex in visualRows.indices && fromIndex != toIndex) {
                                        onMove(fromIndex, toIndex)
                                    }
                                },
                                onDragCancel = {
                                    settleOffset = Animatable(0f)
                                    draggedIndex = -1
                                    dragStartIndex = -1
                                    draggedDistance = 0f
                                    settling = false
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                if (draggedIndex !in visualRows.indices || rowHeightPx <= 0f) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                val pushingPastTop = draggedIndex == 0 && draggedDistance <= 0f && dragAmount.y < 0f
                                val pushingPastBottom = draggedIndex == visualRows.lastIndex &&
                                    draggedDistance >= 0f && dragAmount.y > 0f
                                draggedDistance += if (pushingPastTop || pushingPastBottom) {
                                    dragAmount.y * 0.35f
                                } else {
                                    dragAmount.y
                                }
                                val reorderThresholdPx = rowHeightPx / 2f
                                while (kotlin.math.abs(draggedDistance) >= reorderThresholdPx) {
                                    val direction = if (draggedDistance > 0f) 1 else -1
                                    val destination = (draggedIndex + direction).coerceIn(visualRows.indices)
                                    if (destination == draggedIndex) {
                                        break
                                    }
                                    visualRows = visualRows.moved(draggedIndex, destination)
                                    visualActiveIndex = movedIndex(visualActiveIndex, draggedIndex, destination)
                                    draggedIndex = destination
                                    // 基准位置换到相邻行后反向扣除完整行高，屏幕坐标保持连续。
                                    draggedDistance -= direction * rowHeightPx
                                }
                            }
                        },
                    )
                },
            )
        }
    }
}

/** 拖动排序列表内部使用的一行：[id] 供 LazyColumn 复用与动画，[value] 是业务数据。 */
private data class DragRow<T>(val id: Long, val value: T)

/** 返回列表项从 [from] 移到 [to] 后，原 [index] 对应的新下标。 */
internal fun movedIndex(index: Int, from: Int, to: Int): Int = when {
    index == from -> to
    from < to && index in (from + 1)..to -> index - 1
    to < from && index in to until from -> index + 1
    else -> index
}

/** 创建移动后的副本，拖动手势期间不修改调用方持有的权威列表。 */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }
