<template>
  <div class="login-shell">
    <!-- 背景装饰：两团柔和光晕 + 细网格，纯 CSS，不引外部资源（CSP font-src/img-src 都不放行外域） -->
    <div class="login-aurora" aria-hidden="true">
      <span class="aurora aurora-a"></span>
      <span class="aurora aurora-b"></span>
      <span class="grid"></span>
    </div>

    <div class="login-card">
      <div class="login-header">
        <span class="logo-badge">
          <img src="/favicon.png" class="login-logo" alt="logo" />
        </span>
        <h1>桃桃音乐管理中心</h1>
        <p class="login-sub">
          {{ step === "credentials" ? "请使用管理员账号登录" : "需要二次验证" }}
        </p>
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
        <p class="totp-hint">请输入验证器应用中的 6 位动态码</p>
        <el-input v-model="totpCode" placeholder="000000" size="large" maxlength="6"
          @keyup.enter="handleTotp" class="totp-input" />
        <el-button type="primary" size="large" :loading="loading" :disabled="totpCode.length !== 6"
          @click="handleTotp" class="submit-btn">
          验证并进入
        </el-button>
        <el-button link @click="step = 'credentials'" class="back-btn">返回登录</el-button>
      </el-form>

      <p v-if="error" class="error-text">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
      </p>
    </div>

    <p class="login-footer">桃桃音乐 · 服务端管理控制台</p>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { User, Lock, WarningFilled } from "@element-plus/icons-vue";
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
// 第二步要带上第一步签发的挑战票据，服务端靠它确认密码已经验过。
const tempToken = ref("");

async function handleLogin() {
  if (!username.value || !password.value) return;
  loading.value = true;
  error.value = "";
  try {
    const result = await adminLogin(username.value, password.value);
    if (result.requires_totp) {
      tempToken.value = result.temp_token ?? "";
      totpCode.value = "";
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
    const result = await adminTotpVerify(tempToken.value, totpCode.value);
    emit("login", { token: result.token, admin: result.admin });
  } catch (e) {
    error.value = (e as Error).message;
    // 票据是一次性的：无论动态码对错都已被服务端核销，只能退回第一步重来。
    tempToken.value = "";
    step.value = "credentials";
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  position: relative;
  overflow: hidden;
  background: var(--bg-color);
  padding: 24px;
  box-sizing: border-box;
}

/* ---------- 背景装饰 ---------- */

.login-aurora {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.aurora {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  opacity: 0.55;
}

.aurora-a {
  width: 520px;
  height: 520px;
  top: -180px;
  left: -120px;
  background: radial-gradient(circle, var(--primary-color), transparent 68%);
}

.aurora-b {
  width: 460px;
  height: 460px;
  bottom: -190px;
  right: -110px;
  background: radial-gradient(circle, #7aa8ff, transparent 68%);
  opacity: 0.4;
}

/* 细网格：给纯色背景一点肌理，透明到几乎看不见但能撑住观感 */
.grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--border-color) 1px, transparent 1px),
    linear-gradient(90deg, var(--border-color) 1px, transparent 1px);
  background-size: 48px 48px;
  opacity: 0.5;
  mask-image: radial-gradient(circle at 50% 45%, #000 0%, transparent 72%);
  -webkit-mask-image: radial-gradient(circle at 50% 45%, #000 0%, transparent 72%);
}

/* ---------- 登录卡片 ---------- */

.login-card {
  position: relative;
  width: 400px;
  max-width: 100%;
  padding: 40px 36px 32px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  /* 顶部一道主色高光，让卡片边缘不那么「平」 */
  background-image: linear-gradient(180deg, var(--primary-soft), transparent 120px);
  box-sizing: border-box;
}

.login-header {
  text-align: center;
  margin-bottom: 30px;
}

.logo-badge {
  display: inline-grid;
  place-items: center;
  width: 64px;
  height: 64px;
  margin-bottom: 18px;
  border-radius: 19px;
  background: linear-gradient(140deg, var(--primary-color), #ff9d6e);
  box-shadow: 0 10px 24px var(--primary-glow);
}

.login-logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  object-fit: contain;
}

.login-header h1 {
  margin: 0;
  font-size: 21px;
  font-weight: 650;
  letter-spacing: 0.3px;
  color: var(--el-text-color-primary);
}

.login-sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.submit-btn {
  width: 100%;
  margin-top: 10px;
  height: 44px;
  font-size: 15px;
  letter-spacing: 0.5px;
}

.totp-hint {
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 0 0 16px;
}

.totp-input :deep(.el-input__inner) {
  text-align: center;
  font-size: 26px;
  letter-spacing: 10px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  height: 52px;
}

.back-btn {
  display: block;
  margin: 14px auto 0;
}

/* 错误提示做成一块淡红卡片，比一行红字更容易被注意到 */
.error-text {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 16px 0 0;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border: 1px solid var(--el-color-danger-light-8);
  border-radius: var(--radius-sm);
}

.login-footer {
  position: relative;
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  letter-spacing: 0.4px;
}

@media screen and (max-width: 480px) {
  .login-card {
    padding: 32px 22px 26px;
    border-radius: var(--radius-lg);
  }

  .logo-badge {
    width: 56px;
    height: 56px;
    border-radius: 17px;
  }

  .login-logo {
    width: 34px;
    height: 34px;
  }

  .login-header h1 {
    font-size: 19px;
  }
}
</style>
