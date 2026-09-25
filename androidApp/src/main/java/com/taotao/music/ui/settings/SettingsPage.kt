package com.taotao.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.taotao.music.data.AppearanceMode
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.model.AudioQuality
import com.taotao.music.playerui.SharedBackButton
import com.taotao.music.playerui.SharedCard
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.ui.player.formatSleepTimerRemaining

/**
 * 设置页。
 *
 * 播放与下载的音质分开设置：流量敏感的是播放，下载一次的体积反而愿意换更好的音质，
 * 所以两者的默认值本来就不该一样。
 *
 * 标题与卡片统一走 `player-ui` 的 [SharedSectionHeader] / [SharedCard]，
 * 间距与圆角走 `TaotaoSpacing` / `TaotaoShapes`。
 */
@Composable
fun SettingsPage(
    playbackQuality: AudioQuality,
    downloadQuality: AudioQuality,
    sleepTimerRemainingMs: Long,
    sleepTimerWaitingSongEnd: Boolean = false,
    appearance: AppearanceMode,
    profile: TencentMusicApi.UserProfile?,
    profileLoading: Boolean,
    onOpenProfile: () -> Unit,
    onPickPlaybackQuality: () -> Unit,
    onPickDownloadQuality: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onPickAppearance: (AppearanceMode) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = TaotaoSpacing.screenHorizontal),
    ) {
        // 与收藏、历史等列表页共用同一个页面标题组件，此前这里写的是 20sp、列表页是 26sp。
        SharedSectionHeader(
            title = "设置",
            level = SharedSectionLevel.PAGE,
            leading = { SharedBackButton(onBack) },
        )

        SharedCard(title = "账号", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            SettingRow(
                title = "个人资料",
                value = if (profileLoading) "读取中" else "管理",
                description = profile?.let { "${it.nickname} · ${it.email ?: "未绑定邮箱"}" } ?: "修改头像、昵称和邮箱",
                onClick = onOpenProfile,
                leadingIcon = { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary) },
            )
        }

        SharedCard(title = "音质", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxs),
                modifier = Modifier.padding(bottom = TaotaoSpacing.xs),
            ) {
                SettingRow(
                    title = "播放音质",
                    value = playbackQuality.label,
                    description = "在线播放使用的音质。无损一首约 50MB，用移动数据时建议选 HQ 或标准。",
                    onClick = onPickPlaybackQuality,
                )
                SettingRow(
                    title = "下载音质",
                    value = downloadQuality.label,
                    description = "离线下载使用的音质。下载只花一次流量，可以比播放选得更高。",
                    onClick = onPickDownloadQuality,
                )
            }
        }

        SharedCard(title = "播放", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            SettingRow(
                title = "定时关闭",
                value = when {
                    sleepTimerWaitingSongEnd -> "本首结束后停止"
                    sleepTimerRemainingMs > 0L -> formatSleepTimerRemaining(sleepTimerRemainingMs)
                    else -> "关闭"
                },
                description = "播放达到设定时长后自动停止；暂停会保留剩余时间，切歌不会重置。",
                onClick = onOpenSleepTimer,
            )
        }

        Text(
            "选中的音质若某首歌没有，服务端会自动降到最接近的可用档位，播放页会显示实际音质。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = TaotaoSpacing.sm),
        )

        SharedCard(title = "外观", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            Column(Modifier.padding(bottom = TaotaoSpacing.xs)) {
                AppearanceMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(TaotaoShapes.button)
                            .clickable { onPickAppearance(mode) }
                            .padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(mode.label, modifier = Modifier.weight(1f))
                        if (mode == appearance) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(TaotaoSpacing.xl))
    }
}

@Composable
private fun SettingRow(
    title: String,
    value: String,
    description: String,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(TaotaoShapes.button)
            .clickable(onClick = onClick)
            .padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            Box(Modifier.padding(end = TaotaoSpacing.sm)) { it() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = TaotaoSpacing.xxs),
            )
        }
        Box(
            Modifier.clip(TaotaoShapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.xxs),
        ) {
            Text(
                value,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
