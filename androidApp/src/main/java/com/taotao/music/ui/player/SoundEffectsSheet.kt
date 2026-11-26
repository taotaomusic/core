package com.taotao.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taotao.music.model.SoundEffectPreset
import com.taotao.music.model.SoundEffectScene
import com.taotao.music.model.SoundEffectFrequenciesHz
import com.taotao.music.model.SoundEffectsSettings
import com.taotao.music.player.SoundEffectsStatus
import com.taotao.music.playerui.theme.TaotaoMiuixTheme
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSpacing
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** 播放页与设置页复用同一面板；偏好由宿主持有，关闭面板不撤销听感调整。 */
@Composable
fun SoundEffectsSheet(
    settings: SoundEffectsSettings,
    status: SoundEffectsStatus,
    onChange: (SoundEffectsSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val equalizerEnabled = settings.enabled && (!status.sessionReady || status.equalizerAvailable)
    val bassEnabled = settings.enabled && (!status.sessionReady || status.bassAvailable)
    TaotaoMiuixTheme {
        WindowBottomSheet(show = true, title = "播放音效", onDismissRequest = onDismiss) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.76f).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = TaotaoSpacing.md),
                verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.md),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(TaotaoShapes.card)
                        .background(colors.primaryContainer.copy(alpha = 0.4f))
                        .padding(TaotaoSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = colors.primary)
                    Spacer(Modifier.width(TaotaoSpacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text("找到喜欢的听感", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !settings.enabled -> "音效已关闭 · 原始输出"
                                !status.sessionReady -> "设置已保存 · 播放音乐后生效"
                                !status.equalizerAvailable && !status.bassAvailable -> "当前设备音效不可用 · 设置已保留"
                                !status.equalizerAvailable -> "低音增强可用 · 均衡器暂不可用"
                                else -> "${settings.preset.label} · 实时生效"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                        )
                    }
                    MiuixSwitch(
                        checked = settings.enabled,
                        onCheckedChange = { onChange(settings.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = "启用播放音效" },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                    Text("听感预设", fontWeight = FontWeight.SemiBold)
                    SoundEffectPreset.entries.filter { it != SoundEffectPreset.CUSTOM }.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                            row.forEach { preset ->
                                SoundEffectOption(
                                    label = preset.label,
                                    description = preset.description,
                                    selected = settings.preset == preset,
                                    enabled = equalizerEnabled,
                                    onClick = { onChange(settings.copy(preset = preset)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    SoundEffectOption(
                        label = SoundEffectPreset.CUSTOM.label,
                        description = SoundEffectPreset.CUSTOM.description,
                        selected = settings.preset == SoundEffectPreset.CUSTOM,
                        enabled = equalizerEnabled,
                        onClick = { onChange(settings.copy(preset = SoundEffectPreset.CUSTOM)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("音效场景", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = TaotaoSpacing.xs))
                    SoundEffectScene.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                            row.forEach { scene ->
                                SoundEffectOption(
                                    label = scene.label,
                                    description = scene.description,
                                    selected = scene.matches(settings),
                                    enabled = true,
                                    onClick = { onChange(scene.applyTo(settings)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                Column(
                    Modifier.fillMaxWidth().clip(TaotaoShapes.card)
                        .background(colors.surface).padding(TaotaoSpacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("十段均衡器", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(
                            onClick = { onChange(SoundEffectsSettings(enabled = settings.enabled)) },
                        ) { Text("重置音效") }
                    }
                    Text(
                        if (status.sessionReady && !status.equalizerAvailable) {
                            "当前音频设备不支持均衡器，或音效正被其他应用占用。"
                        } else {
                            "拖动即为自定义，切换预设仍会保留你的曲线。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    EqualizerCurve(settings.bandGains, equalizerEnabled)
                    val labels = SoundEffectFrequenciesHz.map { frequency ->
                        if (frequency >= 1_000) "${frequency / 1_000} kHz" else "$frequency Hz"
                    }
                    val descriptions = listOf("超低频", "低频", "中低频", "低中频", "中频", "中频", "中高频", "高频", "超高频", "空气感")
                    settings.bandGains.forEachIndexed { index, gain ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(labels[index], Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = gain / 100f,
                                onValueChange = { onChange(settings.withBandGain(index, it.roundToInt() * 100)) },
                                valueRange = -6f..6f,
                                steps = 11,
                                enabled = equalizerEnabled,
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = "${descriptions[index]}均衡器，${labels[index]}"
                                },
                            )
                            Text(
                                "${if (gain > 0) "+" else ""}${gain / 100} dB",
                                Modifier.width(48.dp),
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.End,
                                color = if (equalizerEnabled) colors.primary else colors.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = TaotaoSpacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("低音增强", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("${settings.bassStrength / 10}%", style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = settings.bassStrength / 10f,
                        onValueChange = { onChange(settings.copy(bassStrength = it.roundToInt() * 10)) },
                        valueRange = 0f..100f,
                        enabled = bassEnabled,
                        modifier = Modifier.semantics { contentDescription = "低音增强强度，百分比" },
                    )
                    Text(
                        if (status.sessionReady && !status.bassAvailable) {
                            "当前设备不支持可调低音增强，或音效正被其他应用占用。"
                        } else {
                            "独立于均衡器；0% 关闭增强，耳机聆听建议从低档开始。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(vertical = TaotaoSpacing.sm))
                    Text("空间与混响", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (status.sessionReady && !status.spatialAvailable) {
                            "声场展宽需要双声道 PCM；混响在单声道和立体声均可使用。"
                        } else {
                            "声场展宽保留中置人声；轻混响营造自然空间感。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    listOf(
                        Triple("立体声展宽", settings.spatialStrength, status.spatialAvailable),
                        Triple("空间混响", settings.reverbStrength, status.reverbAvailable),
                    ).forEachIndexed { index, (label, strength, supported) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = strength / 10f,
                                onValueChange = { value ->
                                    val amount = value.roundToInt() * 10
                                    onChange(
                                        if (index == 0) settings.copy(spatialStrength = amount)
                                        else settings.copy(reverbStrength = amount)
                                    )
                                },
                                valueRange = 0f..100f,
                                enabled = settings.enabled && (!status.sessionReady || supported),
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = "${label}强度，百分比"
                                },
                            )
                            Text("${strength / 10}%", Modifier.width(42.dp), textAlign = TextAlign.End,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    "仅调整本机音乐播放，不改变音源音质或下载文件。增益过高可能产生失真，请适度调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TaotaoSpacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun SoundEffectOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.clip(TaotaoShapes.button)
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.5f) else colors.surface)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant, TaotaoShapes.button)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontWeight = FontWeight.Medium,
                color = if (enabled && selected) colors.primary else colors.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
            )
            Text(description, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (selected) {
            Icon(Icons.Default.Check, null, tint = colors.primary, modifier = Modifier.size(18.dp))
        }
    }
}

/** 静态曲线只在参数变化时重绘，不制造后台动画或音频分析开销。 */
@Composable
private fun EqualizerCurve(gains: List<Int>, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val accent = if (enabled) colors.primary else colors.outline
    Canvas(Modifier.fillMaxWidth().height(88.dp).padding(vertical = TaotaoSpacing.sm, horizontal = 6.dp)) {
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            drawLine(colors.outlineVariant, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
        }
        val points = gains.mapIndexed { index, gain ->
            Offset(size.width * index / (gains.size - 1), size.height * (0.5f - gain / 1_200f))
        }
        val curve = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.zipWithNext().forEach { (start, end) ->
                val middle = (start.x + end.x) / 2f
                cubicTo(middle, start.y, middle, end.y, end.x, end.y)
            }
        }
        drawPath(curve, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        points.forEach { point -> drawCircle(accent, 3.dp.toPx(), point) }
    }
}
