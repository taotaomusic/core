package com.taotao.music.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taotao.music.data.TencentMusicApi
import com.taotao.music.playerui.SharedSectionHeader
import com.taotao.music.playerui.SharedSectionLevel
import com.taotao.music.playerui.theme.TaotaoShapes
import com.taotao.music.playerui.theme.TaotaoSizes
import com.taotao.music.playerui.theme.TaotaoSpacing
import com.taotao.music.playerui.theme.TaotaoStroke
import com.taotao.music.playerui.theme.TaotaoTypeScale
import com.taotao.music.ui.auth.readableMessage
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 客户端仅做格式预校验；实际可用域名和验证规则由服务端统一裁决。 */
private const val SUPPORTED_EMAIL_HINT = "请输入有效的邮箱地址"
private val EMAIL_INPUT_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/**
 * 账号资料底部页的最大高度。
 *
 * 刻意不进 TaotaoSizes：它描述的是「这一屏最多占多高」——
 * 底部页上方要留出可见的一截背景，用户才知道还能下滑关闭；超出后内部滚动。
 */
private val ProfileSheetMaxHeight = 720.dp

/**
 * 公告对话框正文的最大高度。
 *
 * 超出后内部滚动，避免一段长公告把下方按钮顶出屏幕。
 */
private val AnnouncementDialogMaxHeight = 480.dp

/**
 * 账号资料用完整底部页承载，避免在窄小对话框里叠放三组输入与操作。
 * 老账号 email 为 null 时明确走“补绑”，已有邮箱时才进入换绑流程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    api: TencentMusicApi,
    profile: TencentMusicApi.UserProfile,
    onProfileChanged: (TencentMusicApi.UserProfile) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nickname by remember(profile.nickname) { mutableStateOf(profile.nickname) }
    var email by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val changingEmail = profile.email != null
    val emailAccepted = EMAIL_INPUT_PATTERN.matches(email.trim())
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun launchRequest(work: suspend () -> Unit, successMessage: String) {
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { work() } }
                .onSuccess { onMessage(successMessage) }
                .onFailure { onMessage(it.readableMessage()) }
            loading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!loading) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = ProfileSheetMaxHeight).verticalScroll(rememberScrollState())
                .padding(
                    start = TaotaoSpacing.screenHorizontal,
                    end = TaotaoSpacing.screenHorizontal,
                    bottom = TaotaoSpacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.md),
        ) {
            AccountHeader(profile)
            SharedSectionHeader(
                title = "个人资料",
                subtitle = "这些信息只在当前账号下展示",
                level = SharedSectionLevel.CARD,
            )
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(TaotaoSpacing.md), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(24) },
                        label = { Text("个人昵称") },
                        supportingText = { Text("1 至 24 个字符") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !loading,
                    )
                    Button(
                        enabled = !loading && nickname.trim().isNotEmpty() && nickname.trim() != profile.nickname,
                        onClick = {
                            loading = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { api.updateNickname(nickname.trim()) } }
                                    .onSuccess { onProfileChanged(it); onMessage("昵称已更新") }
                                    .onFailure { onMessage(it.readableMessage()) }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存昵称") }
                }
            }
            SharedSectionHeader(
                title = "账号安全",
                subtitle = "绑定邮箱后可用于账号验证",
                level = SharedSectionLevel.CARD,
            )
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(TaotaoSpacing.md), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm)) {
                    EmailBindingStatus(profile.email)
                    HorizontalDivider()
                    Text(
                        if (changingEmail) "更换后，新邮箱将作为此账号的验证邮箱。" else "老账号尚未绑定邮箱，可直接在这里补绑。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.trim(); verificationCode = "" },
                        label = { Text(if (changingEmail) "新邮箱" else "邮箱") },
                        leadingIcon = { Icon(Icons.Default.Email, null) },
                        isError = email.isNotEmpty() && !emailAccepted,
                        supportingText = { Text(SUPPORTED_EMAIL_HINT) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = verificationCode,
                            onValueChange = { verificationCode = it.filter(Char::isDigit).take(6) },
                            label = { Text("6 位验证码") }, modifier = Modifier.weight(1f), singleLine = true,
                            enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(Modifier.width(TaotaoSpacing.xs))
                        TextButton(
                            enabled = !loading && emailAccepted,
                            onClick = { launchRequest({ api.sendEmailBindingVerification(email, changingEmail) }, "验证码已发送，请查收邮箱") },
                        ) { Text("获取验证码") }
                    }
                    Button(
                        enabled = !loading && emailAccepted && verificationCode.length == 6,
                        onClick = {
                            launchRequest({
                                api.confirmEmailBinding(email, verificationCode, changingEmail)
                                onProfileChanged(api.profile())
                            }, if (changingEmail) "邮箱已更换" else "邮箱已绑定")
                        }, modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (changingEmail) "确认更换邮箱" else "确认绑定邮箱") }
                }
            }
            if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = TaotaoStroke.medium)
            }
            TextButton(onClick = onDismiss, enabled = !loading, modifier = Modifier.align(Alignment.End)) { Text("完成") }
        }
    }
}

/** 设置页中的独立个人资料页，资料与邮箱验证不再塞进“我的”页的临时弹层。 */
@Composable
fun AccountProfilePage(
    api: TencentMusicApi,
    profile: TencentMusicApi.UserProfile,
    onProfileChanged: (TencentMusicApi.UserProfile) -> Unit,
    onMessage: (String) -> Unit,
    onBack: () -> Unit,
) {
    var nickname by remember(profile.nickname) { mutableStateOf(profile.nickname) }
    var avatarUrl by remember(profile.avatarUrl) { mutableStateOf(profile.avatarUrl.orEmpty()) }
    var selectedAvatarUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var resendRemainingSeconds by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val changingEmail = profile.email != null
    val emailAccepted = EMAIL_INPUT_PATTERN.matches(email.trim())
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selectedAvatarUri = it }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(resendRemainingSeconds) {
        if (resendRemainingSeconds > 0) {
            delay(1_000)
            resendRemainingSeconds -= 1
        }
    }
    fun request(work: suspend () -> Unit, success: String) {
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { work() } }
                .onSuccess { onMessage(success) }
                .onFailure { onMessage(it.readableMessage()) }
            loading = false
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TaotaoSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.sm),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = TaotaoSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("个人资料", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = TaotaoSpacing.xxs))
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(TaotaoSpacing.md), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.avatarUrl != null) {
                        AsyncImage(profile.avatarUrl, "头像", Modifier.size(TaotaoSizes.avatar).clip(CircleShape))
                    } else {
                        Box(
                        Modifier.size(TaotaoSizes.avatar).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(Modifier.padding(start = TaotaoSpacing.sm)) {
                        Text(profile.nickname, style = TaotaoTypeScale.sectionTitle)
                        Text("用户名 ${profile.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(value = nickname, onValueChange = { nickname = it.take(24) }, label = { Text("昵称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !loading)
                OutlinedButton(onClick = { avatarPicker.launch("image/*") }, enabled = !loading) { Text(if (selectedAvatarUri == null) "选择头像图片" else "已选择头像图片") }
                Button(
                    enabled = !loading && nickname.isNotBlank() && (nickname != profile.nickname || selectedAvatarUri != null),
                    onClick = {
                        request({
                            val updated = selectedAvatarUri?.let { api.uploadAvatar(it, context.contentResolver) }
                            val finalProfile = if (updated != null && nickname.trim() != updated.nickname) {
                                api.updateProfile(nickname.trim())
                            } else {
                                updated ?: api.updateProfile(nickname.trim())
                            }
                            onProfileChanged(finalProfile)
                        }, "个人资料已保存")
                    }, modifier = Modifier.fillMaxWidth(),
                ) { Text("保存个人资料") }
            }
        }
        SharedSectionHeader(
                    title = "邮箱",
                    subtitle = "用于账号验证",
                    level = SharedSectionLevel.CARD,
                )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(TaotaoSpacing.md), verticalArrangement = Arrangement.spacedBy(TaotaoSpacing.xs)) {
                EmailBindingStatus(profile.email)
                Text(if (changingEmail) "当前邮箱已绑定，可验证新邮箱后换绑。" else "当前账号还未绑定邮箱，验证后即可完成补绑。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = email, onValueChange = { email = it.trim(); code = ""; resendRemainingSeconds = 0 }, label = { Text(if (changingEmail) "新邮箱" else "邮箱") }, supportingText = { Text(SUPPORTED_EMAIL_HINT) }, isError = email.isNotEmpty() && !emailAccepted, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("6 位验证码") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    TextButton(
                        enabled = !loading && resendRemainingSeconds == 0 && emailAccepted,
                        onClick = {
                            loading = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { api.sendEmailBindingVerification(email, changingEmail) } }
                                    .onSuccess {
                                        resendRemainingSeconds = EMAIL_RESEND_INTERVAL_SECONDS
                                        onMessage("验证码已发送，请在 ${EMAIL_RESEND_INTERVAL_SECONDS} 秒内查收")
                                    }
                                    .onFailure { onMessage(it.readableMessage()) }
                                loading = false
                            }
                        },
                    ) {
                        Text(if (resendRemainingSeconds > 0) "${resendRemainingSeconds} 秒后重发" else "获取验证码")
                    }
                }
                Button(enabled = !loading && emailAccepted && code.length == 6, onClick = {
                    request({ api.confirmEmailBinding(email, code, changingEmail); onProfileChanged(api.profile()) }, if (changingEmail) "邮箱已换绑" else "邮箱已绑定")
                }, modifier = Modifier.fillMaxWidth()) { Text(if (changingEmail) "确认换绑" else "确认绑定") }
            }
        }
        if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(strokeWidth = TaotaoStroke.medium) }
        Spacer(Modifier.height(TaotaoSpacing.md))
    }
}

@Composable
private fun AccountHeader(profile: TencentMusicApi.UserProfile) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(TaotaoShapes.card)
            .background(MaterialTheme.colorScheme.primaryContainer).padding(TaotaoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(TaotaoSizes.artworkRow).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(TaotaoSizes.iconLg)) }
        Column(Modifier.weight(1f).padding(start = TaotaoSpacing.sm)) {
            Text(profile.nickname, color = MaterialTheme.colorScheme.onPrimaryContainer, style = TaotaoTypeScale.subtitle, fontWeight = FontWeight.Bold)
            Text("用户名 ${profile.username}", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmailBindingStatus(email: String?) {
    val bound = email != null
    val background = if (bound) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (bound) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().clip(TaotaoShapes.medium).background(background).padding(TaotaoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (bound) Icons.Default.VerifiedUser else Icons.Default.Email, null, tint = content)
        Column(Modifier.padding(start = TaotaoSpacing.sm)) {
            Text(if (bound) "邮箱已绑定" else "邮箱未绑定", color = content, style = TaotaoTypeScale.minorTitle)
            Text(email ?: "绑定后可用于账号验证", color = content, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = TaotaoSpacing.xxs))
        }
    }
}

/** 公告列表只展示服务端已启用的公开内容，服务端决定排序与置顶。 */
@Composable
fun AnnouncementDialog(announcements: List<TencentMusicApi.Announcement>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Campaign, null) },
        title = { Text("公告") },
        // 容器色走主题默认：surfaceContainerHigh 已在 PlayerTheme 覆盖为暖色。
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Column(Modifier.heightIn(max = AnnouncementDialogMaxHeight).verticalScroll(rememberScrollState())) {
                announcements.forEachIndexed { index, announcement ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(vertical = TaotaoSpacing.sm),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(announcement.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (announcement.pinned) Text(
                            "置顶", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clip(TaotaoShapes.small).background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = TaotaoSpacing.xxs, vertical = TaotaoSpacing.xxs),
                        )
                    }
                    Text(announcement.content, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = TaotaoSpacing.xxs))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private const val EMAIL_RESEND_INTERVAL_SECONDS = 60
