package com.taotao.music.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.model.Lyric
import com.taotao.music.model.LyricLine
import com.taotao.music.model.LyricWord

/** 未唱到的字的颜色。比纯灰略暖，和高亮的珊瑚红同色系。 */
private val DimText = Color(0xFFB9AEAB)

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
                Text("♪", fontSize = 40.sp, color = DimText)
                Text("暂无歌词", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val currentIndex = lyric.indexAt(positionMs)
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(lyric.lines) { index, line ->
                LyricRow(
                    line = line,
                    active = lyric.synced && index == currentIndex,
                    centered = lyric.synced,
                    positionMs = positionMs,
                    onClick = if (lyric.synced) ({ onSeek(line.timeMs) }) else null,
                )
            }
        }
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    active: Boolean,
    centered: Boolean,
    positionMs: Int,
    onClick: (() -> Unit)?,
) {
    val style = TextStyle(
        fontSize = if (active) 21.sp else 16.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        lineHeight = if (active) 29.sp else 23.sp,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
    )
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 2.dp)

    // 只有当前行需要逐字推进；其余行整行一个颜色，省掉大量文本节点。
    if (active && line.words.isNotEmpty()) {
        KaraokeLine(words = line.words, positionMs = positionMs, style = style, modifier = rowModifier)
        return
    }
    Text(
        text = line.text,
        style = style.copy(color = if (active) TaotaoCoral else DimText),
        modifier = rowModifier,
    )
}

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
    FlowRow(
        modifier = modifier,
        horizontalArrangement = if (style.textAlign == TextAlign.Center) Arrangement.Center else Arrangement.Start,
    ) {
        words.forEach { word ->
            Text(text = word.text, style = style.copy(brush = brushFor(fractionOf(word, positionMs))))
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
 */
private fun brushFor(fraction: Float): Brush {
    val edge = fraction.coerceIn(0f, 1f)
    if (edge <= EdgeEpsilon) return SolidColor(DimText)
    if (edge >= 1f - EdgeEpsilon) return SolidColor(TaotaoCoral)
    return Brush.horizontalGradient(
        0f to TaotaoCoral,
        edge to TaotaoCoral,
        (edge + EdgeEpsilon) to DimText,
        1f to DimText,
    )
}

private const val EdgeEpsilon = 0.002f
