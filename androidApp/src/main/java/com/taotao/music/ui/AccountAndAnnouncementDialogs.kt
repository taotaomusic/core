package com.taotao.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.data.TencentMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val QQ_EMAIL_PATTERN = Regex("^[^\\s@]+@(qq\\.com|foxmail\\.com)$", RegexOption.IGNORE_CASE)

/** 账号资料与邮箱操作集中在一个弹层，避免把邮箱留在本地设置中造成隐私泄露。 */
@Composable
fun AccountDialog(
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

    fun launchRequest(work: suspend () -> Unit, successMessage: String) {
        loading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { work() } }
                .onSuccess { onMessage(successMessage) }
                .onFailure { onMessage(it.readableMessage()) }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        icon = { Icon(Icons.Default.Person, null) },
        title = { Text("账号资料") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("用户名：${profile.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(24) },
                    label = { Text("个人昵称") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading,
                )
                TextButton(
                    enabled = !loading && nickname.trim().isNotEmpty() && nickname.trim() != profile.nickname,
                    onClick = {
                        loading = true
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { api.updateNickname(nickname.trim()) } }
                                .onSuccess {
                                    onProfileChanged(it)
                                    onMessage("昵称已更新")
                                }
                                .onFailure { onMessage(it.readableMessage()) }
                            loading = false
                        }
                    },
                ) { Text("保存昵称") }
                HorizontalDivider()
                Text(if (changingEmail) "已绑定邮箱：${profile.email}" else "尚未绑定邮箱", fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim(); verificationCode = "" },
                    label = { Text(if (changingEmail) "新的 QQ 邮箱" else "QQ 邮箱") },
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    isError = email.isNotEmpty() && !QQ_EMAIL_PATTERN.matches(email),
                    supportingText = { Text("仅支持 qq.com 或 foxmail.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("验证码") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = !loading && QQ_EMAIL_PATTERN.matches(email),
                        onClick = {
                            launchRequest(
                                { api.sendEmailBindingVerification(email, changingEmail) },
                                "验证码已发送，请查收邮箱",
                            )
                        },
                    ) { Text("发送验证码") }
                }
                TextButton(
                    enabled = !loading && QQ_EMAIL_PATTERN.matches(email) && verificationCode.length == 6,
                    onClick = {
                        launchRequest({
                            api.confirmEmailBinding(email, verificationCode, changingEmail)
                            onProfileChanged(api.profile())
                        }, if (changingEmail) "邮箱已更换" else "邮箱已绑定")
                    },
                ) { Text(if (changingEmail) "确认更换邮箱" else "确认绑定邮箱") }
            }
        },
        confirmButton = {
            if (loading) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
            else TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** 公告列表仅展示服务端已启用的公开内容，服务端决定排序与置顶。 */
@Composable
fun AnnouncementDialog(announcements: List<TencentMusicApi.Announcement>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Campaign, null) },
        title = { Text("公告") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                announcements.forEachIndexed { index, announcement ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(announcement.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (announcement.pinned) {
                            Text(
                                "置顶",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Text(
                        announcement.content,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
