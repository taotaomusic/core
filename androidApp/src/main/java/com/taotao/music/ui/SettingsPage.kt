package com.taotao.music.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.unit.sp
import com.taotao.music.data.AppearanceMode
import com.taotao.music.model.AudioQuality
import com.taotao.music.data.TencentMusicApi

/**
 * 设置页。
 *
 * 播放与下载的音质分开设置：流量敏感的是播放，下载一次的体积反而愿意换更好的音质，
 * 所以两者的默认值本来就不该一样。
 *
 */
@Composable
fun SettingsPage(
    playbackQuality: AudioQuality,
    downloadQuality: AudioQuality,
    sleepTimerRemainingMs: Long,
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }

        CardWithTitle("账号", Modifier.fillMaxWidth().padding(top = 12.dp), titleColor = MaterialTheme.colorScheme.primary) {
            SettingRow(
                title = "个人资料",
                value = if (profileLoading) "读取中" else "管理",
                description = profile?.let { "${it.nickname} · ${it.email ?: "未绑定邮箱"}" } ?: "修改头像、昵称和邮箱",
                onClick = onOpenProfile,
                leadingIcon = { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary) },
            )
        }

        CardWithTitle(
            "音质",
            Modifier.fillMaxWidth().padding(top = 12.dp),
            titleColor = MaterialTheme.colorScheme.primary,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(bottom = 8.dp)) {
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

        CardWithTitle(
            "播放",
            Modifier.fillMaxWidth().padding(top = 12.dp),
            titleColor = MaterialTheme.colorScheme.primary,
        ) {
            SettingRow(
                title = "定时播放",
                value = if (sleepTimerRemainingMs > 0L) {
                    formatSleepTimerRemaining(sleepTimerRemainingMs)
                } else {
                    "关闭"
                },
                description = "播放达到设定时长后自动停止；暂停会保留剩余时间，切歌不会重置。",
                onClick = onOpenSleepTimer,
            )
        }

        Text(
            "选中的音质若某首歌没有，服务端会自动降到最接近的可用档位，播放页会显示实际音质。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 14.dp),
        )

        CardWithTitle(
            "外观",
            Modifier.fillMaxWidth().padding(top = 14.dp),
            titleColor = MaterialTheme.colorScheme.primary,
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                AppearanceMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPickAppearance(mode) }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(mode.label, modifier = Modifier.weight(1f))
                        if (mode == appearance) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            Box(Modifier.padding(end = 12.dp)) { it() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
