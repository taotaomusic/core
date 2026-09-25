package com.taotao.music.ui.update

import com.taotao.music.ui.theme.TaotaoCoral
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.update.UpdateStage
import com.taotao.music.update.UpdateStatus

/**
 * 强制更新页顶部图标的尺寸。
 *
 * 刻意不进 TaotaoSizes：它是**这一屏的构图决定**
 *（全屏居中，图标要够大才不显空），而不是会在别处复用的组件尺寸。
 */
private val UpdateHeroIconSize = 64.dp

/**
 * 更新说明正文的最大高度。
 *
 * 超出后转为滚动，避免一段很长的更新日志把下方按钮挤出屏幕。
 */
private val UpdateNoteMaxHeight = 220.dp

/**
 * 可选更新弹窗里说明正文的最大高度。
 *
 * 比 [UpdateNoteMaxHeight] 更宽松，这是**有意的不统一**：
 * 全屏页的按钮固定在说明下方，说明一高就会把按钮顶出屏幕；
 * 弹窗里说明与按钮在同一个滚动区内，说明占高不会挤走按钮，所以可以给得松一些。
 */
private val OptionalUpdateNoteMaxHeight = 300.dp

/**
 * 强制更新拦截页。
 *
 * 必须挡在登录之前：最需要强制更新的场景恰恰是上一个版本把登录搞坏了，
 * 若要求先登录才能看到这个页面，坏版本的用户就永远走不出来。
 */
@Composable
fun ForceUpdatePage(status: UpdateStatus, onDownload: () -> Unit, onInstall: () -> Unit, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(TaotaoSpacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                "更新",
                tint = TaotaoCoral,
                modifier = Modifier.height(UpdateHeroIconSize),
            )
            Spacer(Modifier.height(TaotaoSpacing.lg))
            Text(
                "需要更新后继续使用",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            status.release?.let { release ->
                Spacer(Modifier.height(TaotaoSpacing.xs))
                Text("新版本 ${release.versionName}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (release.releaseNote.isNotBlank()) {
                    Spacer(Modifier.height(TaotaoSpacing.md))
                    Column(
                        Modifier.fillMaxWidth().clip(TaotaoShapes.card)
                            .background(MaterialTheme.colorScheme.surface)
                            .heightIn(max = UpdateNoteMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(TaotaoSpacing.md),
                    ) {
                        Text(
                            release.releaseNote,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(TaotaoSpacing.xl))
            UpdateActionArea(status, onDownload = onDownload, onInstall = onInstall, onRetry = onRetry)
        }
    }
}

/** 可选更新提示。用户可以忽略，本次启动内不再提示。 */
@Composable
fun OptionalUpdateDialog(
    status: UpdateStatus,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (status.stage != UpdateStage.DOWNLOADING) onDismiss() },
        title = { Text("发现新版本 ${status.release?.versionName ?: ""}") },
        text = {
            Column(Modifier.heightIn(max = OptionalUpdateNoteMaxHeight).verticalScroll(rememberScrollState())) {
                Text(
                    status.release?.releaseNote?.takeIf { it.isNotBlank() } ?: "建议更新到最新版本。",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(TaotaoSpacing.md))
                UpdateActionArea(status, onDownload = onDownload, onInstall = onInstall, onRetry = onRetry)
            }
        },
        confirmButton = {},
        dismissButton = {
            // 下载过程中不提供关闭入口，避免用户以为取消了但下载还在继续。
            if (status.stage != UpdateStage.DOWNLOADING) TextButton(onClick = onDismiss) { Text("稍后再说") }
        },
    )
}

/** 按阶段渲染操作区，强制与可选两种界面共用，避免两处各写一份按钮逻辑。 */
@Composable
private fun UpdateActionArea(status: UpdateStatus, onDownload: () -> Unit, onInstall: () -> Unit, onRetry: () -> Unit) {
    when (status.stage) {
        UpdateStage.DOWNLOADING -> {
            LinearProgressIndicator(
                progress = { status.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = TaotaoCoral,
            )
            Spacer(Modifier.height(TaotaoSpacing.xs))
            Text(
                "正在下载 ${status.progressPercent}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        UpdateStage.READY -> Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("立即安装") }

        UpdateStage.FAILED -> {
            Text(status.error ?: "更新失败", color = TaotaoCoral, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(TaotaoSpacing.xs))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
        }

        else -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("下载更新") }
    }
}
