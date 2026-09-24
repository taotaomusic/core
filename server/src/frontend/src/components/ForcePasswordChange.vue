<template>
  <div class="force-shell">
    <div class="force-aurora" aria-hidden="true">
      <span class="aurora aurora-a"></span>
      <span class="aurora aurora-b"></span>
    </div>

    <div class="force-card">
      <div class="force-header">
        <span class="logo-badge">
          <img src="/favicon.png" class="force-logo" alt="logo" />
        </span>
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

        <!-- 实时反馈长度要求，避免点提交才知道哪里不合格 -->
        <ul class="rules">
          <li :class="{ ok: newPassword.length >= 8 }">
            <el-icon><component :is="newPassword.length >= 8 ? CircleCheckFilled : CircleClose" /></el-icon>
            至少 8 位
          </li>
          <li :class="{ ok: Boolean(newPassword) && newPassword === confirmPassword }">
            <el-icon>
              <component :is="newPassword && newPassword === confirmPassword ? CircleCheckFilled : CircleClose" />
            </el-icon>
            两次输入一致
          </li>
        </ul>

        <el-button type="primary" size="large" :loading="loading" :disabled="!canSubmit"
          @click="handleSubmit" class="submit-btn">
          修改密码并进入后台
        </el-button>
      </el-form>

      <p v-if="error" class="error-text">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
      </p>

      <el-button link class="logout-btn" @click="emit('logout')">退出登录</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { Lock, WarningFilled, CircleCheckFilled, CircleClose } from "@element-plus/icons-vue";
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
  position: relative;
  overflow: hidden;
  background: var(--bg-color);
  padding: 24px;
  box-sizing: border-box;
}

.force-aurora {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.aurora {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  opacity: 0.5;
}

.aurora-a {
  width: 480px;
  height: 480px;
  top: -170px;
  left: -130px;
  background: radial-gradient(circle, var(--primary-color), transparent 68%);
}

.aurora-b {
  width: 420px;
  height: 420px;
  bottom: -180px;
  right: -120px;
  background: radial-gradient(circle, #7aa8ff, transparent 68%);
  opacity: 0.35;
}

.force-card {
  position: relative;
  width: 400px;
  max-width: 100%;
  padding: 40px 36px 28px;
  background: var(--card-bg);
  background-image: linear-gradient(180deg, var(--primary-soft), transparent 120px);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  box-sizing: border-box;
}

.force-header {
  text-align: center;
  margin-bottom: 20px;
}

.logo-badge {
  display: inline-grid;
  place-items: center;
  width: 60px;
  height: 60px;
  margin-bottom: 16px;
  border-radius: 18px;
  background: linear-gradient(140deg, var(--primary-color), #ff9d6e);
  box-shadow: 0 10px 24px var(--primary-glow);
}

.force-logo {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  object-fit: contain;
}

.force-header h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 650;
  color: var(--el-text-color-primary);
}

.force-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.65;
  margin: 0 0 20px;
  padding: 12px 14px;
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
}

/* 密码规则实时反馈 */
.rules {
  list-style: none;
  display: flex;
  gap: 18px;
  margin: -4px 0 14px;
  padding: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.rules li {
  display: flex;
  align-items: center;
  gap: 5px;
  transition: color var(--transition);
}

.rules li.ok {
  color: var(--el-color-success);
}

.submit-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  margin-top: 4px;
}

.logout-btn {
  display: block;
  margin: 18px auto 0;
}

.error-text {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 14px 0 0;
  padding: 10px 14px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border: 1px solid var(--el-color-danger-light-8);
  border-radius: var(--radius-sm);
}

@media screen and (max-width: 480px) {
  .force-card {
    padding: 32px 22px 24px;
    border-radius: var(--radius-lg);
  }

  .logo-badge {
    width: 54px;
    height: 54px;
    border-radius: 16px;
  }

  .force-logo {
    width: 34px;
    height: 34px;
  }
}
</style>
