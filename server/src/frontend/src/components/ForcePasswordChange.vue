<template>
  <div class="force-shell">
    <div class="force-card">
      <div class="force-header">
        <img src="/favicon.png" class="force-logo" alt="logo" />
        <h1>首次登录必须修改密码</h1>
      </div>

      <p class="force-hint">
        当前账号使用的是服务启动时生成的初始口令。为避免后台被接管，
        改密前无法访问其它管理功能。
      </p>

      <el-form @submit.prevent="handleSubmit">
        <el-form-item>
          <el-input v-model="oldPassword" type="password" placeholder="当前（初始）密码" size="large"
            prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item>
          <el-input v-model="newPassword" type="password" placeholder="新密码（至少 8 位）" size="large"
            prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item>
          <el-input v-model="confirmPassword" type="password" placeholder="确认新密码" size="large"
            prefix-icon="Lock" show-password @keyup.enter="handleSubmit" />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" :disabled="!canSubmit"
          @click="handleSubmit" class="submit-btn">
          修改密码并进入后台
        </el-button>
      </el-form>

      <p v-if="error" class="error-text">{{ error }}</p>

      <el-button link class="logout-btn" @click="emit('logout')">退出登录</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { Lock } from "@element-plus/icons-vue";
import { adminChangePassword } from "../api";

const props = defineProps<{ token: string }>();
const emit = defineEmits<{
  (e: "changed"): void;
  (e: "logout"): void;
}>();

const oldPassword = ref("");
const newPassword = ref("");
const confirmPassword = ref("");
const loading = ref(false);
const error = ref("");

// 只做「本地能判断的」校验；长度下限与「新旧不能相同」由服务端兜底，
// 避免两边规则各写一份、迟早不一致。
const canSubmit = computed(() =>
  Boolean(props.token) && Boolean(oldPassword.value) && newPassword.value.length >= 8
  && newPassword.value === confirmPassword.value);

async function handleSubmit() {
  if (!canSubmit.value) {
    if (newPassword.value.length < 8) error.value = "新密码至少 8 位";
    else if (newPassword.value !== confirmPassword.value) error.value = "两次输入的新密码不一致";
    return;
  }
  loading.value = true;
  error.value = "";
  try {
    await adminChangePassword(props.token, oldPassword.value, newPassword.value);
    oldPassword.value = "";
    newPassword.value = "";
    confirmPassword.value = "";
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.force-shell {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-color);
}

.force-card {
  width: 380px;
  padding: 40px 32px;
  background: var(--card-bg);
  border-radius: 12px;
  box-shadow: var(--box-shadow);
}

.force-header {
  text-align: center;
  margin-bottom: 20px;
}

.force-logo {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  margin-bottom: 16px;
  box-shadow: 0 4px 12px rgba(255, 107, 129, 0.2);
}

.force-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.force-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
  margin: 0 0 20px;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
}

.logout-btn {
  display: block;
  margin: 16px auto 0;
}

.error-text {
  color: var(--el-color-danger);
  text-align: center;
  margin-top: 12px;
  font-size: 13px;
}
</style>
