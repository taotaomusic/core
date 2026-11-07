package com.taotao.music.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taotao.music.data.diagnostics.PowerDiagnostics
import com.taotao.music.data.diagnostics.PowerSample
import com.taotao.music.data.diagnostics.PowerSnapshot
import com.taotao.music.data.diagnostics.PowerSummary
import com.taotao.music.data.diagnostics.summarizePowerSamples
import com.taotao.music.data.im.ImConnectionInfo
import com.taotao.music.data.im.ImConnectionState
import com.taotao.music.model.Song
import com.taotao.music.playerui.SharedBackButton
import com.taotao.music.playerui.SharedCard
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内耗电诊断页。
 *
 * 系统的「电池用量」只对系统设置开放，第三方应用读不到全机耗电排行；本页改为记录
 * 桃桃音乐自己的电量、前后台、亮屏和播放状态，让「后台是否异常耗电」有可核对的数据。
 */
@Composable
fun PowerDiagnosticsPage(
    diagnostics: PowerDiagnostics,
    isPlaying: Boolean,
    currentSong: Song?,
    imConnection: ImConnectionInfo,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(diagnostics.snapshot()) }
    var samples by remember { mutableStateOf(emptyList<PowerSample>()) }
    var summary by remember { mutableStateOf<PowerSummary?>(null) }
    var historyLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(diagnostics) {
        while (true) {
            val next = withContext(Dispatchers.Default) { diagnostics.snapshot() }
            snapshot = next
            delay(SNAPSHOT_REFRESH_MS)
        }
    }

    LaunchedEffect(diagnostics, refreshKey) {
        historyLoading = true
        val loaded = withContext(Dispatchers.IO) { diagnostics.history(HISTORY_HOURS) }
        samples = loaded
        summary = withContext(Dispatchers.Default) { summarizePowerSamples(loaded) }
        historyLoading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = TaotaoSpacing.screenHorizontal),
    ) {
        SharedSectionHeader(
            title = "耗电诊断",
            subtitle = "只记录本机桃桃音乐，不上传",
            level = SharedSectionLevel.PAGE,
            leading = { SharedBackButton(onBack) },
        )

        SharedCard(title = "电池", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            DiagnosticRow("电量", snapshot.batteryPercent.takeIf { it >= 0 }?.let { "$it%" } ?: "未知")
            DiagnosticRow("状态", "${snapshot.statusLabel} · ${snapshot.pluggedLabel}")
            DiagnosticRow("健康度", snapshot.healthLabel)
            DiagnosticRow("温度", snapshot.temperatureC?.let { String.format(Locale.CHINA, "%.1f °C", it) } ?: "未知")
            DiagnosticRow("电压", snapshot.voltageMv?.let { "$it mV" } ?: "未知")
            DiagnosticRow("电池技术", snapshot.technology ?: "未知")
        }

        SharedCard(title = "电源管理", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            DiagnosticRow("省电模式", snapshot.powerSaveMode.label())
            DiagnosticRow("屏幕", if (snapshot.screenOn) "亮屏" else "熄屏")
            DiagnosticRow("Doze 深度休眠", snapshot.deviceIdleMode.label())
            DiagnosticRow("电池优化白名单", if (snapshot.ignoringBatteryOptimizations) "已加入" else "未加入")
            DiagnosticRow("后台运行限制", if (snapshot.backgroundRestricted) "受限" else "正常")
            DiagnosticRow("应用待机分组", snapshot.standbyBucket)
            DiagnosticRow("低内存设备", snapshot.lowRamDevice.label())
        }

        SharedCard(title = "应用资源", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            DiagnosticRow("应用运行时长", formatDuration(snapshot.appUptimeMs))
            DiagnosticRow("CPU 时间", formatDuration(snapshot.cpuTimeMs))
            DiagnosticRow("内存 PSS", snapshot.pssMb?.let { "$it MB" } ?: "不可用")
            DiagnosticRow("系统可用内存", "${snapshot.availableMemMb} MB")
            DiagnosticRow("流量接收", formatBytes(snapshot.rxBytes))
            DiagnosticRow("流量发送", formatBytes(snapshot.txBytes))
            DiagnosticRow("线程数", snapshot.threadCount.toString())
            DiagnosticRow("版本", snapshot.versionName)
            DiagnosticRow("设备", "${snapshot.deviceLabel} · ${snapshot.androidLabel}")
        }

        SharedCard(title = "播放与连接", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            DiagnosticRow("播放状态", if (isPlaying) "正在播放" else "未播放")
            DiagnosticRow(
                "当前歌曲",
                currentSong?.let { "${it.title} · ${it.artist}" } ?: "无",
            )
            DiagnosticRow("IM 连接", imConnection.state.label())
        }

        SharedCard(title = "最近 $HISTORY_HOURS 小时", modifier = Modifier.padding(top = TaotaoSpacing.sm)) {
            if (historyLoading) {
                Row(
                    Modifier.fillMaxWidth().padding(TaotaoSpacing.md),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(TaotaoSpacing.md), strokeWidth = 2.dp)
                }
            } else {
                PowerSummaryContent(summary)
                if (samples.isEmpty()) {
                    Text(
                        "还没有采样。系统电量、亮屏、前后台或播放状态变化后会自动记录；先正常使用一段时间再回来看。应用被系统彻底杀掉期间不会有样本。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs),
                    )
                } else {
                    Column(Modifier.padding(top = TaotaoSpacing.xs)) {
                        samples.takeLast(MAX_VISIBLE_SAMPLES).asReversed().forEach { sample ->
                            SampleRow(sample)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { refreshKey++ }) { Text("刷新") }
                TextButton(onClick = {
                    val report = buildReport(snapshot, summary, samples, isPlaying, currentSong, imConnection)
                    scope.launch {
                        runCatching {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("耗电诊断", report)),
                            )
                        }.onSuccess {
                            onMessage("耗电诊断已复制")
                        }.onFailure {
                            onMessage("复制失败，请重试")
                        }
                    }
                }) { Text("复制诊断") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    diagnostics.clear()
                    samples = emptyList()
                    summary = null
                    onMessage("耗电诊断历史已清空")
                }) { Text("清空历史") }
            }
        }

        Text(
            "说明：Android 不向普通应用开放其它应用的耗电排行，本页只能记录桃桃音乐自身状态；" +
                "采样由电量、亮屏、前后台和播放状态变化触发，不做定时轮询；应用被系统杀掉期间不会有样本。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = TaotaoSpacing.sm),
        )
        Spacer(Modifier.height(TaotaoSpacing.xl))
    }
}

@Composable
private fun PowerSummaryContent(summary: PowerSummary?) {
    if (summary == null) {
        Text(
            "样本不足：至少需要两次电量变化才能计算趋势。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs),
        )
        return
    }
    Column(Modifier.padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs)) {
        Text(
            "${formatDateTime(summary.fromMillis)} 至 ${formatDateTime(summary.toMillis)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "电量 ${summary.startPercent}% → ${summary.endPercent}% · 区间放电 ${summary.dischargePercent}% · " +
                "样本 ${summary.sampleCount}",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = TaotaoSpacing.xxs),
        )
        Text(
            "前台 ${formatDuration(summary.foregroundMs)} · 后台 ${formatDuration(summary.backgroundMs)} · " +
                "播放 ${formatDuration(summary.playbackMs)} · 亮屏 ${formatDuration(summary.screenOnMs)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = TaotaoSpacing.xxs),
        )
        Text(
            "估算耗电：前台 ${formatRate(summary.foregroundDrainPerHour)} · " +
                "后台 ${formatRate(summary.backgroundDrainPerHour)} · " +
                "播放 ${formatRate(summary.playbackDrainPerHour)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = TaotaoSpacing.xxs),
        )
    }
}

@Composable
private fun DiagnosticRow(
    title: String,
    value: String,
    detail: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                )
            }
        }
        Text(
            value,
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = TaotaoSpacing.sm),
        )
    }
}

@Composable
private fun SampleRow(sample: PowerSample) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatSampleTime(sample.atMillis),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "${sample.batteryPercent}%",
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = TaotaoSpacing.sm),
        )
        Text(
            buildString {
                if (sample.charging) append("充电") else append("放电")
                if (sample.screenOn) append(" · 亮屏") else append(" · 熄屏")
                append(if (sample.appForeground) " · 前台" else " · 后台")
                if (sample.playing) append(" · 播放")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f).padding(start = TaotaoSpacing.sm),
            maxLines = 1,
        )
    }
}

private fun buildReport(
    snapshot: PowerSnapshot,
    summary: PowerSummary?,
    samples: List<PowerSample>,
    isPlaying: Boolean,
    currentSong: Song?,
    imConnection: ImConnectionInfo,
): String = buildString {
    appendLine("桃桃音乐耗电诊断")
    appendLine("导出时间：${formatDateTime(System.currentTimeMillis())}")
    appendLine("版本：${snapshot.versionName}")
    appendLine("设备：${snapshot.deviceLabel} · ${snapshot.androidLabel}")
    appendLine()
    appendLine("[当前状态]")
    appendLine("电量：${snapshot.batteryPercent}% · ${snapshot.statusLabel} · ${snapshot.pluggedLabel}")
    appendLine(
        "健康度：${snapshot.healthLabel} · 温度：" +
            (snapshot.temperatureC?.let { String.format(Locale.CHINA, "%.1f °C", it) } ?: "未知") +
            " · 电压：${snapshot.voltageMv?.let { "$it mV" } ?: "未知"}",
    )
    appendLine("省电模式：${snapshot.powerSaveMode.label()} · 屏幕：${if (snapshot.screenOn) "亮屏" else "熄屏"}")
    appendLine("Doze：${snapshot.deviceIdleMode.label()} · 后台限制：${if (snapshot.backgroundRestricted) "受限" else "正常"}")
    appendLine("电池优化白名单：${if (snapshot.ignoringBatteryOptimizations) "已加入" else "未加入"} · 待机分组：${snapshot.standbyBucket}")
    appendLine("运行时长：${formatDuration(snapshot.appUptimeMs)} · CPU：${formatDuration(snapshot.cpuTimeMs)}")
    appendLine("PSS：${snapshot.pssMb?.let { "$it MB" } ?: "不可用"} · 可用内存：${snapshot.availableMemMb} MB · 线程：${snapshot.threadCount}")
    appendLine("流量：接收 ${formatBytes(snapshot.rxBytes)} · 发送 ${formatBytes(snapshot.txBytes)}")
    appendLine("播放：${if (isPlaying) "正在播放" else "未播放"} · 歌曲：${currentSong?.let { "${it.title} · ${it.artist}" } ?: "无"}")
    appendLine("IM：${imConnection.state.label()}")
    appendLine()
    appendLine("[最近 $HISTORY_HOURS 小时]")
    if (summary == null) {
        appendLine("样本不足")
    } else {
        appendLine("${formatDateTime(summary.fromMillis)} 至 ${formatDateTime(summary.toMillis)}")
        appendLine("电量 ${summary.startPercent}% → ${summary.endPercent}% · 放电 ${summary.dischargePercent}% · 样本 ${summary.sampleCount}")
        appendLine("前台 ${formatDuration(summary.foregroundMs)} · 后台 ${formatDuration(summary.backgroundMs)} · 播放 ${formatDuration(summary.playbackMs)} · 亮屏 ${formatDuration(summary.screenOnMs)}")
        appendLine("估算耗电：前台 ${formatRate(summary.foregroundDrainPerHour)} · 后台 ${formatRate(summary.backgroundDrainPerHour)} · 播放 ${formatRate(summary.playbackDrainPerHour)}")
    }
    appendLine()
    appendLine("[采样明细]")
    if (samples.isEmpty()) {
        appendLine("无")
    } else {
        samples.takeLast(MAX_REPORT_SAMPLES).forEach { sample ->
            appendLine(
                "${formatDateTime(sample.atMillis)}  ${sample.batteryPercent}%  " +
                    "${if (sample.charging) "充电" else "放电"}  " +
                    "${if (sample.screenOn) "亮屏" else "熄屏"}  " +
                    "${if (sample.appForeground) "前台" else "后台"}  " +
                    if (sample.playing) "播放" else "未播放",
            )
        }
    }
}

private fun Boolean.label(): String = if (this) "是" else "否"

private fun ImConnectionState.label(): String = when (this) {
    ImConnectionState.IDLE -> "未连接"
    ImConnectionState.CONNECTING -> "连接中"
    ImConnectionState.CONNECTED -> "已连接"
    ImConnectionState.NO_NETWORK -> "无网络"
    ImConnectionState.FAILED -> "连接失败"
}

private fun formatRate(rate: Float?): String =
    rate?.let { String.format(Locale.CHINA, "%.1f%%/小时", it) } ?: "样本不足"

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000L
    if (totalMinutes <= 0L) return "不足 1 分钟"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours} 小时 ${minutes} 分" else "${minutes} 分钟"
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "不可用"
    if (bytes < 1024L) return "0 KB"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.CHINA, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.CHINA, "%.1f MB", mb)
    return String.format(Locale.CHINA, "%.2f GB", mb / 1024.0)
}

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatSampleTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

private const val HISTORY_HOURS = 24
private const val MAX_VISIBLE_SAMPLES = 40
private const val MAX_REPORT_SAMPLES = 200
private const val SNAPSHOT_REFRESH_MS = 2_000L
