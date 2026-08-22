package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
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

/** 未唱到的字的颜色。比灰色略深，和高亮的珊瑚红形成对比但不刺眼。 */
private val DimText = Color(0xFFBDB3B0)

/**
 * 歌词页。详情页左右滑动即可切换到这里。
 *
 * 有逐字时间轴（服务端的 yrc）时做卡拉 OK 式推进：当前行按已唱比例做颜色分割，
 * 只有行级时间轴时退化为整行高亮，完全没有时间轴时只分行展示。
 */
@Composable
fun LyricPane(
    lyric: Lyric,
    positionMs: Int,
    title: String,
    artist: String,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 26.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 14.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
        }
        if (lyric.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♪", fontSize = 40.sp, color = DimText)
                    Text("暂无歌词", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
            return@Column
        }
        LyricList(lyric = lyric, positionMs = positionMs, onSeek = onSeek)
    }
}

@Composable
private fun LyricList(lyric: Lyric, positionMs: Int, onSeek: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val currentIndex = lyric.indexAt(positionMs)

    // 只在用户没有手动滚动时跟随，否则会把用户正在看的位置拽回去。
    LaunchedEffect(currentIndex, listState.isScrollInProgress) {
        if (currentIndex >= 0 && !listState.isScrollInProgress) {
            runCatching { listState.animateScrollToItem(currentIndex) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 上下留出半屏空白，animateScrollToItem 会把目标行对齐到内容区顶部，
        // 加上这段留白后当前行正好落在屏幕中部，不必自己换算像素偏移。
        val halfHeight = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = halfHeight, bottom = halfHeight),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(lyric.lines) { index, line ->
                val active = lyric.synced && index == currentIndex
                LyricRow(
                    text = line.text,
                    active = active,
                    centered = lyric.synced,
                    // 只有当前行需要算逐字进度，其余行整体点亮或整体变暗即可。
                    progress = if (active) lyric.progressOf(index, positionMs) else 0f,
                    sung = lyric.synced && index < currentIndex,
                    onClick = if (lyric.synced) ({ onSeek(line.timeMs) }) else null,
                )
            }
        }
    }
}

@Composable
private fun LyricRow(
    text: String,
    active: Boolean,
    centered: Boolean,
    progress: Float,
    sung: Boolean,
    onClick: (() -> Unit)?,
) {
    val style = TextStyle(
        fontSize = if (active) 21.sp else 16.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        lineHeight = if (active) 28.sp else 22.sp,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        brush = brushFor(active, progress, sung),
    )
    Text(
        text = text,
        style = style,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 逐字高亮用的画刷。
 *
 * 渐变停靠点必须严格递增，底层 LinearGradient 不接受重复位置，所以用一个极小的
 * 间隔做硬边；两端的纯色情况单独用 SolidColor 处理，避免比例贴边时算出非法停靠点。
 * 画刷作用于文本自身的边界，所以行宽即字宽，分割位置与已唱比例一致。
 */
private fun brushFor(active: Boolean, progress: Float, sung: Boolean): Brush {
    if (!active) return SolidColor(if (sung) TaotaoCoral.copy(alpha = 0.45f) else DimText)
    val edge = progress.coerceIn(0f, 1f)
    if (edge <= EDGE_EPSILON) return SolidColor(DimText)
    if (edge >= 1f - EDGE_EPSILON) return SolidColor(TaotaoCoral)
    return Brush.horizontalGradient(
        0f to TaotaoCoral,
        edge to TaotaoCoral,
        (edge + EDGE_EPSILON) to DimText,
        1f to DimText,
    )
}

private const val EDGE_EPSILON = 0.002f
