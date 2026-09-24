package com.taotao.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.taotao.music.playerui.theme.TaotaoSpacing

/** 常用定时选项；需要更长时间时可在下方输入 1-1440 分钟。 */
private val SleepTimerOptionsMinutes = listOf(15, 30, 45, 60, 90, 120)

/**
 * 定时播放选择面板。
 *
 * 倒计时由播放服务维护，这个面板只负责发送用户选择并展示服务同步回来的剩余时间。
 */
@Composable
fun SleepTimerDialog(
    remainingMs: Long,
    onSet: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = remainingMs > 0L
    var customMinutes by remember { mutableStateOf("") }
    val customValue = customMinutes.toIntOrNull()
    val customValid = customValue in 1..1_440
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时播放") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.tightVertical),
            ) {
                Text(
                    if (active) {
                        "剩余 ${formatSleepTimerRemaining(remainingMs)}"
                    } else {
                        "仅在播放时计时，暂停会保留剩余时间。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = TaotaoSpacing.xxs),
                )
                SleepTimerOptionsMinutes.forEach { minutes ->
                    TextButton(
                        onClick = { onSet(minutes) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${minutes} 分钟")
                    }
                }
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { value ->
                        if (value.length <= 4 && value.all(Char::isDigit)) customMinutes = value
                    },
                    label = { Text("自定义分钟数（1-1440）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xxs),
                )
                TextButton(
                    onClick = { customValue?.let(onSet) },
                    enabled = customValid,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("设置自定义时长") }
            }
        },
        confirmButton = {
            if (active) {
                TextButton(onClick = onCancelTimer) { Text("关闭定时") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 固定显示为 mm:ss；超过一小时则显示 h:mm:ss，避免长数字挤出设置行。 */
fun formatSleepTimerRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) / 1_000L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
