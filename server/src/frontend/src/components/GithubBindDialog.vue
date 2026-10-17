<template>
  <el-dialog v-model="visible" title="GitHub 登录" width="440px" @open="resetState">
    <!-- 未绑定：发起绑定 -->
    <template v-if="!props.githubLogin">
      <el-alert type="info" :closable="false" show-icon class="hint">
        绑定后即可在登录页用 GitHub 账号登录本账号。为安全起见，绑定前需要验证当前登录密码。
      </el-alert>
      <el-input v-model="password" type="password" placeholder="当前登录密码" size="large"
        show-password @keyup.enter="handleBind" />
      <el-button type="primary" size="large" class="action-btn" :loading="submitting"
        :disabled="!password" @click="handleBind">
        绑定 GitHub 账号
      </el-button>
    </template>

    <!-- 已绑定：展示状态并提供解绑入口 -->
    <template v-else>
      <el-alert type="success" :closable="false" show-icon class="hint">
        当前账号已绑定 GitHub 账号 <strong>{{ props.githubLogin }}</strong>，可在登录页用其登录。
      </el-alert>
      <el-divider content-position="left">解绑 GitHub</el-divider>
      <el-alert type="warning" :closable="false" show-icon class="hint">
        解绑后该 GitHub 账号将无法登录本账号。解绑需要再次输入登录密码确认。
      </el-alert>
      <el-input v-model="password" type="password" placeholder="当前登录密码" size="large"
        show-password @keyup.enter="handleUnbind" />
      <el-button type="danger" size="large" class="action-btn" :loading="submitting"
        :disabled="!password" @click="handleUnbind">
        确认解绑 GitHub
      </el-button>
    </template>

    <p v-if="error" class="error-text">{{ error }}</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { adminGithubBind, adminGithubUnbind } from "../api";

const props = defineProps<{
  /** 当前会话令牌。 */
  token: string;
  /** 本人当前绑定的 GitHub 登录名，未绑定为 null。 */
  githubLogin: string | null;
  /** v-model 显示开关。 */
  modelValue: boolean;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: boolean): void;
  /** 状态发生真实变化（解绑成功）后通知父级刷新身份信息。 */
  (e: "changed"): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit("update:modelValue", value),
});

const password = ref("");
const submitting = ref(false);
const error = ref("");

/** 每次打开都回到初始态，避免上一次的密码残留在输入框里。 */
function resetState() {
  password.value = "";
  submitting.value = false;
  error.value = "";
}

/** 发起绑定：验密码换授权 URL，然后整页跳转到 GitHub 完成绑定。 */
async function handleBind() {
  if (!password.value || submitting.value) return;
  submitting.value = true;
  error.value = "";
  try {
    const result = await adminGithubBind(props.token, password.value);
    // 绑定结果由 GitHub 回调带回管理后台；这里直接离开当前页。
    window.location.href = result.authorize_url;
  } catch (e) {
    error.value = (e as Error).message;
    submitting.value = false;
  }
}

/** 解绑：验密码后服务端清空 github_id 三列。 */
async function handleUnbind() {
  if (!password.value || submitting.value) return;
  submitting.value = true;
  error.value = "";
  try {
    await adminGithubUnbind(props.token, password.value);
    password.value = "";
    visible.value = false;
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.hint {
  margin-bottom: 16px;
}

.action-btn {
  width: 100%;
  margin-top: 14px;
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