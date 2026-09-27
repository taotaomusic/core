package com.taotao.music.ui.chat

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.data.im.ImChatMessage
import com.taotao.music.data.im.ImConnectionInfo
import com.taotao.music.data.im.ImConnectionState
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 悟空 IM 私聊页：左侧抽屉用于切换已保存的聊天。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatPage(
    connection: ImConnectionInfo,
    messages: List<ImChatMessage>,
    savedPeers: List<String>,
    syncedPeers: List<String>,
    peerContacts: Map<String, TencentMusicApi.ImContact>,
    ownAvatarUrl: String?,
    onSend: (peerUid: String, content: String) -> Unit,
    onPeerSelected: (peerUid: String) -> Unit,
    onPeerActiveChanged: (peerUid: String?) -> Unit,
    onPeersVisible: (List<String>) -> Unit,
    onRevoke: (ImChatMessage) -> Unit,
    onMessage: (String) -> Unit,
    onNewMessage: () -> Unit = {},
) {
    // 抽屉合并「本地保存的会话」与「本进程同步到的会话」：对端（或后台）先发来的
    // 新会话不会进本地存储，不合并的话那条消息在界面上无路可达。
    val knownPeers = remember(savedPeers, syncedPeers) { (savedPeers + syncedPeers).distinct().sorted() }
    // 选中会话不 keyed 在列表实例上：同步刷新会让列表换新实例，重置会把用户拽回第一个会话。
    var peerUid by remember { mutableStateOf(savedPeers.firstOrNull().orEmpty()) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()
    val peerMessages by remember(messages, peerUid) {
        derivedStateOf { messages.filter { it.peerUid == peerUid.trim().lowercase() } }
    }
    val previousMessageCount = remember { mutableStateOf(peerMessages.size) }

    LaunchedEffect(peerMessages.size) {
        if (peerMessages.size > previousMessageCount.value) {
            onNewMessage()
        }
        previousMessageCount.value = peerMessages.size
    }
    LaunchedEffect(peerUid) {
        val normalizedPeerUid = peerUid.trim().lowercase()
        if (UUID_PATTERN.matches(normalizedPeerUid)) {
            onPeerActiveChanged(normalizedPeerUid)
            onPeerSelected(normalizedPeerUid)
        } else {
            onPeerActiveChanged(null)
        }
    }
    DisposableEffect(Unit) { onDispose { onPeerActiveChanged(null) } }
    // 没有选中会话时（本地还没有保存记录）取第一个已知会话，并把可见会话交给客户端解析昵称。
    LaunchedEffect(knownPeers) {
        if (peerUid.isBlank()) peerUid = knownPeers.firstOrNull().orEmpty()
        onPeersVisible(knownPeers)
    }
    // 1:1 聊天全程只有两张头像图片，页面级各加载一次；气泡里只画缓存位图。
    // 此前每个气泡各自挂 AsyncImage，快速滑动时每个新气泡都要走一遍图片请求管线，
    // 动图头像还会逐帧解码 —— 这是滑动卡顿的主因。
    val peerAvatar = rememberAvatarImage(peerContacts[peerUid.trim().lowercase()]?.avatarUrl)
    val ownAvatar = rememberAvatarImage(ownAvatarUrl)
    // 首次进入会话滚到最新一条；之后仅当本来就在底部时才跟随新消息，
    // 用户上滑翻历史时不再被强制拽回底部。
    var pendingInitialScroll by remember(peerUid) { mutableStateOf(true) }
    LaunchedEffect(peerUid, peerMessages.size) {
        if (peerMessages.isEmpty()) return@LaunchedEffect
        val layoutInfo = messageListState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisibleIndex >= layoutInfo.totalItemsCount - 2
        if (pendingInitialScroll || nearBottom) {
            messageListState.scrollToItem(peerMessages.lastIndex)
            pendingInitialScroll = false
        }
    }

    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet {
            Text("聊天", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(TaotaoSpacing.xl))
            if (knownPeers.isEmpty()) {
                Text("还没有聊天。发送第一条消息后，好友会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = TaotaoSpacing.xl))
            } else {
                val peerItems = remember(knownPeers, peerContacts, peerUid) {
                    knownPeers.map { savedUid ->
                        Triple(savedUid, peerContacts[savedUid]?.nickname ?: "加载昵称…", peerContacts[savedUid]?.avatarUrl)
                    }
                }
                peerItems.forEach { (savedUid, displayName, avatarUrl) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            peerUid = savedUid
                            scope.launch { drawerState.close() }
                        }.padding(horizontal = TaotaoSpacing.xl, vertical = TaotaoSpacing.sm),
                    ) {
                        ChatAvatar(rememberAvatarImage(avatarUrl), size = ConversationAvatarSize)
                        Spacer(Modifier.size(TaotaoSpacing.sm))
                        Text(
                            text = displayName,
                            color = if (savedUid == peerUid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (savedUid == peerUid) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }) {
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = TaotaoSpacing.lg, vertical = TaotaoSpacing.md),
            verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Menu, "打开聊天列表", modifier = Modifier.size(TaotaoSizes.iconLg).clickable { scope.launch { drawerState.open() } })
                Spacer(Modifier.size(TaotaoSpacing.sm))
                Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(TaotaoSizes.iconMd))
                Spacer(Modifier.size(TaotaoSpacing.xs))
                Column(Modifier.weight(1f)) {
                    Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(peerContacts[peerUid]?.nickname ?: peerUid.takeIf { it.isBlank() } ?: "加载昵称…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ConnectionBadge(connection.state)
            }
            if (peerUid.isBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("从左上角打开聊天列表", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(
                    state = messageListState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs),
                ) {
                    items(peerMessages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            peerAvatar = peerAvatar,
                            ownAvatar = ownAvatar,
                            onRevoke = onRevoke,
                        )
                    }
                }
            }
            ChatInputBar(
                enabled = peerUid.isNotBlank() && connection.state == ImConnectionState.CONNECTED,
                onSend = { content ->
                    runCatching { onSend(peerUid, content) }
                        .onFailure { onMessage(it.message ?: "消息发送失败") }
                        .isSuccess
                },
            )
        }
    }
}

@Composable
private fun ConnectionBadge(state: ImConnectionState) {
    val (label, color) = when (state) {
        ImConnectionState.CONNECTED -> "已连接" to MaterialTheme.colorScheme.primary
        ImConnectionState.CONNECTING -> "连接中" to MaterialTheme.colorScheme.tertiary
        ImConnectionState.NO_NETWORK -> "无网络" to MaterialTheme.colorScheme.error
        ImConnectionState.FAILED -> "连接失败" to MaterialTheme.colorScheme.error
        ImConnectionState.IDLE -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.background(color.copy(alpha = 0.12f), CircleShape).padding(horizontal = TaotaoSpacing.xs, vertical = TaotaoSpacing.xxs))
}

@Composable
private fun ChatBubble(
    message: ImChatMessage,
    peerAvatar: ImageBitmap?,
    ownAvatar: ImageBitmap?,
    onRevoke: (ImChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.isRevoked) {
        // 撤回消息：微信风格，灰色居中显示
        Box(modifier.fillMaxWidth().padding(vertical = TaotaoSpacing.xxs), contentAlignment = Alignment.Center) {
            Text(
                text = if (message.isMine) "你撤回了一条消息" else "对方撤回了一条消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        ) {
            // 微信式布局：对方消息头像在左、自己的在右，头像与气泡顶部对齐。
            if (!message.isMine) ChatAvatar(peerAvatar, size = BubbleAvatarSize)
            Column(
                horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
                // fill = false：气泡随内容收缩，长文本最多占到头像以外的剩余宽度。
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = TaotaoSpacing.xs),
            ) {
                Text(message.content, modifier = Modifier.background(if (message.isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, TaotaoShapes.card).padding(horizontal = TaotaoSpacing.sm, vertical = TaotaoSpacing.xs), color = if (message.isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = MESSAGE_TIME_FORMAT.format(Date(message.sentAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.tightVertical),
                )
                if (message.isMine) {
                    val canRevoke = remember(message.id, message.sentAtMillis) {
                        System.currentTimeMillis() - message.sentAtMillis <= 120_000
                    }
                    if (canRevoke) {
                        Text(
                            if (message.isRead) "已读 · 撤回" else "未读 · 撤回",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onRevoke(message) }.padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.tightVertical),
                        )
                    }
                }
            }
            if (message.isMine) ChatAvatar(ownAvatar, size = BubbleAvatarSize)
        }
    }
}

/** 聊天头像：直接绘制页面级加载好的位图，缺省退回人形占位（与「我的」页一致）。 */
@Composable
private fun ChatAvatar(avatar: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar == null) {
            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(TaotaoSizes.iconSm))
        } else {
            Image(
                bitmap = avatar,
                contentDescription = "聊天头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 页面级头像加载：一个地址只发起一次请求、解码一次，结果随状态流共享。
 *
 * 气泡列表里直接画返回的位图，而不是每个气泡各自挂 AsyncImage —— 后者会让
 * 滑动时新进入屏幕的气泡逐个走图片请求管线，动图头像更会被逐帧解码，滑动就卡。
 * 请求显式限定解码尺寸并开启硬件位图，头像画成静帧，不再承担动画成本。
 */
@Composable
private fun rememberAvatarImage(url: String?): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, url) {
        if (url.isNullOrBlank()) return@produceState
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(AVATAR_DECODE_PIXELS)
            .allowHardware(true)
            .build()
        runCatching { context.imageLoader.execute(request) }
            .onSuccess { result ->
                val drawable = (result as? SuccessResult)?.drawable ?: return@onSuccess
                value = when (drawable) {
                    is BitmapDrawable -> drawable.bitmap.asImageBitmap()
                    else -> drawable.toBitmap().asImageBitmap()
                }
            }
    }.value
}

/**
 * 输入栏自持草稿，发送成功才清空。
 *
 * 草稿是打字期间唯一高频变化的状态，收在栏内让每次按键只重组输入栏本身；
 * 之前草稿放在页面顶层，每敲一个字抽屉、标题栏和整条消息列表都跟着重组一遍，
 * 头像进列表后这条路径明显变卡。
 */
@Composable
private fun ChatInputBar(
    enabled: Boolean,
    onSend: (content: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("消息") }, modifier = Modifier.weight(1f), maxLines = 4)
        Spacer(Modifier.size(TaotaoSpacing.xs))
        Button(enabled = enabled && draft.isNotBlank(), onClick = {
            if (onSend(draft)) draft = ""
        }) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
    }
}

/** 气泡旁的头像直径与会话列表头像直径。 */
private val BubbleAvatarSize = 36.dp
private val ConversationAvatarSize = 44.dp

/** 头像解码上限（像素）：4 倍直径的余量，避免相册原图整幅解码。 */
private const val AVATAR_DECODE_PIXELS = 144

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val MESSAGE_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
