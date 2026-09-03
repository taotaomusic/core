package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.taotao.music.data.TencentMusicApi
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 客户端仅做格式预校验；实际可用域名和验证规则由服务端统一裁决。 */
private const val SUPPORTED_EMAIL_HINT = "请输入有效的邮箱地址"
private val EMAIL_INPUT_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

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
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AccountHeader(profile)
            AccountSectionTitle("个人资料", "这些信息只在当前账号下展示")
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            AccountSectionTitle("账号安全", "绑定邮箱后可用于账号验证")
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmailBindingStatus(profile.email)
                    HorizontalDivider()
                    Text(
                        if (changingEmail) "更换后，新邮箱将作为此账号的验证邮箱。" else "老账号尚未绑定邮箱，可直接在这里补绑。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
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
                        Spacer(Modifier.width(8.dp))
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
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("个人资料", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.avatarUrl != null) {
                        AsyncImage(profile.avatarUrl, "头像", Modifier.size(56.dp).clip(CircleShape))
                    } else {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(profile.nickname, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("用户名 ${profile.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
        AccountSectionTitle("邮箱", "用于账号验证")
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EmailBindingStatus(profile.email)
                Text(if (changingEmail) "当前邮箱已绑定，可验证新邮箱后换绑。" else "当前账号还未绑定邮箱，验证后即可完成补绑。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
        if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(strokeWidth = 2.dp) }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun AccountHeader(profile: TencentMusicApi.UserProfile) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp)) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(profile.nickname, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("用户名 ${profile.username}", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AccountSectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun EmailBindingStatus(email: String?) {
    val bound = email != null
    val background = if (bound) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (bound) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (bound) Icons.Default.VerifiedUser else Icons.Default.Email, null, tint = content)
        Column(Modifier.padding(start = 10.dp)) {
            Text(if (bound) "邮箱已绑定" else "邮箱未绑定", color = content, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(email ?: "绑定后可用于账号验证", color = content, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
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
        // 默认的容器 token 在本项目未覆盖时会回退为 Material 紫灰；显式绑定主题卡片色。
        containerColor = MaterialTheme.colorScheme.surface,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                announcements.forEachIndexed { index, announcement ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(announcement.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (announcement.pinned) Text(
                            "置顶", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Text(announcement.content, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private const val EMAIL_RESEND_INTERVAL_SECONDS = 60
