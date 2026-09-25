package com.taotao.music.ui.player

import com.taotao.music.ui.theme.LyricDim
import com.taotao.music.ui.theme.TaotaoCoral
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.taotao.music.model.Lyric
import com.taotao.music.model.LyricWord
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoTypeScale

/** 用户手动滚动后暂停自动跟随的时长，避免刚滑到别处就被拽回去。 */
private const val ManualScrollGraceMs = 2_500L

/**
 * 歌词页。顶栏、歌名和播放控制由详情页提供，这里只负责歌词本身。
 *
 * 有逐字时间轴（服务端的 yrc）时按字推进，没有则整行高亮，
 * 完全没有时间轴时只分行展示。
 */
@Composable
fun LyricPane(
    lyric: Lyric,
    positionMs: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyric.isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 装饰性音符：走 hero 档（40sp）。它不表达文字层级，所以不新增字号档位，
                // 而是复用同为 40sp 的首屏主标题档。
                Text("♪", style = TaotaoTypeScale.hero, color = LyricDim)
                Text(
                    "暂无歌词",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = TaotaoSpacing.xs),
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val currentIndex = lyric.indexAt(positionMs)
    // 播放位置逐帧变化，但普通歌词行不需要订阅它。把它保存在稳定的 State 引用中，
    // 只有当前逐字行读取该值，避免可见的普通行跟着每一帧重组。
    val positionMsState = rememberUpdatedState(positionMs)
    var manualScrollAtMs by remember { mutableStateOf(0L) }

    // 记录用户最近一次手动滚动的时刻。用 snapshotFlow 而不是把 isScrollInProgress
    // 直接当 LaunchedEffect 的 key：后者会在每次滚动状态翻转时重启协程，
    // 把正在进行的 animateScrollToItem 打断，观感上就是一顿一顿的。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) manualScrollAtMs = System.currentTimeMillis()
        }
    }

    // 只在行号真正变化时才滚动一次（每句一次），不受逐帧进度刷新影响。
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        if (System.currentTimeMillis() - manualScrollAtMs < ManualScrollGraceMs) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(currentIndex) }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // 上下留出半屏空白：animateScrollToItem 把目标行对齐到内容区顶部，
        // 有这段留白后当前行正好落在屏幕中部，不必自己换算像素偏移。
        val halfHeight = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = halfHeight, bottom = halfHeight),
            verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.lg),
        ) {
            itemsIndexed(lyric.lines) { index, line ->
                val active = lyric.synced && index == currentIndex
                if (active && line.words.isNotEmpty()) {
                    CurrentKaraokeLyricRow(
                        words = line.words,
                        centered = true,
                        positionMsState = positionMsState,
                        seekEnabled = true,
                        timeMs = line.timeMs,
                        onSeek = onSeek,
                    )
                } else {
                    StaticLyricRow(
                        text = line.text,
                        active = active,
                        centered = lyric.synced,
                        seekEnabled = lyric.synced,
                        timeMs = line.timeMs,
                        onSeek = onSeek,
                    )
                }
            }
        }
    }
}

@Composable
private fun StaticLyricRow(
    text: String,
    active: Boolean,
    centered: Boolean,
    seekEnabled: Boolean,
    timeMs: Int,
    onSeek: (Int) -> Unit,
) {
    Text(
        text = text,
        style = lyricTextStyle(active = active, centered = centered)
            .copy(color = if (active) TaotaoCoral else LyricDim),
        modifier = lyricRowModifier(seekEnabled, timeMs, onSeek),
    )
}

/**
 * 当前逐字行。它是歌词列表中唯一读取帧级播放位置的组件，避免普通行随进度重组。
 */
@Composable
private fun CurrentKaraokeLyricRow(
    words: List<LyricWord>,
    centered: Boolean,
    positionMsState: State<Int>,
    seekEnabled: Boolean,
    timeMs: Int,
    onSeek: (Int) -> Unit,
) {
    val positionMs by positionMsState
    KaraokeLine(
        words = words,
        positionMs = positionMs,
        style = lyricTextStyle(active = true, centered = centered),
        modifier = lyricRowModifier(seekEnabled, timeMs, onSeek),
    )
}

/**
 * 当前行只改变颜色和字重；字号与行高保持固定，防止 LazyColumn 在换句时跳动。
 *
 * 18sp / 26sp 正好是 [TaotaoTypeScale.sectionTitle]，所以直接基于它 `copy`：
 * 只覆盖字重与对齐，字号、行高、字距都跟着 token 走，不再各写一遍。
 */
private fun lyricTextStyle(active: Boolean, centered: Boolean) = TaotaoTypeScale.sectionTitle.copy(
    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
)

private fun lyricRowModifier(
    seekEnabled: Boolean,
    timeMs: Int,
    onSeek: (Int) -> Unit,
): Modifier = Modifier
    .fillMaxWidth()
    .clip(TaotaoShapes.small)
    .then(
        if (seekEnabled) {
            Modifier.clickable(onClickLabel = "跳转到此句") { onSeek(timeMs) }
        } else {
            Modifier
        },
    )
    .padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.tightVertical)

/**
 * 逐字高亮的当前行。
 *
 * 每个字单独成一个文本节点由 [FlowRow] 排版，而不是给整行文字铺一层水平渐变：
 * 渐变作用于整个文本块的边界，一旦这行折成两行，两行会共用同一个水平分割点，
 * 第二行开头的字明明还没唱到却落在分割点左侧，于是被错误点亮。
 * 拆成独立单元后每个字用自己的时间上色，换行天然正确。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KaraokeLine(
    words: List<LyricWord>,
    positionMs: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    // 主题色在这里取一次，别在每个字的循环里读 —— brushFor 不是 Composable。
    val sung = MaterialTheme.colorScheme.primary
    val unsung = LyricDim
    FlowRow(
        modifier = modifier,
        horizontalArrangement = if (style.textAlign == TextAlign.Center) Arrangement.Center else Arrangement.Start,
    ) {
        words.forEach { word ->
            Text(text = word.text, style = style.copy(brush = brushFor(fractionOf(word, positionMs), sung, unsung)))
        }
    }
}

/** 单个字自身的完成比例。长音的字靠这个比例平滑推进，而不是整字跳变。 */
private fun fractionOf(word: LyricWord, positionMs: Int): Float = when {
    positionMs >= word.endMs -> 1f
    positionMs <= word.timeMs -> 0f
    word.durationMs <= 0 -> 1f
    else -> ((positionMs - word.timeMs).toFloat() / word.durationMs).coerceIn(0f, 1f)
}

/**
 * 单个字的画刷。
 *
 * 渐变停靠点必须严格递增，底层 LinearGradient 不接受重复位置，所以用一个极小的
 * 间隔做硬边；两端的纯色情况单独用 SolidColor，避免比例贴边时算出非法停靠点。
 *
 * 颜色由调用方传入而不是在这里读主题：这不是 Composable，而且逐字循环里
 * 每个字都读一次主题是白花开销。
 */
private fun brushFor(fraction: Float, sung: Color, unsung: Color): Brush {
    val edge = fraction.coerceIn(0f, 1f)
    if (edge <= EdgeEpsilon) return SolidColor(unsung)
    if (edge >= 1f - EdgeEpsilon) return SolidColor(sung)
    return Brush.horizontalGradient(
        0f to sung,
        edge to sung,
        (edge + EdgeEpsilon) to unsung,
        1f to unsung,
    )
}

private const val EdgeEpsilon = 0.002f
