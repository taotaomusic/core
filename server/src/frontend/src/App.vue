<template>
  <el-config-provider :locale="zhCn">
    <!-- 首次登录必须改密：优先于后台界面，也优先于登录页 -->
    <ForcePasswordChange
      v-if="token && mustChangePassword"
      :token="token"
      @changed="onPasswordChanged"
      @logout="forgetToken"
    />

    <!-- 未登录：显示登录页 -->
    <AdminLogin v-else-if="askToken" @login="onLogin" />

    <!--
      本地有令牌、但身份（角色）还没确认：先停在加载态。

      页签可见性依赖 `adminInfo.role`，而它来自异步的 `adminMe`。如果这段时间直接渲染
      管理界面，标签栏会先按「角色未知」画一遍，等请求回来再把「管理员」「审计日志」
      插进去 —— 表现为页签凭空弹出、后面的页签整体右移。观察者账号更糟：角色未知时
      `undefined !== 'viewer'` 成立，会先闪出「用户与统计」「审计日志」再消失。
      宁可多等一个请求，也不要让标签栏画两遍。
    -->
    <div v-else-if="booting" class="boot-screen">
      <div class="boot-card">
        <el-icon class="boot-spinner"><Loading /></el-icon>
        <p>正在确认管理员身份…</p>
      </div>
    </div>

    <!-- 已登录且身份已确认：显示管理界面 -->
    <div v-else class="shell">
      <header class="topbar">
        <div class="brand">
          <img src="/favicon.png" class="logo-icon" alt="logo" />
          <h1>桃桃音乐管理中心</h1>
        </div>
        <div class="topbar-right">
          <el-button 
            class="theme-toggle"
            circle 
            text
            @click="toggleTheme()"
          >
            <el-icon><component :is="isDark ? Moon : Sunny" /></el-icon>
          </el-button>
          <el-tag v-if="!isMobile" size="default" type="success" effect="light" round class="status-tag">
            <el-icon><Check /></el-icon> {{ adminInfo?.display_name || adminInfo?.username }}
          </el-tag>
          <el-button type="danger" :link="!isMobile" :circle="isMobile" @click="forgetToken" class="logout-btn">
            <el-icon v-if="isMobile"><SwitchButton /></el-icon>
            <span v-else>退出登录</span>
          </el-button>
        </div>
      </header>

      <main class="body">
        <div class="main-container">
          <el-tabs v-model="tab" class="custom-tabs" type="border-card" :tab-position="isMobile ? 'top' : 'top'">
            <el-tab-pane name="releases" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Upload /></el-icon> <span v-show="!isMobile || tab === 'releases'">发布管理</span></div>
              </template>
              <ReleaseManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="patches" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Connection /></el-icon> <span v-show="!isMobile || tab === 'patches'">补丁管理</span></div>
              </template>
              <PatchManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="desktop" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Monitor /></el-icon> <span v-show="!isMobile || tab === 'desktop'">Windows 发布</span></div>
              </template>
              <DesktopReleaseManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="announcements" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Bell /></el-icon> <span v-show="!isMobile || tab === 'announcements'">公告管理</span></div>
              </template>
              <AnnouncementManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane v-if="adminInfo?.role !== 'viewer'" name="users" lazy>
              <template #label>
                <div class="tab-label"><el-icon><User /></el-icon> <span v-show="!isMobile || tab === 'users'">用户与统计</span></div>
              </template>
              <UserManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="supporters" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Key /></el-icon> <span v-show="!isMobile || tab === 'supporters'">AI 密钥管理</span></div>
              </template>
              <SupporterKeyManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane v-if="isSuperAdmin" name="admin-users" lazy>
              <template #label>
                <div class="tab-label"><el-icon><User /></el-icon> <span v-show="!isMobile || tab === 'admin-users'">管理员</span></div>
              </template>
              <AdminUserManager :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane v-if="adminInfo?.role !== 'viewer'" name="audit-log" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Connection /></el-icon> <span v-show="!isMobile || tab === 'audit-log'">审计日志</span></div>
              </template>
              <AuditLogViewer :admin-token="token" />
            </el-tab-pane>
            <el-tab-pane name="settings" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Setting /></el-icon> <span v-show="!isMobile || tab === 'settings'">系统设置</span></div>
              </template>
              <SystemSettings :admin-token="token" @forget-token="forgetToken" />
            </el-tab-pane>
          </el-tabs>
        </div>
      </main>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, ref, onMounted, onUnmounted, watch } from "vue";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { Check, Upload, Connection, Bell, User, Key, Setting, Lock, Moon, Sunny, SwitchButton, Monitor, Loading } from '@element-plus/icons-vue'
import { useDark, useToggle } from '@vueuse/core'
import AdminLogin from "./components/AdminLogin.vue";
import ForcePasswordChange from "./components/ForcePasswordChange.vue";
import { adminLogout, adminMe, type AdminIdentity } from "./api";

// 标签页使用异步组件：用户未打开的管理模块不进入首屏主包。
const ReleaseManager = defineAsyncComponent(() => import("./components/ReleaseManager.vue"));
const PatchManager = defineAsyncComponent(() => import("./components/PatchManager.vue"));
const DesktopReleaseManager = defineAsyncComponent(() => import("./components/DesktopReleaseManager.vue"));
const AnnouncementManager = defineAsyncComponent(() => import("./components/AnnouncementManager.vue"));
const UserManager = defineAsyncComponent(() => import("./components/UserManager.vue"));
const SupporterKeyManager = defineAsyncComponent(() => import("./components/SupporterKeyManager.vue"));
const SystemSettings = defineAsyncComponent(() => import("./components/SystemSettings.vue"));
const AdminUserManager = defineAsyncComponent(() => import("./components/AdminUserManager.vue"));
const AuditLogViewer = defineAsyncComponent(() => import("./components/AuditLogViewer.vue"));

const STORAGE_KEY = "taotao_admin_token";

const tab = ref("releases");
const token = ref("");
const askToken = ref(false);
const adminInfo = ref<AdminIdentity | null>(null);
/**
 * 账号仍在「首次登录必须改密」状态。
 *
 * 服务端在改密前会把其它管理接口一律拒成 403/4031，所以这里必须拦住界面，
 * 否则用户会看到一堆报错的页签而不是一个明确的改密入口。
 */
const mustChangePassword = ref(false);

/**
 * 正在用本地令牌确认身份。
 *
 * 为真时既不渲染登录页也不渲染管理界面，避免标签栏先画一遍「角色未知」的版本。
 * 见模板中 `booting` 分支的说明。
 */
const booting = ref(false);

/**
 * 角色判定集中在这里，不要在模板里散写 `adminInfo?.role === ...`。
 *
 * `adminInfo` 为 null 时 `adminInfo?.role !== 'viewer'` 求值为 `undefined !== 'viewer'`，
 * 结果是 true —— 角色未知会被当成「不是观察者」，两个受限页签会先亮一下再消失。
 * 这里显式要求角色已确认，未确认一律按最低权限处理。
 */
const role = computed(() => adminInfo.value?.role ?? "");
const isSuperAdmin = computed(() => role.value === "super_admin");
/** 个人数据类页签（用户与统计、审计日志）：观察者不可见，与后端 PRIVILEGED_READ_ROLES 对齐。 */
const canViewPersonalData = computed(() => role.value !== "" && role.value !== "viewer");

// 响应式状态
const isMobile = ref(false);

// 暗色主题控制
const isDark = useDark({
  storageKey: 'taotao_admin_theme',
  valueDark: 'dark',
  /* Element Plus 的暗黑模式靠 class='dark'，亮色就是没有这个 class */
  valueLight: '',
});
const toggleTheme = useToggle(isDark);

function checkMobile() {
  isMobile.value = window.innerWidth <= 768;
}

onMounted(() => {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored) {
    token.value = stored;
    // 先停在加载态：角色要等 adminMe 回来才知道，此时渲染管理界面会让页签画两遍。
    booting.value = true;
    // 尝试验证 token 有效性；同时取出强制改密标记，刷新页面后仍能停在改密页。
    adminMe(stored).then(info => {
      adminInfo.value = info;
      mustChangePassword.value = info.must_change_password === true;
    }).catch(() => {
      // token 无效，清除并显示登录页
      localStorage.removeItem(STORAGE_KEY);
      token.value = "";
      askToken.value = true;
    }).finally(() => {
      // 成功或失败都要收尾，否则校验失败时会卡在加载页上。
      booting.value = false;
    });
  } else {
    askToken.value = true;
  }

  checkMobile();
  window.addEventListener('resize', checkMobile);
});

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
});

function onLogin(data: { token: string; admin: AdminIdentity }) {
  token.value = data.token;
  adminInfo.value = data.admin;
  mustChangePassword.value = data.admin.must_change_password === true;
  localStorage.setItem(STORAGE_KEY, data.token);
  askToken.value = false;
  // 页签可见性随角色变化（用户与统计、管理员、审计日志）。上一个会话停在这些页签上时，
  // 换个低权限账号登录会落在一个不渲染的页签上，看到一片空白 —— 一律回到首个页签。
  tab.value = "releases";
}

/**
 * 强制改密完成。
 *
 * 重新拉一次 `me` 而不是直接把标记置 false：改密成功后服务端会撤销其它会话、
 * 保留当前这条，重新确认一次能顺带验证当前会话仍然有效。
 */
async function onPasswordChanged() {
  try {
    const info = await adminMe(token.value);
    adminInfo.value = info;
    mustChangePassword.value = info.must_change_password === true;
  } catch {
    // 会话在改密过程中失效（例如被别处撤销）：退回登录页重新来一次。
    await forgetToken();
  }
}

async function forgetToken() {
  try { await adminLogout(token.value); } catch { /* 忽略 */ }
  localStorage.removeItem(STORAGE_KEY);
  token.value = "";
  adminInfo.value = null;
  mustChangePassword.value = false;
  booting.value = false;
  askToken.value = true;
  tab.value = "releases";
}
</script>

<style>
:root {
  color-scheme: light;
  --primary-color: #ff6b81;
  --bg-color: #f5f7fa;
  --text-color: #303133;
  --card-bg: #ffffff;
  --topbar-bg: #ffffff;
  --border-color: #e4e7ed;
  --hint-bg: #f8f9fa;
  --code-bg: #e9ecef;
  --box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
  --header-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

html.dark {
  color-scheme: dark;
  --bg-color: #141414;
  --text-color: #E5EAF3;
  --card-bg: #1D1E1F;
  --topbar-bg: #1D1E1F;
  --border-color: #363637;
  --hint-bg: #2B2C2D;
  --code-bg: #3C3D3F;
  --box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  --header-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
}

body {
  margin: 0;
  background: var(--bg-color);
  color: var(--text-color);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  transition: background-color 0.3s ease;
}

/* 覆盖 Element Plus 的一些默认样式，使其更符合音乐应用的风格 */
.el-button--primary {
  --el-button-bg-color: var(--primary-color) !important;
  --el-button-border-color: var(--primary-color) !important;
  --el-button-hover-bg-color: #ff8598 !important;
  --el-button-hover-border-color: #ff8598 !important;
  --el-button-active-bg-color: #e55c70 !important;
  --el-button-active-border-color: #e55c70 !important;
}

/* 移动端横向滚动表格优化 */
.el-table {
  /* 允许在移动端手势横向滑动 */
  overflow-x: auto;
}
</style>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* 身份确认中的过渡页。保持与登录页一致的居中卡片观感，避免出现无样式的白屏。 */
.boot-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-color);
}

.boot-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: var(--el-text-color-secondary);
}

.boot-card p {
  margin: 0;
  font-size: 14px;
}

.boot-spinner {
  font-size: 28px;
  color: var(--primary-color);
  animation: boot-spin 1s linear infinite;
}

@keyframes boot-spin {
  to { transform: rotate(360deg); }
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 32px;
  background: var(--topbar-bg);
  box-shadow: var(--header-shadow);
  position: sticky;
  top: 0;
  z-index: 100;
  transition: background-color 0.3s ease;
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
  color: var(--el-text-color-primary);
  letter-spacing: 0.5px;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.theme-toggle {
  font-size: 18px;
  color: var(--el-text-color-regular);
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
  box-shadow: var(--box-shadow);
  border: 1px solid var(--border-color);
  background: var(--card-bg);
  overflow: hidden;
  transition: background-color 0.3s ease, border-color 0.3s ease;
}

:deep(.el-tabs__content) {
  padding: 24px;
  background: var(--card-bg);
  min-height: calc(100vh - 200px);
  transition: background-color 0.3s ease;
}

:deep(.el-tabs__header) {
  background-color: var(--el-fill-color-light) !important;
  border-bottom: 1px solid var(--border-color) !important;
  transition: background-color 0.3s ease;
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 500;
}

/* 响应式移动端适配 */
@media screen and (max-width: 768px) {
  .topbar {
    padding: 0 16px;
    height: 54px;
  }
  
  .brand h1 {
    font-size: 16px;
  }
  
  .topbar-right {
    gap: 8px;
  }
  
  .body {
    padding: 12px 0 24px;
  }
  
  :deep(.el-form-item) {
    flex-direction: column;
    align-items: flex-start;
    margin-bottom: 16px;
  }

  :deep(.el-form-item__label) {
    width: 100% !important;
    justify-content: flex-start;
    margin-bottom: 4px;
    line-height: 1.5;
    padding-bottom: 0;
  }

  :deep(.el-form-item__content) {
    margin-left: 0 !important;
    width: 100%;
  }

  :deep(.el-dialog) {
    width: 92% !important;
    margin: 20px auto !important;
  }

  :deep(.el-dialog__body) {
    padding: 20px 16px;
  }

  :deep(.el-descriptions__cell) {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 12px 16px !important;
  }

  :deep(.el-descriptions__label) {
    margin-right: 0 !important;
    color: var(--el-text-color-secondary);
  }

  .custom-tabs {
    border-radius: 0;
    border-left: none;
    border-right: none;
  }
  
  :deep(.el-tabs__item) {
    padding: 0 12px !important;
  }
  
  :deep(.el-tabs__content) {
    padding: 16px 12px;
  }
  
  .tab-label {
    font-size: 14px;
    flex-direction: column;
    gap: 2px;
    padding: 4px 0;
  }
  
  .tab-label .el-icon {
    font-size: 18px;
  }
  
  :deep(.el-table) {
    font-size: 13px;
  }
  
  :deep(.el-button--small) {
    padding: 5px 8px;
  }
}
</style>



