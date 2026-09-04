package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import com.taotao.music.model.AudioQuality
import com.taotao.music.model.labelOfQuality

/**
 * 一个可选档位。
 *
 * [sizeBytes] 为 null 表示未知（没查到 `/song/info`，或用的是静态档位表）；
 * [available] 为 false 时置灰不可选 —— 这首歌没有这一档，选了也只会静默降级。
 */
data class QualityChoice(
    val value: Int,
    val label: String,
    val sizeBytes: Long? = null,
    val available: Boolean = true,
)

/** 由静态档位表生成的选项，用于设置页这种与具体歌曲无关的场景。 */
fun staticQualityChoices(): List<QualityChoice> =
    AudioQuality.entries.map { QualityChoice(it.value, it.label) }

/**
 * 音质选择面板。
 *
 * 播放页临时切档、下载前选档、设置页改默认值共用同一个面板：
 * 三处的差别只是候选档位从哪来，以及确认后把值写去哪。
 *
 * [loading] 为真时显示进度 —— 与具体歌曲相关的档位表要先查一次 `/song/info`。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QualitySheet(
    title: String,
    choices: List<QualityChoice>,
    selected: Int,
    loading: Boolean = false,
    note: String? = null,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (!note.isNullOrBlank()) {
                Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary)
                    Text("正在查可用音质…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                }
                return@Column
            }
            choices.forEach { choice ->
                QualityRow(choice, choice.value == selected) { if (choice.available) onPick(choice.value) }
            }
        }
    }
}

@Composable
private fun QualityRow(choice: QualityChoice, checked: Boolean, onClick: () -> Unit) {
    val targetTint = when {
        !choice.available -> MaterialTheme.colorScheme.onSurfaceVariant
        checked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = taotaoTween(AnimationDurations.MICRO),
        label = "音质选项着色",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = taotaoTween(AnimationDurations.MICRO),
        label = "音质选项背景",
    )
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(enabled = choice.available, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                choice.label.ifBlank { labelOfQuality(choice.value) },
                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
                color = tint,
            )
            val detail = when {
                !choice.available -> "这首歌没有这一档"
                choice.sizeBytes != null && choice.sizeBytes > 0 -> formatBytes(choice.sizeBytes)
                else -> null
            }
            if (detail != null) {
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (checked) Icon(Icons.Default.Check, "已选择", tint = MaterialTheme.colorScheme.primary)
    }
}

/** 人类可读的体积。下载前提示流量用，一位小数足够。 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "${(bytes * 10 / (1024L * 1024L * 1024L)) / 10.0} GB"
    bytes >= 1024L * 1024L -> "${(bytes * 10 / (1024L * 1024L)) / 10.0} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

/**
 * 当前音质的小徽标。
 *
 * [local] 为真时标明这份是本地文件的实际音质；[onClick] 为 null 表示不可点
 * （本地文件换档要重新下载，不能就地切）。
 */
@Composable
fun QualityChip(quality: Int, modifier: Modifier = Modifier, local: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            if (local) "已下载 · ${labelOfQuality(quality)}" else labelOfQuality(quality),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
