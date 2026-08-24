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
import com.taotao.music.hotfix.HotfixDiagnostics
import com.taotao.music.model.AudioQuality

/**
 * 设置页。
 *
 * 播放与下载的音质分开设置：流量敏感的是播放，下载一次的体积反而愿意换更好的音质，
 * 所以两者的默认值本来就不该一样。
 *
 * 末尾那张「热修复」卡片是排查用的：补丁没生效时，四种原因（版本号不匹配、下载失败、
 * 校验不符、加载抛异常）在界面上原本长得一模一样，而测试机连不上 adb 拿不到 logcat。
 */
@Composable
fun SettingsPage(
    playbackQuality: AudioQuality,
    downloadQuality: AudioQuality,
    appearance: AppearanceMode,
    hotfix: HotfixDiagnostics,
    onPickPlaybackQuality: () -> Unit,
    onPickDownloadQuality: () -> Unit,
    onPickAppearance: (AppearanceMode) -> Unit,
    onRetryHotfix: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
        }

        CardWithTitle("音质", Modifier.fillMaxWidth().padding(top = 12.dp)) {
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

        Text(
            "选中的音质若某首歌没有，服务端会自动降到最接近的可用档位，播放页会显示实际音质。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 14.dp),
        )

        CardWithTitle("外观", Modifier.fillMaxWidth().padding(top = 14.dp)) {
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
                        if (mode == appearance) Icon(Icons.Default.Check, "已选择", tint = TaotaoCoral)
                    }
                }
            }
        }
        CardWithTitle("热修复", Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                DiagnosticRow("补丁状态", hotfix.summary)
                DiagnosticRow("本机版本号", hotfix.installedVersionCode.toString())
                if (hotfix.targetVersionCode != 0L) {
                    DiagnosticRow("补丁目标版本", hotfix.targetVersionCode.toString())
                }
                hotfix.lastOutcome?.let { outcome ->
                    Text(
                        outcome,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (hotfix.lastOutcome == null) {
                    Text(
                        "还没有收到过补丁。补丁要求本机版本号与补丁的目标版本严格相等。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // 只在真的卡住时才显示 —— 平时不该引导用户点这个。
                if (hotfix.isBlockedByFailure) {
                    Box(
                        Modifier.padding(top = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TaotaoCoral.copy(alpha = 0.12f))
                            .clickable(onClick = onRetryHotfix)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text("清除失败记录并重试", color = TaotaoCoral, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingRow(title: String, value: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(TaotaoCoral.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(value, color = TaotaoCoral, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
