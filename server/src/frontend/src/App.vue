<template>
  <el-config-provider :locale="zhCn">
    <div class="shell">
      <header class="topbar">
        <div class="brand">
          <img src="/favicon.png" class="logo-icon" alt="logo" />
          <h1>桃桃音乐管理中心</h1>
        </div>
        <div class="topbar-right">
          <el-tag v-if="token" size="default" type="success" effect="light" round class="status-tag">
            <el-icon><Check /></el-icon> 已授权连接
          </el-tag>
          <el-button v-if="token" type="danger" link @click="forgetToken" class="logout-btn">
            退出登录
          </el-button>
        </div>
      </header>

      <main class="body">
        <div class="main-container">
          <el-tabs v-if="token" v-model="tab" class="custom-tabs" type="border-card">
            <el-tab-pane name="releases" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Upload /></el-icon> 发布管理</div>
              </template>
              <ReleaseManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="patches" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Connection /></el-icon> 补丁管理</div>
              </template>
              <PatchManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="announcements" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Bell /></el-icon> 公告管理</div>
              </template>
              <AnnouncementManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="users" lazy>
              <template #label>
                <div class="tab-label"><el-icon><User /></el-icon> 用户与统计</div>
              </template>
              <UserManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="supporters" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Key /></el-icon> AI 密钥管理</div>
              </template>
              <SupporterKeyManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="settings" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Setting /></el-icon> 系统设置</div>
              </template>
              <SystemSettings :admin-token="token" @forget-token="forgetToken" />
            </el-tab-pane>
          </el-tabs>
        </div>
      </main>

      <el-dialog
        v-model="askToken"
        title="身份验证"
        width="420px"
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        :show-close="false"
        class="login-dialog"
        align-center
      >
        <div class="login-header">
          <img src="/favicon.png" class="login-logo" alt="logo" />
          <h2>桃桃音乐管理中心</h2>
        </div>
        <p class="dialog-hint">
          请输入系统管理令牌（服务端 <code>.env</code> 里的 <code>ADMIN_TOKEN</code>）。
          该令牌仅保存在当前浏览器的本地存储中。
        </p>
        <el-input
          v-model="draft"
          type="password"
          show-password
          placeholder="请输入 ADMIN_TOKEN"
          size="large"
          prefix-icon="Lock"
          @keyup.enter="acceptToken"
          class="token-input"
        />
        <template #footer>
          <el-button type="primary" size="large" class="login-submit" :disabled="!draft.trim()" @click="acceptToken">
            验证并登录
          </el-button>
        </template>
      </el-dialog>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { defineAsyncComponent, ref, onMounted } from "vue";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { Check, Upload, Connection, Bell, User, Key, Setting, Lock } from '@element-plus/icons-vue'

// 标签页使用异步组件：用户未打开的管理模块不进入首屏主包。
const ReleaseManager = defineAsyncComponent(() => import("./components/ReleaseManager.vue"));
const PatchManager = defineAsyncComponent(() => import("./components/PatchManager.vue"));
const AnnouncementManager = defineAsyncComponent(() => import("./components/AnnouncementManager.vue"));
const UserManager = defineAsyncComponent(() => import("./components/UserManager.vue"));
const SupporterKeyManager = defineAsyncComponent(() => import("./components/SupporterKeyManager.vue"));
const SystemSettings = defineAsyncComponent(() => import("./components/SystemSettings.vue"));

const STORAGE_KEY = "taotao_admin_token";

const tab = ref("releases");
const token = ref("");
const draft = ref("");
const askToken = ref(false);

onMounted(() => {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored) token.value = stored;
  else askToken.value = true;
});

function acceptToken() {
  const value = draft.value.trim();
  if (!value) return;
  token.value = value;
  localStorage.setItem(STORAGE_KEY, value);
  draft.value = "";
  askToken.value = false;
}

function forgetToken() {
  localStorage.removeItem(STORAGE_KEY);
  token.value = "";
  askToken.value = true;
}
</script>

<style>
:root {
  color-scheme: light;
  --primary-color: #ff6b81;
  --bg-color: #f5f7fa;
}

body {
  margin: 0;
  background: var(--bg-color);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
}

/* 覆盖 Element Plus 的一些默认样式，使其更符合音乐应用的风格 */
.el-button--primary {
  --el-button-bg-color: var(--primary-color);
  --el-button-border-color: var(--primary-color);
  --el-button-hover-bg-color: #ff8598;
  --el-button-hover-border-color: #ff8598;
  --el-button-active-bg-color: #e55c70;
  --el-button-active-border-color: #e55c70;
}

.el-tabs--border-card > .el-tabs__header .el-tabs__item.is-active {
  color: var(--primary-color);
}

.el-tabs--border-card > .el-tabs__header .el-tabs__item:not(.is-disabled):hover {
  color: var(--primary-color);
}
</style>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 32px;
  background: #ffffff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  position: sticky;
  top: 0;
  z-index: 100;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-icon {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  object-fit: contain;
}

.brand h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #2c3e50;
  letter-spacing: 0.5px;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.status-tag {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  height: 28px;
}

.logout-btn {
  font-size: 14px;
}

.body {
  flex: 1;
  padding: 24px 32px 48px;
  display: flex;
  justify-content: center;
}

.main-container {
  width: 100%;
  max-width: 1440px;
}

.custom-tabs {
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
  border: none;
  overflow: hidden;
}

:deep(.el-tabs__content) {
  padding: 24px;
  background: #fff;
  min-height: calc(100vh - 200px);
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 500;
}

/* 登录弹窗样式优化 */
.login-dialog :deep(.el-dialog__header) {
  display: none;
}

.login-dialog :deep(.el-dialog__body) {
  padding: 40px 32px 20px;
}

.login-header {
  text-align: center;
  margin-bottom: 24px;
}

.login-logo {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  margin-bottom: 16px;
  box-shadow: 0 4px 12px rgba(255, 107, 129, 0.2);
}

.login-header h2 {
  margin: 0;
  font-size: 22px;
  color: #303133;
  font-weight: 600;
}

.dialog-hint {
  margin: 0 0 24px;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
  text-align: center;
  background: #f8f9fa;
  padding: 12px;
  border-radius: 6px;
}

.dialog-hint code {
  background: #e9ecef;
  padding: 2px 6px;
  border-radius: 4px;
  color: #e83e8c;
  font-family: Monaco, Consolas, monospace;
}

.token-input {
  margin-bottom: 8px;
}

.login-submit {
  width: 100%;
  font-size: 16px;
  border-radius: 6px;
}

.login-dialog :deep(.el-dialog__footer) {
  padding: 0 32px 40px;
  border-top: none;
}
</style>
