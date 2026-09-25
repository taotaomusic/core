package com.taotao.music.ui.player

import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.TaotaoCoral
import com.taotao.music.ui.theme.taotaoSpring
import com.taotao.music.ui.theme.taotaoTween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoTypeScale

/** 快捷时长圆钮，与设计稿一致的 10-90 分钟六档。 */
private val SleepTimerQuickMinutes = listOf(10, 20, 30, 45, 60, 90)

/**
 * 定时关闭选择面板（底部弹层）。
 *
 * 倒计时由播放服务维护，这个面板只负责发送用户选择并展示服务同步回来的状态：
 * - 「上次定时」开关：打开按上次时长重新计时，关闭取消定时；
 * - 圆形快捷键直接按对应分钟数开始倒计时；
 * - 「自定义」展开输入 1-1440 分钟；
 * - 「播完整首歌再停止播放」只切换到期行为，不影响正在进行的倒计时。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    remainingMs: Long,
    waitingSongEnd: Boolean,
    lastMinutes: Int,
    waitForSongEnd: Boolean,
    onSet: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onToggleWaitForSongEnd: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val active = remainingMs > 0L
    var customExpanded by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableStateOf("") }
    val customValue = customMinutes.toIntOrNull()
    val customValid = customValue in 1..1_440
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TaotaoSpacing.screenHorizontal)
                .padding(bottom = TaotaoSpacing.xl),
        ) {
            Text("定时关闭", style = TaotaoTypeScale.sectionTitle, fontWeight = FontWeight.Bold)
            if (waitingSongEnd) {
                Text(
                    "定时已到，本歌唱完后停止播放",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                )
            }
            Column(
                Modifier.fillMaxWidth()
                    .padding(top = TaotaoSpacing.md)
                    .clip(TaotaoShapes.card)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = TaotaoSpacing.md, vertical = TaotaoSpacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (active) "剩余" else "上次定时",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = TaotaoSpacing.xs)
                            .size(TaotaoSizes.iconSm),
                    )
                    Text(
                        if (active) formatSleepTimerRemaining(remainingMs) else formatTimerDuration(lastMinutes),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = active,
                        onCheckedChange = { on -> if (on) onSet(lastMinutes) else onCancelTimer() },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = TaotaoSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
                ) {
                    SleepTimerQuickMinutes.forEach { minutes ->
                        val selected = active && lastMinutes == minutes
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable { onSet(minutes) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$minutes",
                                fontWeight = FontWeight.Bold,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
                HorizontalDivider(
                    Modifier.padding(top = TaotaoSpacing.md),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
                if (!customExpanded) {
                    Text(
                        "自定义",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { customExpanded = true }
                            .padding(vertical = TaotaoSpacing.sm),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(top = TaotaoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = { value ->
                                if (value.length <= 4 && value.all(Char::isDigit)) customMinutes = value
                            },
                            label = { Text("分钟（1-1440）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { customValue?.let(onSet) },
                            enabled = customValid,
                            modifier = Modifier.padding(start = TaotaoSpacing.xs),
                        ) { Text("确定") }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggleWaitForSongEnd(!waitForSongEnd) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckCircleBadge(checked = waitForSongEnd)
                Text(
                    "播完整首歌再停止播放",
                    modifier = Modifier.padding(start = TaotaoSpacing.xs),
                )
            }
        }
    }
}

/** 「上次定时」的展示格式：整分时长显示为 分:秒（90 分钟 -> 90:00）。 */
private fun formatTimerDuration(minutes: Int): String = "%d:00".format(minutes.coerceAtLeast(0))

/** 勾选徽标的外径与对勾尺寸；触达面积由整行 clickable 承担，不靠徽标自身。 */
private val CheckCircleBadgeSize = 22.dp
private val CheckCircleIconSize = 14.dp

/**
 * 「播完整首歌再停止」的圆形勾选徽标。
 *
 * 不用默认 Checkbox：方框加对勾描边动画在这一排里太重、太像系统控件。
 * 选中是珊瑚实心圆加白色对勾弹入，未选中是空心圆环；着色走 tween、
 * 对勾走弹簧，与收藏按钮同一套手感；系统关闭动画时直接切换不播补间。
 */
@Composable
private fun CheckCircleBadge(checked: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val checkScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else taotaoSpring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "播完再停对勾",
    )
    val fillColor by animateColorAsState(
        targetValue = if (checked) TaotaoCoral else Color.Transparent,
        animationSpec = taotaoTween(AnimationDurations.MICRO),
        label = "播完再停底色",
    )
    val ringColor by animateColorAsState(
        targetValue = if (checked) TaotaoCoral else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        animationSpec = taotaoTween(AnimationDurations.MICRO),
        label = "播完再停圆环",
    )
    Box(
        modifier
            .size(CheckCircleBadgeSize)
            .clip(CircleShape)
            .background(fillColor)
            .border(1.5.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(CheckCircleIconSize).scale(checkScale),
        )
    }
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
