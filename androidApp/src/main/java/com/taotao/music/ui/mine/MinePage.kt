package com.taotao.music.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.taotao.music.data.CrashLog
import com.taotao.music.data.CrashReporter
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.TaotaoTypeScale
import com.taotao.music.ui.common.shareLogFile
import com.taotao.music.ui.theme.TaotaoCoral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** “我的”页三张快捷卡的固定高度。 */
private val MineShortcutCardHeight = 120.dp

/** 崩溃日志弹窗的正文最大高度；再多就滚动，弹窗不无限拉高。 */
private val CrashLogViewportMaxHeight = 420.dp

@Composable
internal fun MinePage(
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit,
    versionName: String,
    checking: Boolean,
    onCheckUpdate: () -> Unit,
    onMessage: (String) -> Unit,
    favoriteCount: Int,
    historyCount: Int,
    localCount: Int,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenPlaylists: () -> Unit,
    playlistCount: Int,
    profile: TencentMusicApi.UserProfile?,
    profileLoading: Boolean,
    imUid: String?,
    onOpenAccount: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val crashReporter = remember { CrashReporter(context) }
    var crashLogs by remember { mutableStateOf(emptyList<CrashLog>()) }
    var showCrashLogs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        crashLogs = withContext(Dispatchers.IO) { crashReporter.logs() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TaotaoSpacing.screenHorizontal,
            top = TaotaoSpacing.xxl,
            end = TaotaoSpacing.screenHorizontal,
            bottom = TaotaoSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
    ) {
        item { Text("我的", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = TaotaoSpacing.md, bottom = TaotaoSpacing.xs)
                    .clip(TaotaoShapes.card).background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpenAccount).padding(TaotaoSpacing.md),
                // 资料卡是进入昵称与邮箱管理的唯一入口，整块可点更容易发现。
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(TaotaoSizes.avatar).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!profile?.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = profile?.avatarUrl,
                            contentDescription = "个人头像",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(TaotaoSizes.iconLg))
                    }
                }
                Column(Modifier.weight(1f).padding(start = TaotaoSpacing.md)) {
                    Text(profile?.nickname ?: if (profileLoading) "正在读取资料…" else "桃桃音乐", style = TaotaoTypeScale.sectionTitle)
                    Text(
                        profile?.email ?: "点击管理个人昵称与邮箱",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = TaotaoSpacing.xxs),
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(TaotaoSizes.iconMd))
            }
        }
        if (!imUid.isNullOrBlank()) {
            item {
                Text(
                    "聊天 ID：$imUid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = TaotaoSpacing.xs),
                )
            }
        }
        item { Text("我的音乐", style = TaotaoTypeScale.sectionTitle, modifier = Modifier.padding(top = TaotaoSpacing.xs)) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
            ) {
                MineLibraryShortcut(
                    icon = Icons.Default.Favorite,
                    iconDescription = "收藏夹",
                    title = "收藏夹",
                    count = favoriteCount,
                    onClick = onOpenFavorites,
                    modifier = Modifier.weight(1f),
                )
                MineLibraryShortcut(
                    icon = Icons.Default.History,
                    iconDescription = "最近播放",
                    title = "最近播放",
                    count = historyCount,
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f),
                )
                MineLibraryShortcut(
                    icon = Icons.Default.DownloadDone,
                    iconDescription = "本地歌曲",
                    title = "本地歌曲",
                    count = localCount,
                    onClick = onOpenLocal,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            MineActionRow(
                icon = Icons.Default.LibraryMusic,
                iconDescription = "云端歌单",
                title = "我的歌单",
                subtitle = if (playlistCount == 0) "创建后同步到所有设备" else "$playlistCount 个歌单 · 云端同步",
                iconTint = TaotaoCoral,
                onClick = onOpenPlaylists,
            )
        }
        item { Text("应用", style = TaotaoTypeScale.sectionTitle, modifier = Modifier.padding(top = TaotaoSpacing.sm)) }
        item {
            MineActionRow(
                icon = Icons.Default.Settings,
                iconDescription = "设置",
                title = "设置",
                subtitle = "音质、下载与外观",
                iconTint = TaotaoCoral,
                onClick = onOpenSettings,
            )
        }
        item {
            // 测试机无法连接 adb，崩溃堆栈只能在应用内查看和复制。
            MineActionRow(
                icon = Icons.Default.BugReport,
                iconDescription = "崩溃日志",
                title = "崩溃日志",
                subtitle = if (crashLogs.isEmpty()) "暂无记录" else "${crashLogs.size} 条记录",
                enabled = crashLogs.isNotEmpty(),
                iconTint = if (crashLogs.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else TaotaoCoral,
                onClick = { showCrashLogs = true },
            )
        }
        item {
            MineActionRow(
                icon = Icons.Default.SystemUpdate,
                iconDescription = "检查更新",
                title = "检查更新",
                subtitle = if (checking) "正在检查…" else "当前 $versionName",
                enabled = !checking,
                iconTint = TaotaoCoral,
                trailing = if (checking) {
                    { CircularProgressIndicator(Modifier.size(TaotaoSizes.iconXs), color = TaotaoCoral, strokeWidth = TaotaoStroke.medium) }
                } else {
                    null
                },
                onClick = onCheckUpdate,
            )
        }
        item {
            MineActionRow(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                iconDescription = "退出登录",
                title = "退出登录",
                iconTint = TaotaoCoral,
                onClick = onLogout,
            )
        }
    }
    if (showCrashLogs) {
        val allLogs = crashLogs.joinToString("\n\n" + "=".repeat(40) + "\n\n") { "${it.name}\n${it.content}" }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showCrashLogs = false },
            title = { Text("崩溃日志（${crashLogs.size} 条）") },
            text = {
                Column(Modifier.heightIn(max = CrashLogViewportMaxHeight).verticalScroll(rememberScrollState())) {
                    Text(allLogs, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                // 导出成 .log 交给系统分享：多条堆栈叠起来轻易上万字符，
                // 剪贴板装不下，粘贴时换行也常被吃掉。
                TextButton(onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { crashReporter.exportToFile() }
                        if (file == null) {
                            onMessage("没有可导出的日志")
                            return@launch
                        }
                        runCatching { shareLogFile(context, file) }
                            .onFailure { onMessage(it.message ?: "导出失败") }
                    }
                }) { Text("导出 .log") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(allLogs)) }) {
                        Text("复制")
                    }
                    TextButton(onClick = {
                        crashReporter.clear()
                        crashLogs = emptyList()
                        showCrashLogs = false
                    }) { Text("清空") }
                }
            },
        )
    }
}

/** “我的音乐”三个入口固定在同一行，数量与入口含义一眼即可比较。 */
@Composable
private fun MineLibraryShortcut(
    icon: ImageVector,
    iconDescription: String,
    title: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.height(MineShortcutCardHeight).clip(TaotaoShapes.card)
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(TaotaoSpacing.sm),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier.size(TaotaoSizes.iconButton).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, iconDescription, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(TaotaoSizes.iconSm))
        }
        Column {
            Text(title, style = TaotaoTypeScale.minorTitle, maxLines = 1)
            Text(
                "$count 首",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = TaotaoSpacing.xxs),
            )
        }
    }
}

/** 设置、更新等入口使用同一套行组件，避免「我的」页出现四种不一致的卡片规则。 */
@Composable
private fun MineActionRow(
    icon: ImageVector,
    iconDescription: String,
    title: String,
    iconTint: Color,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(TaotaoShapes.card).background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, onClick = onClick).padding(TaotaoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, iconDescription, tint = iconTint)
        Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) {
            Text(title, color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        trailing?.invoke()
    }
}
