package com.taotao.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taotao.music.data.TencentMusicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** 与服务端 /api/v1/auth/register 完全一致的用户名规则：3 至 32 位中英文、数字或下划线。 */
private val USERNAME_PATTERN = Regex("^[\\w\\u4e00-\\u9fa5]{3,32}$")

/** 用户名上限，同时用于限制输入长度，避免输入完才提示超长。 */
private const val MAX_USERNAME_LENGTH = 32

/** 服务端要求的最短密码长度。 */
private const val MIN_PASSWORD_LENGTH = 6
private val QQ_EMAIL_PATTERN = Regex("^[^\\s@]+@(qq\\.com|foxmail\\.com)$", RegexOption.IGNORE_CASE)

/**
 * 登录与注册页面：只负责收集凭据，令牌保存由调用方的会话层完成。
 *
 * 校验规则与服务端保持一致并就地提示，避免提交后才报错；
 * 注册模式额外要求确认密码，防止密码输错后账号再也进不去。
 */
@Composable
fun AuthPage(
    api: TencentMusicApi,
    darkTheme: Boolean = isSystemInDarkTheme(),
    onAuthenticated: (TencentMusicApi.TokenPair) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var registerMode by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 只在字段已有输入时提示，刚进页面不会满屏红字。
    val usernameError = "用户名需为 3 至 32 位".takeIf { username.isNotEmpty() && !USERNAME_PATTERN.matches(username) }
    val passwordError = "密码至少 $MIN_PASSWORD_LENGTH 位".takeIf { password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH }
    val confirmError = "两次输入的密码不一致".takeIf { registerMode && confirmPassword.isNotEmpty() && confirmPassword != password }
    val emailError = "请输入 QQ 邮箱或 foxmail.com 邮箱".takeIf { registerMode && email.isNotEmpty() && !QQ_EMAIL_PATTERN.matches(email) }
    val verificationError = "请输入 6 位验证码".takeIf { registerMode && verificationCode.isNotEmpty() && verificationCode.length != 6 }

    val canSubmit = !loading &&
        USERNAME_PATTERN.matches(username) &&
        password.length >= MIN_PASSWORD_LENGTH &&
        (!registerMode || (confirmPassword == password && QQ_EMAIL_PATTERN.matches(email) && verificationCode.length == 6))

    fun submit() {
        if (!canSubmit) return
        loading = true
        message = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (registerMode) api.register(username, password, email, verificationCode) else api.login(username, password)
                }
            }.onSuccess(onAuthenticated).onFailure { message = it.readableMessage() }
            loading = false
        }
    }

    TaotaoTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 系统栏与输入法都会遮挡内容，配合滚动保证小屏弹出键盘后按钮仍可点。
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(56.dp))
                // 品牌标识复用列表和播放页的圆形封面组件，保持视觉语言统一。
                AlbumArt(TaotaoCoral, 88.dp, 44.sp)
                Spacer(Modifier.height(20.dp))
                Text("桃桃音乐", fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (registerMode) "注册后即可收藏和离线下载" else "登录后同步你的收藏与下载",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(30.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(20.dp),
                ) {
                    OutlinedTextField(
                        value = username,
                        // 服务端不接受空白字符，输入时就过滤掉，避免粘贴带空格的用户名后被拒。
                        onValueChange = { value ->
                            username = value.filterNot(Char::isWhitespace).take(MAX_USERNAME_LENGTH)
                            message = null
                        },
                        label = { Text("用户名") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        isError = usernameError != null,
                        supportingText = { Text(usernameError ?: "支持中英文、数字和下划线") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; message = null },
                        label = { Text("密码") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (passwordVisible) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        isError = passwordError != null,
                        supportingText = { Text(passwordError ?: "至少 $MIN_PASSWORD_LENGTH 位") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        enabled = !loading,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (registerMode) ImeAction.Next else ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    // 明确只使用透明度与位移：默认 AnimatedVisibility 会展开高度，表单布局会在输入时抖动。
                    AnimatedVisibility(
                        visible = registerMode,
                        enter = fadeIn(animationSpec = taotaoTween(AnimationDurations.SHORT)) +
                            slideInVertically(
                                animationSpec = taotaoTween(
                                    AnimationDurations.SHORT,
                                    easing = AnimationCurves.emphasizedIn,
                                ),
                            ) { height -> height / 12 },
                        exit = fadeOut(animationSpec = taotaoTween(AnimationDurations.MICRO)) +
                            slideOutVertically(
                                animationSpec = taotaoTween(
                                    AnimationDurations.MICRO,
                                    easing = AnimationCurves.emphasizedIn,
                                ),
                            ) { height -> height / 12 },
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it; message = null },
                                label = { Text("确认密码") },
                                leadingIcon = { Icon(Icons.Default.Lock, null) },
                                isError = confirmError != null,
                                supportingText = { Text(confirmError ?: "再次输入以确认") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true,
                                enabled = !loading,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it.trim(); message = null },
                                label = { Text("QQ 邮箱") },
                                leadingIcon = { Icon(Icons.Default.Email, null) },
                                isError = emailError != null,
                                supportingText = { Text(emailError ?: "用于验证账号与找回凭据") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true,
                                enabled = !loading,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = verificationCode,
                                    onValueChange = { verificationCode = it.filter(Char::isDigit).take(6); message = null },
                                    label = { Text("邮箱验证码") },
                                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                                    isError = verificationError != null,
                                    supportingText = { Text(verificationError ?: "验证码有效期 10 分钟") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                    singleLine = true,
                                    enabled = !loading,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submit() }),
                                )
                                TextButton(
                                    enabled = !loading && QQ_EMAIL_PATTERN.matches(email),
                                    onClick = {
                                        loading = true
                                        message = null
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { api.sendRegistrationVerification(email) } }
                                                .onSuccess { message = "验证码已发送，请查收邮箱" }
                                                .onFailure { message = it.readableMessage() }
                                            loading = false
                                        }
                                    },
                                ) { Text("发送验证码") }
                            }
                        }
                    }
                }
                message?.let { AuthErrorBanner(it) }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = ::submit,
                    enabled = canSubmit,
                    // 固定高度，切换到加载态时按钮不会变矮。
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            // 默认取主色，与珊瑚红按钮同色会看不见，这里改用按钮前景色。
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(if (registerMode) "注册并登录" else "登录", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(
                    enabled = !loading,
                    onClick = {
                        registerMode = !registerMode
                        // 切换模式时清掉确认密码和上一次的报错，避免残留状态误导。
                        confirmPassword = ""
                        email = ""
                        verificationCode = ""
                        message = null
                    },
                ) { Text(if (registerMode) "已有账号？返回登录" else "还没有账号？立即注册", fontSize = 14.sp) }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** 服务端返回的错误提示：用配色和图标区分于字段级校验，避免与输入提示混在一起。 */
@Composable
private fun AuthErrorBanner(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        // 占满剩余宽度，较长的服务端文案换行显示而不是被裁掉。
        Text(
            text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
    }
}

/**
 * 把异常转成用户能看懂的提示。
 *
 * 网络故障时 [IOException] 携带的是主机名解析之类的英文信息，直接展示等于没有提示，
 * 统一引导检查网络；服务端明确返回的中文文案（如「用户名已存在」）保持原样。
 */
fun Throwable.readableMessage(): String = when (this) {
    is IOException -> "网络连接失败，请检查网络后重试"
    else -> message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
}
