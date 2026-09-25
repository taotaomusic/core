package com.taotao.music.ui.ai

import com.taotao.music.ui.theme.AnimationCurves
import com.taotao.music.ui.theme.AnimationDurations
import com.taotao.music.ui.theme.LocalReduceMotion
import com.taotao.music.ui.theme.taotaoTween
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.taotao.music.data.AiImageSaver
import com.taotao.music.data.AiChatStore
import com.taotao.music.data.SavedAiChatMessage
import com.taotao.music.data.SavedAiConversation
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.TaotaoTypeScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AiChatMessage(val role: AiChatRole, val text: String, val taskId: String? = null, val progress: Int = 0, val imageUrl: String? = null, val error: String? = null)
private enum class AiChatRole { USER, ASSISTANT }
private enum class AiModel(val title: String, val apiName: String) { GPT_IMAGE_2("GPT Image 2", "gpt-image-2") }
private enum class AiQuality(val label: String, val apiName: String) { LOW("低", "low"), MEDIUM("中", "medium"), HIGH("高", "high") }

/** 支持多会话的 GPT Image 对话工作台，所有会话保存在设备本地。 */
@Composable
fun AiStudioPage(
    signedIn: Boolean,
    submitting: Boolean,
    task: TencentMusicApi.ImageTask?,
    onQueryTask: suspend (String) -> TencentMusicApi.ImageTask,
    onGenerate: (String, String, String, String, String, String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { AiChatStore(context) }
    var conversations by remember { mutableStateOf(store.readConversations().ifEmpty { listOf(store.newConversation()) }) }
    var selectedId by remember { mutableStateOf(conversations.last().id) }
    var draft by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(AiModel.GPT_IMAGE_2) }
    var ratio by remember { mutableStateOf("1:1") }
    var imageSize by remember { mutableStateOf("1K") }
    var quality by remember { mutableStateOf(AiQuality.LOW) }
    var thinking by remember { mutableStateOf("标准") }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var pendingSaveUrl by remember { mutableStateOf<String?>(null) }
    val selected = conversations.firstOrNull { it.id == selectedId } ?: conversations.last()
    val messages = selected.messages.map { it.toChatMessage() }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current

    /** 未发送过内容的欢迎页只是草稿，不占用本地会话名额。 */
    fun startNewConversation() {
        val fresh = store.newConversation()
        conversations = conversations.filter { conversation ->
            conversation.messages.any { message -> message.role == "user" }
        } + fresh
        selectedId = fresh.id
    }

    fun saveImage(imageUrl: String) {
        scope.launch {
            saveMessage = try {
                withContext(Dispatchers.IO) { AiImageSaver.save(context, imageUrl) }
            } catch (error: Throwable) {
                error.message ?: "图片保存失败"
            }
        }
    }
    val legacyStoragePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val imageUrl = pendingSaveUrl
        pendingSaveUrl = null
        if (granted && imageUrl != null) saveImage(imageUrl) else if (!granted) saveMessage = "请允许存储权限后再保存图片"
    }
    fun requestSave(imageUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            saveImage(imageUrl)
        } else {
            pendingSaveUrl = imageUrl
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun replaceSelected(newMessages: List<AiChatMessage>, title: String = selected.title) {
        conversations = conversations.map { conversation ->
            if (conversation.id == selected.id) SavedAiConversation(conversation.id, title, conversation.pinned, newMessages.map { it.toSavedMessage() }) else conversation
        }
    }
    LaunchedEffect(conversations) { store.saveConversations(conversations) }
    // 进程被系统杀掉或用户冷启动时，服务端任务仍在继续。重新发现本地的任务 ID 后
    // 主动恢复轮询，不能只恢复“正在创作”的文案，否则它会永久卡住。
    LaunchedEffect(Unit) {
        conversations.flatMap { conversation -> conversation.messages }
            .filter { it.taskId != null && it.imageUrl == null && it.error == null }
            .mapNotNull { it.taskId }
            .distinct()
            .forEach { pendingTaskId ->
                launch {
                    val latest = try {
                        var latest = onQueryTask(pendingTaskId)
                        while (latest.state == "IN_PROGRESS") {
                            delay(3_000)
                            latest = onQueryTask(pendingTaskId)
                        }
                        latest
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        conversations = conversations.map { conversation ->
                            conversation.copy(messages = conversation.messages.map { message ->
                                if (message.taskId == pendingTaskId) message.copy(error = error.message ?: "任务查询失败") else message
                            })
                        }
                        return@launch
                    }
                    conversations = conversations.map { conversation ->
                        conversation.copy(messages = conversation.messages.map { message ->
                            if (message.taskId == pendingTaskId) latest.toSavedMessage(message.text) else message
                        })
                    }
                }
            }
    }
    LaunchedEffect(task?.taskId, task?.state, task?.progress, task?.imageUrl, task?.error, selected.id) {
        val current = task ?: return@LaunchedEffect
        val index = messages.indexOfLast { it.taskId == current.taskId }.takeIf { it >= 0 }
            ?: messages.indexOfLast { it.role == AiChatRole.ASSISTANT && it.text == "正在提交创作请求…" }
        if (index < 0) return@LaunchedEffect
        val updated = messages.toMutableList()
        updated[index] = when {
            current.imageUrl != null -> AiChatMessage(AiChatRole.ASSISTANT, "创作完成，喜欢这张图吗？", current.taskId, imageUrl = current.imageUrl)
            current.state == "FAILED" -> AiChatMessage(AiChatRole.ASSISTANT, "这次创作没有完成。", current.taskId, error = current.error ?: "图片生成失败")
            else -> AiChatMessage(AiChatRole.ASSISTANT, "正在根据你的描述创作…", current.taskId, current.progress)
        }
        replaceSelected(updated)
        listState.animateScrollToItem(updated.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AiConversationDrawer(
                conversations = conversations,
                selectedId = selectedId,
                onSelect = { id -> selectedId = id; scope.launch { drawerState.close() } },
                onNew = { startNewConversation(); scope.launch { drawerState.close() } },
                onTogglePinned = { id -> conversations = conversations.map { if (it.id == id) it.copy(pinned = !it.pinned) else it } },
                onDelete = { id ->
                    val remaining = conversations.filterNot { it.id == id }
                    val safe = remaining.ifEmpty { listOf(store.newConversation()) }
                    conversations = safe
                    if (selectedId == id) selectedId = safe.last().id
                },
            )
        },
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.md)) {
        Row(Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "打开会话列表") }
            Column(Modifier.weight(1f).padding(start = TaotaoSpacing.xs)) {
                Text(selected.title, style = TaotaoTypeScale.subtitle, maxLines = 1)
                Text(
                    "GPT Image 创作对话",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = ::startNewConversation) { Icon(Icons.Default.Add, "新建对话", tint = MaterialTheme.colorScheme.primary) }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm)) {
            itemsIndexed(messages) { _, message ->
                AiChatBubble(
                    message = message,
                    reduceMotion = reduceMotion,
                    onPreview = { previewImageUrl = it },
                    onSave = ::requestSave,
                )
            }
            if (submitting && messages.lastOrNull()?.taskId == null) {
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = if (reduceMotion) {
                            fadeIn(taotaoTween(AnimationDurations.MICRO))
                        } else {
                            fadeIn(taotaoTween(AnimationDurations.SHORT)) + scaleIn(
                                initialScale = 0.97f,
                                animationSpec = taotaoTween(AnimationDurations.SHORT),
                            )
                        },
                        exit = if (reduceMotion) {
                            fadeOut(taotaoTween(AnimationDurations.MICRO, easing = AnimationCurves.standardOut))
                        } else {
                            fadeOut(taotaoTween(AnimationDurations.MICRO)) + scaleOut(
                                targetScale = 0.97f,
                                animationSpec = taotaoTween(AnimationDurations.MICRO, easing = AnimationCurves.standardOut),
                            )
                        },
                    ) {
                        AiChatBubble(
                            message = AiChatMessage(AiChatRole.ASSISTANT, "正在提交创作请求…"),
                            reduceMotion = reduceMotion,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = TaotaoSpacing.xs), horizontalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
            AiOptionPicker("模型", model.title, AiModel.entries.toList(), { it.title }) { model = it }
            AiOptionPicker("比例", ratio, listOf("1:1", "3:4", "9:16", "16:9"), { it }) { ratio = it }
            AiOptionPicker("尺寸", imageSize, listOf("1K", "2K", "4K"), { it }) { imageSize = it }
            AiOptionPicker("质量", quality.label, AiQuality.entries.toList(), { it.label }) { quality = it }
            AiOptionPicker("思考", thinking, listOf("快速", "标准", "深入"), { it }) { thinking = it }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xxs, bottom = TaotaoSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text(if (signedIn) "描述你想创作的画面…" else "登录后可开始对话") }, modifier = Modifier.weight(1f), minLines = 1, maxLines = 4, enabled = signedIn && !submitting, shape = TaotaoShapes.large)
            IconButton(onClick = {
                val prompt = draft.trim(); if (prompt.isBlank()) return@IconButton
                replaceSelected(messages + AiChatMessage(AiChatRole.USER, prompt) + AiChatMessage(AiChatRole.ASSISTANT, "正在提交创作请求…"), prompt.take(16))
                draft = ""; onGenerate(model.apiName, prompt, ratio, imageSize, quality.apiName, thinking)
            }, enabled = signedIn && draft.isNotBlank() && !submitting, modifier = Modifier.padding(start = TaotaoSpacing.xs).background(MaterialTheme.colorScheme.primary, TaotaoShapes.large)) { Icon(Icons.Default.Send, "发送", tint = MaterialTheme.colorScheme.onPrimary) }
        }
        saveMessage?.let { text ->
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = TaotaoSpacing.sm, bottom = TaotaoSpacing.xxs),
            )
        }
    }
    previewImageUrl?.let { imageUrl ->
        Dialog(onDismissRequest = { previewImageUrl = null }) {
            Surface(shape = TaotaoShapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(TaotaoSpacing.sm)) {
                    AsyncImage(imageUrl, "AI 图片预览", Modifier.fillMaxWidth())
                    TextButton(
                        onClick = { requestSave(imageUrl) },
                    ) {
                        Icon(Icons.Default.Download, null)
                        Text(" 保存到本地")
                    }
                }
            }
        }
    }
    }
}

/** DeepSeek 式会话抽屉：可搜索、置顶、删除并切换任意本地历史会话。 */
@Composable
private fun AiConversationDrawer(
    conversations: List<SavedAiConversation>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onTogglePinned: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = TaotaoSpacing.md)) {
            Row(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text("对话", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                FilledTonalButton(onClick = onNew) { Icon(Icons.Default.Add, null); Text(" 新对话") }
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("搜索对话内容") },
                modifier = Modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.sm),
                singleLine = true,
            )
            val filtered = conversations.filter { conversation ->
                keyword.isBlank() || conversation.title.contains(keyword, true) || conversation.messages.any { it.text.contains(keyword, true) }
            }.sortedWith(compareByDescending<SavedAiConversation> { it.pinned }.thenByDescending { it.id })
            LazyColumn(verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xxs), modifier = Modifier.weight(1f)) {
                itemsIndexed(filtered, key = { _, item -> item.id }) { _, conversation ->
                    var menu by remember(conversation.id) { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (conversation.id == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, TaotaoShapes.medium)
                            .padding(
                                start = TaotaoSpacing.sm,
                                top = TaotaoSpacing.xs,
                                bottom = TaotaoSpacing.xs,
                                end = TaotaoSpacing.xxs,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(conversation.title, Modifier.weight(1f).clickable { onSelect(conversation.id) }, maxLines = 1, color = if (conversation.id == selectedId) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                        if (conversation.pinned) Icon(Icons.Default.PushPin, "已置顶", Modifier.size(TaotaoSizes.iconXs), tint = MaterialTheme.colorScheme.primary)
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surface) {
                                DropdownMenuItem(text = { Text(if (conversation.pinned) "取消置顶" else "置顶") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { onTogglePinned(conversation.id); menu = false })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { onDelete(conversation.id); menu = false })
                            }
                        }
                    }
                }
            }
            Text(
                "本地保存 ${conversations.size} 个对话",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = TaotaoSpacing.sm),
            )
        }
    }
}

@Composable private fun <T> AiOptionPicker(label: String, value: String, options: List<T>, optionLabel: (T) -> String, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = true, onClick = { expanded = true }, label = { Text("$label · $value", maxLines = 1) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surface) { options.forEach { option -> DropdownMenuItem(text = { Text(optionLabel(option), color = MaterialTheme.colorScheme.onSurface) }, onClick = { onSelected(option); expanded = false }) } }
    }
}

@Composable
private fun AiChatBubble(
    message: AiChatMessage,
    reduceMotion: Boolean = false,
    onPreview: (String) -> Unit = {},
    onSave: (String) -> Unit = {},
) {
    val mine = message.role == AiChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(if (mine) 0.78f else 0.88f).background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, TaotaoShapes.card).padding(TaotaoSpacing.sm)) {
            Text(message.text, color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.progress > 0 && message.imageUrl == null && message.error == null) {
                Row(Modifier.padding(top = TaotaoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(TaotaoSizes.iconXs), strokeWidth = TaotaoStroke.medium)
                    Text(" ${message.progress.coerceIn(0, 100)}%", Modifier.padding(start = TaotaoSpacing.xs))
                }
            }
            message.imageUrl?.let { imageUrl ->
                AnimatedVisibility(
                    visible = true,
                    enter = if (reduceMotion) {
                        fadeIn(taotaoTween(AnimationDurations.MICRO))
                    } else {
                        fadeIn(taotaoTween(AnimationDurations.SHORT)) + scaleIn(
                            initialScale = 0.97f,
                            animationSpec = taotaoTween(AnimationDurations.SHORT),
                        )
                    },
                ) {
                    Column {
                        AsyncImage(
                            imageUrl,
                            "AI 生成图片，点击预览",
                            Modifier.fillMaxWidth()
                                .padding(top = TaotaoSpacing.xs)
                                .background(MaterialTheme.colorScheme.surface, TaotaoShapes.medium)
                                .clickable { onPreview(imageUrl) },
                        )
                        TextButton(onClick = { onSave(imageUrl) }) {
                            Icon(Icons.Default.Download, null)
                            Text(" 保存到本地")
                        }
                    }
                }
            }
            message.error?.let { Text(it, Modifier.padding(top = TaotaoSpacing.xs), color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun SavedAiChatMessage.toChatMessage() = AiChatMessage(if (role == "user") AiChatRole.USER else AiChatRole.ASSISTANT, text, taskId, progress, imageUrl, error)
private fun AiChatMessage.toSavedMessage() = SavedAiChatMessage(if (role == AiChatRole.USER) "user" else "assistant", text, taskId, progress, imageUrl, error)
private fun TencentMusicApi.ImageTask.toSavedMessage(@Suppress("UNUSED_PARAMETER") fallbackText: String) = SavedAiChatMessage(
    role = "assistant",
    text = when {
        imageUrl != null -> "创作完成，喜欢这张图吗？"
        state == "FAILED" -> "这次创作没有完成。"
        else -> "正在根据你的描述创作…"
    },
    taskId = taskId,
    progress = progress,
    imageUrl = imageUrl,
    error = error,
)
