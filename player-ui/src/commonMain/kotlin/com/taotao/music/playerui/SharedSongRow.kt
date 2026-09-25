package com.taotao.music.playerui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.taotao.music.model.Song
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing

/**
 * Android 与 Windows 共用的歌曲列表视觉骨架。
 *
 * 封面加载、下载进度和操作按钮通过插槽注入，避免公共组件依赖平台图片库或持久化状态。
 *
 * 字号与圆角全部走 token：`bodyLarge` / `bodySmall` / `labelMedium` 来自
 * [com.taotao.music.playerui.theme.TaotaoTypography]，圆角来自
 * [TaotaoShapes]。此前这里写死了 10dp / 14dp / 3dp / 6dp 等不成刻度的数值。
 *
 * ## 不可播的歌（`song.playable == false`）
 *
 * 整体降透明度、显示「版权不可播」标记，并且**不响应点击**。
 *
 * 之所以在共用组件里做而不是让调用方各自判断：服务端从 2026-09-19 起不再过滤这类歌
 * （波点 App 也不滤，见 `Song.playable` 的说明），于是**每一处列表都会出现它们**。
 * 放在这里一次覆盖 Android / Windows，调用方漏改不会漏出「点了没反应也没解释」的条目。
 *
 * 点击直接禁用而不是弹提示：标记本身就写在行上，用户点之前已经看到了原因；
 * 再弹一句「不可播放」只是把同一件事说两遍。这也是置灰按钮的常规语义。
 */
@Composable
fun SharedSongRow(
    song: Song,
    artworkContent: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    subtitle: String = song.artist,
    downloaded: Boolean = false,
    durationLabel: String = song.duration,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val playable = song.playable
    val contentColor = when {
        !playable -> MaterialTheme.colorScheme.onSurfaceVariant
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SongRowMinHeight)
            .clip(TaotaoShapes.button)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            // 不可播时整行降到 45% 不透明度：封面是调用方注入的插槽，逐个子项改色会漏掉它。
            .then(if (playable) Modifier else Modifier.alpha(0.45f))
            .clickable(enabled = playable, onClick = onClick)
            .padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(TaotaoSizes.artworkRow),
            contentAlignment = Alignment.Center,
        ) {
            artworkContent()
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = TaotaoSpacing.sm, end = TaotaoSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = normalizedDisplayText(song.title),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 不可播时**只显示不可播标记**：当前 `playable == false` 全部来自酷我
                // 「拿不到播放地址」，此时「需要 VIP」这层信息已经不重要了，两个标记并排
                // 只会让行变挤。`vip` 本身仍是权威值，界面别处照旧会用到。
                if (!playable) {
                    SharedUnplayableBadge(Modifier.padding(start = TaotaoSpacing.xs))
                } else if (song.vip) {
                    SharedVipBadge(Modifier.padding(start = TaotaoSpacing.xs))
                }
            }
            Row(
                modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = normalizedDisplayText(subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (downloaded) {
                    Icon(
                        imageVector = Icons.Default.OfflinePin,
                        contentDescription = "已下载",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = TaotaoSpacing.xxs).size(TaotaoSizes.iconXs),
                    )
                }
            }
            supportingContent?.invoke(this)
        }
        if (durationLabel.isNotBlank()) {
            Text(
                text = durationLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = TaotaoSpacing.xs),
            )
        }
        trailingContent?.invoke(this)
    }
}

/**
 * 行高下限由封面高度加两倍垂直内边距推出。
 *
 * 写成推导式而不是 68.dp：封面尺寸一改，行高会自动跟上，
 * 不会出现「封面变小了但行还是原来的高度」这种看不见的错位。
 */
private val SongRowMinHeight = TaotaoSizes.artworkRow + TaotaoSpacing.listItemVertical * 2

/** 三端统一的 VIP 标识。 */
@Composable
fun SharedVipBadge(modifier: Modifier = Modifier) {
    Text(
        text = "VIP",
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(TaotaoShapes.badge)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.tightVertical),
    )
}

/**
 * 三端统一的「不可播」标识。
 *
 * 用于 `song.playable == false` 的歌：它们在酷我上拿不到播放地址
 * （上游逐首回 `code 20012 歌曲已下线`，正版热门曲居多），但**仍然会被列出来** ——
 * 波点 App 同样列出它们，服务端据此不再过滤。见 `Song.playable`。
 *
 * 用中性色而不是警示色：这不是错误，是版权状态，30 条搜索结果里可能 30 条都带它，
 * 满屏红色会让整页看起来像出故障了。
 */
@Composable
fun SharedUnplayableBadge(modifier: Modifier = Modifier) {
    Text(
        text = "版权不可播",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(TaotaoShapes.badge)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            .padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.tightVertical),
    )
}
