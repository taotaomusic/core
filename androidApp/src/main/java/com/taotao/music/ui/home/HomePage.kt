package com.taotao.music.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taotao.music.data.GreetingFormatter
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.common.AlbumArt
import java.util.Calendar

/** 首页促销卡的高度；只属于这一屏的布局常量，不进设计刻度。 */
private val HomePromoCardHeight = 164.dp

@Composable
internal fun HomeHeader(userName: String, onOpenAnnouncements: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("${timeGreeting()}，$userName", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text("听点喜欢的", style = MaterialTheme.typography.headlineMedium)
        }
        IconButton(onClick = onOpenAnnouncements) { Icon(Icons.Default.NotificationsNone, "公告") }
    }
    Spacer(Modifier.height(TaotaoSpacing.lg))
}

/** 只依赖设备本地时区，离线时也能给出符合当前时段的问候。 */
internal fun timeGreeting(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String =
    GreetingFormatter.greetingForHour(hour)

/** 首页只占一行展示最新或置顶公告，详情放进弹层，避免正文挤占搜索与推荐内容。 */
@Composable
internal fun AnnouncementPreview(announcement: TencentMusicApi.Announcement, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.sm, bottom = TaotaoSpacing.xxs)
            .clip(TaotaoShapes.medium).background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick).padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Campaign, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(TaotaoSizes.iconSm))
        Text(
            text = if (announcement.pinned) "置顶 · ${announcement.title}" else announcement.title,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = TaotaoSpacing.xs),
        )
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(TaotaoSizes.iconSm))
    }
}

@Composable
internal fun RecommendationCard() {
    Row(
        Modifier.fillMaxWidth().height(HomePromoCardHeight).clip(TaotaoShapes.card)
            .background(MaterialTheme.colorScheme.primaryContainer).padding(TaotaoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("专属歌单", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(
                "给今天的你\n一点好心情",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "20 首 · 精选推荐",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = TaotaoSpacing.xs),
            )
        }
        AlbumArt(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f), TaotaoSizes.artworkGrid)
    }
}
