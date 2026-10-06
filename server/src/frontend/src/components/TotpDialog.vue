<template>
  <el-dialog v-model="visible" title="两步验证" width="440px" @open="resetState">
    <!-- 开启成功态：changed 事件会让 totpEnabled 变真，done 必须先于它判断，
         否则成功页会被下方的「已开启」视图立刻顶掉 -->
    <template v-if="step === 'done'">
      <el-result icon="success" title="两步验证已开启"
        sub-title="下次登录需要输入验证器中的动态码，请确保验证器数据不会丢失。" />
    </template>

    <!-- 未开启：三步开启流程（验密码 → 扫码 → 输动态码确认） -->
    <template v-else-if="!props.totpEnabled">
      <template v-if="step === 'verify'">
        <el-alert type="info" :closable="false" show-icon class="hint">
          开启后，登录除密码外还需输入验证器应用中的 6 位动态码，可大幅降低密码泄露后被接管的风险。
        </el-alert>
        <el-input v-model="password" type="password" placeholder="当前登录密码" size="large"
          show-password @keyup.enter="handleEnable" />
        <el-button type="primary" size="large" class="action-btn" :loading="submitting"
          :disabled="!password" @click="handleEnable">
          下一步：扫码绑定
        </el-button>
      </template>

      <template v-else>
        <el-alert type="warning" :closable="false" show-icon class="hint">
          密钥只在此页展示一次。请用验证器 App（如 Google Authenticator）扫码；无法扫码时可手动输入密钥。
        </el-alert>
        <div class="qr-area">
          <img v-if="qrDataUrl" :src="qrDataUrl" class="qr-img" alt="TOTP 二维码" />
          <div v-else class="qr-fallback">
            <p>二维码生成失败，请手动添加以下 otpauth 链接：</p>
            <code class="secret-text">{{ otpauthUrl }}</code>
          </div>
        </div>
        <div class="secret-row">
          <span class="secret-label">密钥</span>
          <code class="secret-text">{{ secret }}</code>
          <el-button link type="primary" @click="copySecret">复制</el-button>
        </div>
        <el-input v-model="code" placeholder="输入验证器中的 6 位动态码" size="large" maxlength="6"
          class="code-input" @keyup.enter="handleConfirm" />
        <el-button type="primary" size="large" class="action-btn" :loading="submitting"
          :disabled="code.length !== 6" @click="handleConfirm">
          确认开启
        </el-button>
      </template>
    </template>

    <!-- 已开启：展示状态并提供关闭入口 -->
    <template v-else>
      <el-alert type="success" :closable="false" show-icon class="hint">
        当前账号已开启两步验证，登录时需要输入验证器中的动态码。
      </el-alert>
      <el-divider content-position="left">关闭两步验证</el-divider>
      <el-alert type="warning" :closable="false" show-icon class="hint">
        关闭后登录只校验密码。关闭操作需要再次输入登录密码确认。
      </el-alert>
      <el-input v-model="password" type="password" placeholder="当前登录密码" size="large"
        show-password @keyup.enter="handleDisable" />
      <el-button type="danger" size="large" class="action-btn" :loading="submitting"
        :disabled="!password" @click="handleDisable">
        确认关闭两步验证
      </el-button>
    </template>

    <p v-if="error" class="error-text">{{ error }}</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { toDataURL as qrToDataURL } from "qrcode";
import { adminTotpConfirm, adminTotpDisable, adminTotpEnable } from "../api";

const props = defineProps<{
  /** 当前会话令牌，三个接口都要带。 */
  token: string;
  /** 本人当前是否已开启两步验证，决定展示开启流程还是关闭入口。 */
  totpEnabled: boolean;
}>();

const emit = defineEmits<{
  /** 状态发生真实变化（开启成功或关闭成功）后通知父级刷新身份信息。 */
  (e: "changed"): void;
  (e: "update:modelValue", value: boolean): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit("update:modelValue", value),
});

/** 开启流程的三步：verify 验密码拿密钥 → scan 扫码输动态码 → done 完成。 */
const step = ref<"verify" | "scan" | "done">("verify");
const password = ref("");
const code = ref("");
const secret = ref("");
const otpauthUrl = ref("");
const qrDataUrl = ref("");
const submitting = ref(false);
const error = ref("");

/** 每次打开都回到初始态，避免上一次的密钥残留在界面上。 */
function resetState() {
  step.value = "verify";
  password.value = "";
  code.value = "";
  secret.value = "";
  otpauthUrl.value = "";
  qrDataUrl.value = "";
  submitting.value = false;
  error.value = "";
}

/** 第一步：验密码换 TOTP 密钥，并渲染成二维码。 */
async function handleEnable() {
  if (!password.value || submitting.value) return;
  submitting.value = true;
  error.value = "";
  try {
    const result = await adminTotpEnable(props.token, password.value);
    secret.value = result.secret;
    otpauthUrl.value = result.otpauth_url;
    // toDataURL 失败不阻塞流程：界面上还有 otpauth 链接与明文密钥两个兜底渠道。
    qrDataUrl.value = await qrToDataURL(result.otpauth_url, { width: 200, margin: 1 })
      .catch(() => "");
    password.value = "";
    step.value = "scan";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    submitting.value = false;
  }
}

/** 第二步：校验动态码后真正启用。 */
async function handleConfirm() {
  if (code.value.length !== 6 || submitting.value) return;
  submitting.value = true;
  error.value = "";
  try {
    await adminTotpConfirm(props.token, code.value);
    // 启用成功即刻销毁内存中的密钥材料，界面只保留完成态。
    secret.value = "";
    otpauthUrl.value = "";
    qrDataUrl.value = "";
    code.value = "";
    step.value = "done";
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    submitting.value = false;
  }
}

/** 关闭两步验证：验密码后服务端清空密钥。 */
async function handleDisable() {
  if (!password.value || submitting.value) return;
  submitting.value = true;
  error.value = "";
  try {
    await adminTotpDisable(props.token, password.value);
    password.value = "";
    visible.value = false;
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    submitting.value = false;
  }
}

async function copySecret() {
  try {
    await navigator.clipboard.writeText(secret.value);
  } catch {
    // 非安全上下文（HTTP 内网）下 clipboard API 不可用，退回 execCommand。
    const textarea = document.createElement("textarea");
    textarea.value = secret.value;
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand("copy");
    textarea.remove();
  }
}
</script>

<style scoped>
.hint {
  margin-bottom: 16px;
}

.hint + .el-input {
  margin-top: 4px;
}

.action-btn {
  width: 100%;
  margin-top: 14px;
}

.qr-area {
  display: flex;
  justify-content: center;
  padding: 8px 0 12px;
}

.qr-img {
  width: 200px;
  height: 200px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: #fff;
}

.qr-fallback {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  text-align: center;
  padding: 8px 0;
}

.qr-fallback p {
  margin: 0 0 8px;
}

.secret-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 12px;
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
}

.secret-label {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.secret-text {
  flex: 1;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  color: var(--el-text-color-primary);
  user-select: all;
}

.code-input :deep(.el-input__inner) {
  letter-spacing: 6px;
  font-variant-numeric: tabular-nums;
}

.error-text {
  margin: 12px 0 0;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border: 1px solid var(--el-color-danger-light-8);
  border-radius: var(--radius-sm);
}
</style>
