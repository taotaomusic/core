package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.model.Lyric

/** 歌词面板高度。固定高度是为了让内部列表能独立滚动，不与详情页的外层滚动打架。 */
private val PanelHeight = 260.dp

/**
 * 上下留白取面板高度的一半左右。
 * `animateScrollToItem` 会把目标项对齐到可视区顶部，加了这段留白之后
 * 当前行就落在面板中部，不必自己算偏移量。
 */
private val CenteringPadding = 110.dp

/**
 * 歌词面板。
 *
 * 替换原先「把整段 LRC 原文塞进一个 Text」的做法：那样时间戳 `[00:12.34]` 会直接
 * 显示在界面上，而且无法指示唱到哪一句。
 *
 * 有时间轴时高亮当前行并自动滚动，点击某行可跳到对应时间；
 * 没有时间轴（服务端返回纯文本）时退化为分行展示，不高亮也不可点。
 */
@Composable
fun LyricPanel(
    lyric: Lyric,
    positionMs: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyric.isEmpty) {
        Box(modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = Color.Gray, fontSize = 13.sp)
        }
        return
    }

    val listState = rememberLazyListState()
    val currentIndex = remember(lyric, positionMs) { lyric.indexAt(positionMs) }

    // 只在用户没有手动滚动时跟随，否则会把用户正在看的位置拽回去。
    LaunchedEffect(currentIndex, listState.isScrollInProgress) {
        if (currentIndex >= 0 && !listState.isScrollInProgress) {
            runCatching { listState.animateScrollToItem(currentIndex) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().height(PanelHeight),
        contentPadding = PaddingValues(vertical = if (lyric.synced) CenteringPadding else 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(lyric.lines) { index, line ->
            val active = lyric.synced && index == currentIndex
            Text(
                text = line.text,
                color = when {
                    active -> TaotaoCoral
                    lyric.synced -> Color.Gray
                    else -> Color.DarkGray
                },
                fontSize = if (active) 17.sp else 15.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                lineHeight = 22.sp,
                textAlign = if (lyric.synced) TextAlign.Center else TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    // 纯文本歌词没有时间，点击跳转没有意义。
                    .then(if (lyric.synced) Modifier.clickable { onSeek(line.timeMs) } else Modifier)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** 歌词区域外壳：标题加上白色圆角卡片，与详情页其它模块的视觉语言一致。 */
@Composable
fun LyricSection(
    lyric: Lyric,
    positionMs: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text("歌词", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White),
        ) {
            LyricPanel(lyric = lyric, positionMs = positionMs, onSeek = onSeek)
        }
    }
}
