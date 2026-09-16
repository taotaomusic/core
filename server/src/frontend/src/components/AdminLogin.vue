<template>
  <div class="login-shell">
    <div class="login-card">
      <div class="login-header">
        <img src="/favicon.png" class="login-logo" alt="logo" />
        <h1>桃桃音乐管理中心</h1>
      </div>

      <!-- 步骤1: 用户名密码 -->
      <el-form v-if="step === 'credentials'" @submit.prevent="handleLogin">
        <el-form-item>
          <el-input v-model="username" placeholder="管理员用户名" size="large" prefix-icon="User" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码" size="large" prefix-icon="Lock"
            show-password @keyup.enter="handleLogin" />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" :disabled="!username || !password"
          @click="handleLogin" class="submit-btn">
          登录
        </el-button>
      </el-form>

      <!-- 步骤2: 2FA 验证 -->
      <el-form v-else-if="step === 'totp'" @submit.prevent="handleTotp">
        <p class="totp-hint">请输入验证器应用中的6位动态码</p>
        <el-input v-model="totpCode" placeholder="000000" size="large" maxlength="6"
          @keyup.enter="handleTotp" class="totp-input" />
        <el-button type="primary" size="large" :loading="loading" :disabled="totpCode.length !== 6"
          @click="handleTotp" class="submit-btn">
          验证
        </el-button>
        <el-button link @click="step = 'credentials'" class="back-btn">返回登录</el-button>
      </el-form>

      <p v-if="error" class="error-text">{{ error }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { User, Lock } from "@element-plus/icons-vue";
import { adminLogin, adminTotpVerify } from "../api";

const emit = defineEmits<{
  (e: "login", data: { token: string; admin: { id: number; username: string; role: string; display_name: string } }): void;
}>();

const step = ref<"credentials" | "totp">("credentials");
const username = ref("");
const password = ref("");
const totpCode = ref("");
const loading = ref(false);
const error = ref("");
const tempAdminId = ref(0);

async function handleLogin() {
  if (!username.value || !password.value) return;
  loading.value = true;
  error.value = "";
  try {
    const result = await adminLogin(username.value, password.value);
    if (result.requires_totp) {
      tempAdminId.value = result.admin_id!;
      step.value = "totp";
    } else if (result.token && result.admin) {
      emit("login", { token: result.token, admin: result.admin });
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

async function handleTotp() {
  if (totpCode.value.length !== 6) return;
  loading.value = true;
  error.value = "";
  try {
    const result = await adminTotpVerify(tempAdminId.value, totpCode.value);
    emit("login", { token: result.token, admin: result.admin });
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-color);
}

.login-card {
  width: 380px;
  padding: 40px 32px;
  background: var(--card-bg);
  border-radius: 12px;
  box-shadow: var(--box-shadow);
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-logo {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  margin-bottom: 16px;
  box-shadow: 0 4px 12px rgba(255, 107, 129, 0.2);
}

.login-header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
}

.totp-hint {
  text-align: center;
  color: var(--el-text-color-secondary);
  margin-bottom: 16px;
}

.totp-input :deep(.el-input__inner) {
  text-align: center;
  font-size: 24px;
  letter-spacing: 8px;
  font-family: monospace;
}

.back-btn {
  display: block;
  margin: 12px auto 0;
}

.error-text {
  color: var(--el-color-danger);
  text-align: center;
  margin-top: 12px;
  font-size: 13px;
}
</style>
