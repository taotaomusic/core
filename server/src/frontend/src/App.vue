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
          <span class="logo-badge">
            <img src="/favicon.png" class="logo-icon" alt="logo" />
          </span>
          <span class="brand-text">
            <h1>桃桃音乐</h1>
            <small>管理中心</small>
          </span>
        </div>
        <div class="topbar-right">
          <el-button 
            class="theme-toggle"
            circle 
            text
            title="切换亮色 / 暗色主题"
            @click="toggleTheme()"
          >
            <el-icon><component :is="isDark ? Moon : Sunny" /></el-icon>
          </el-button>
          <span v-if="!isMobile" class="identity">
            <span class="identity-avatar">{{ identityInitial }}</span>
            <span class="identity-text">
              <strong>{{ adminInfo?.display_name || adminInfo?.username }}</strong>
              <small>{{ roleLabel }}</small>
            </span>
          </span>
          <el-button type="danger" :link="!isMobile" :circle="isMobile" @click="forgetToken" class="logout-btn">
            <el-icon v-if="isMobile"><SwitchButton /></el-icon>
            <span v-else>退出登录</span>
          </el-button>
        </div>
      </header>

      <main class="body">
        <div class="main-container">
          <el-tabs v-model="tab" class="custom-tabs" :tab-position="isMobile ? 'top' : 'top'">
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
            <el-tab-pane name="open-api-keys" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Postcard /></el-icon> <span v-show="!isMobile || tab === 'open-api-keys'">开放 API Key</span></div>
              </template>
              <OpenApiKeyManager :admin-token="token" :role="role" />
            </el-tab-pane>
            <el-tab-pane v-if="canViewPersonalData" name="music-sources" lazy>
              <template #label>
                <div class="tab-label"><el-icon><Headset /></el-icon> <span v-show="!isMobile || tab === 'music-sources'">音源账号</span></div>
              </template>
              <MusicSourceManager :admin-token="token" />
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
import { Upload, Connection, Bell, User, Key, Setting, Moon, Sunny, SwitchButton, Monitor, Loading, Headset, Postcard } from '@element-plus/icons-vue'
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
const OpenApiKeyManager = defineAsyncComponent(() => import("./components/OpenApiKeyManager.vue"));
const MusicSourceManager = defineAsyncComponent(() => import("./components/MusicSourceManager.vue"));
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

/** 顶栏身份区的中文角色名。 */
const roleLabel = computed(() => {
  switch (role.value) {
    case "super_admin": return "超级管理员";
    case "admin": return "管理员";
    case "viewer": return "观察者";
    default: return "身份未知";
  }
});

/** 顶栏头像占位字符：取显示名/用户名的首字符，中英文都成立。 */
const identityInitial = computed(() => {
  const name = adminInfo.value?.display_name || adminInfo.value?.username || "?";
  return name.trim().charAt(0).toUpperCase();
});

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
/*
  设计令牌（Design Token）层。

  这些变量是整个后台唯一的视觉真相来源：颜色、圆角、阴影、间距、动效都只在这里定义，
  各组件一律引用变量而不是写死值 —— 否则暗色模式又会变成一处处漏改的硬编码。

  主色沿用音乐应用的珊瑚粉（#ff6b81），但补齐了 3/5/7/8/9 号色阶：
  Element Plus 的 tag / progress / switch / radio / tabs 等组件全部读 `--el-color-primary*`，
  只覆盖 `.el-button--primary` 会让「按钮是一套色、配件是另一套色」，这正是此前观感廉价的主因。
*/
:root {
  color-scheme: light;

  --primary-color: #ff6b81;
  --primary-hover: #ff8598;
  --primary-active: #e55c70;
  --primary-soft: rgba(255, 107, 129, 0.12);
  --primary-glow: rgba(255, 107, 129, 0.28);

  --bg-color: #f4f6fb;
  --text-color: #1f2430;
  --card-bg: #ffffff;
  --topbar-bg: rgba(255, 255, 255, 0.82);
  --border-color: #e6eaf2;
  --border-strong: #d8dfeb;
  --hint-bg: #f7f9fc;
  --code-bg: #eef1f7;

  /* 三级层次：贴地 / 浮起 / 弹层。只有拉开差距，界面才有纵深而不是「糊成一片」。 */
  --shadow-xs: 0 1px 2px rgba(16, 24, 40, 0.04);
  --shadow-sm: 0 1px 3px rgba(16, 24, 40, 0.06), 0 1px 2px rgba(16, 24, 40, 0.04);
  --box-shadow: 0 4px 12px rgba(16, 24, 40, 0.06), 0 1px 3px rgba(16, 24, 40, 0.04);
  --shadow-lg: 0 12px 32px rgba(16, 24, 40, 0.10), 0 2px 8px rgba(16, 24, 40, 0.05);
  --header-shadow: 0 1px 0 var(--border-color), 0 4px 16px rgba(16, 24, 40, 0.04);

  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 18px;
  --radius-xl: 24px;

  --ease-out: cubic-bezier(0.22, 1, 0.36, 1);
  --transition: 0.22s var(--ease-out);

  /*
    Element Plus 主色色阶。light-3/5/7/8/9 是它的「浅色变体」约定顺序：
    3 最浅、9 最深（用于 hover/active 的边框与文字）。留空会让组件回退到默认蓝。
  */
  --el-color-primary: var(--primary-color);
  --el-color-primary-light-3: #ff8fa1;
  --el-color-primary-light-5: #ffb0bd;
  --el-color-primary-light-7: #ffd0d8;
  --el-color-primary-light-8: #ffe0e5;
  --el-color-primary-light-9: #fff0f2;
  --el-color-primary-dark-2: var(--primary-active);

  --el-border-radius-base: var(--radius-sm);
  --el-box-shadow-light: var(--box-shadow);
}

html.dark {
  color-scheme: dark;

  --primary-hover: #ff8598;
  --primary-soft: rgba(255, 107, 129, 0.16);
  --primary-glow: rgba(255, 107, 129, 0.32);

  --bg-color: #0f1117;
  --text-color: #e8ecf4;
  --card-bg: #181b23;
  --topbar-bg: rgba(24, 27, 35, 0.82);
  --border-color: #2a2f3a;
  --border-strong: #363c4a;
  --hint-bg: #1e222b;
  --code-bg: #272c37;

  --shadow-xs: 0 1px 2px rgba(0, 0, 0, 0.3);
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.36);
  --box-shadow: 0 4px 12px rgba(0, 0, 0, 0.34), 0 1px 3px rgba(0, 0, 0, 0.24);
  --shadow-lg: 0 12px 32px rgba(0, 0, 0, 0.48), 0 2px 8px rgba(0, 0, 0, 0.3);
  --header-shadow: 0 1px 0 var(--border-color), 0 4px 16px rgba(0, 0, 0, 0.28);

  /* 暗色下浅色变体要往「更深」的方向走，否则标签底色会亮得刺眼。 */
  --el-color-primary-light-3: #d95a6d;
  --el-color-primary-light-5: #a84454;
  --el-color-primary-light-7: #6d2e39;
  --el-color-primary-light-8: #4c2129;
  --el-color-primary-light-9: #33171c;
  --el-color-primary-dark-2: #ff8598;
}

body {
  margin: 0;
  background: var(--bg-color);
  color: var(--text-color);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  transition: background-color 0.3s ease, color 0.3s ease;
}

/* ---------- 通用观感：滚动条、选中态 ---------- */

::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: var(--border-strong);
  border: 3px solid transparent;
  background-clip: content-box;
  border-radius: 999px;
}

::-webkit-scrollbar-thumb:hover {
  background: var(--el-text-color-disabled);
  background-clip: content-box;
}

::selection {
  background: var(--primary-soft);
}

/* ---------- 组件覆写 ---------- */

/*
  按钮：主色走变量，但顺带补上圆角与微交互 ——
  Element Plus 默认把按钮做成 4px 直角，是全站「生硬」观感的第二个来源。
*/
.el-button {
  border-radius: var(--radius-sm);
  font-weight: 500;
  transition: transform var(--transition), box-shadow var(--transition),
    background-color var(--transition), border-color var(--transition);
}

.el-button--primary {
  --el-button-bg-color: var(--primary-color);
  --el-button-border-color: var(--primary-color);
  --el-button-hover-bg-color: var(--primary-hover);
  --el-button-hover-border-color: var(--primary-hover);
  --el-button-active-bg-color: var(--primary-active);
  --el-button-active-border-color: var(--primary-active);
  --el-button-hover-link-text-color: var(--primary-hover);
  box-shadow: 0 2px 8px var(--primary-glow);
}

.el-button--primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 14px var(--primary-glow);
}

.el-button--primary:active {
  transform: translateY(0);
}

/* 次要按钮给一层极浅的底，避免和背景糊在一起 */
.el-button--default:not(.is-text):not(.is-link) {
  background: var(--card-bg);
  border-color: var(--border-strong);
}

.el-button--default:not(.is-text):not(.is-link):hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
  background: var(--primary-soft);
}

/*
  卡片：Element Plus 的 el-card 默认阴影很闷，这里统一成一个干净的浮起层级。
  所有管理页的「工具卡」「设置卡」都靠它统一。
*/
.el-card {
  border-radius: var(--radius-md);
  border: 1px solid var(--border-color);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.el-card__header {
  padding: 16px 20px;
  font-weight: 600;
  font-size: 15px;
  color: var(--el-text-color-primary);
  background: var(--hint-bg);
  border-bottom: 1px solid var(--border-color);
}

.el-card__body {
  padding: 20px;
}

/*
  表格：表头做成浅色区分 + 字重加强，行加 hover 高亮与底部细线。
  这是「专业感」性价比最高的一处 —— 后台 90% 的屏幕面积都是表格。
*/
.el-table {
  /* 允许在移动端手势横向滑动 */
  overflow-x: auto;
  border-radius: var(--radius-sm);
  --el-table-border-color: var(--border-color);
  --el-table-header-bg-color: var(--hint-bg);
  --el-table-header-text-color: var(--el-text-color-regular);
  --el-table-row-hover-bg-color: var(--primary-soft);
}

.el-table th.el-table__cell {
  font-weight: 600;
  font-size: 13px;
  letter-spacing: 0.02em;
}

.el-table td.el-table__cell,
.el-table th.el-table__cell {
  padding: 10px 0;
}

/* 数字类列用等宽字形，位数对齐后才看得出「统计」的样子 */
.el-table .el-table__cell.is-right .cell {
  font-variant-numeric: tabular-nums;
}

.el-table__empty-block {
  min-height: 180px;
}

.el-table__empty-text {
  color: var(--el-text-color-secondary);
}

/* 表格外框与圆角协调 */
.el-table--border,
.el-table--group {
  border-radius: var(--radius-sm);
}

/* 弹窗：更大的圆角与更明显的浮起层次 */
.el-dialog {
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}

.el-dialog__header {
  margin-right: 0;
  padding: 20px 24px 16px;
  border-bottom: 1px solid var(--border-color);
}

.el-dialog__title {
  font-size: 16px;
  font-weight: 600;
}

.el-dialog__body {
  padding: 20px 24px;
}

.el-dialog__footer {
  padding: 16px 24px 20px;
  border-top: 1px solid var(--border-color);
  background: var(--hint-bg);
}

.el-drawer {
  --el-drawer-padding-primary: 24px;
}

.el-drawer__header {
  margin-bottom: 0;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border-color);
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

/* 标签：默认是直角小块，加圆角后精致度明显不同 */
.el-tag {
  border-radius: 6px;
  font-weight: 500;
}

/* 弹层与下拉：统一阴影 + 圆角，避免和卡片风格脱节 */
.el-popper,
.el-select-dropdown,
.el-dropdown-menu,
.el-popover {
  border-radius: var(--radius-md) !important;
  box-shadow: var(--shadow-lg) !important;
  border-color: var(--border-color) !important;
}

.el-message-box {
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding-bottom: 16px;
}

.el-message {
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  border-color: var(--border-color);
}

/* 提示条：默认边框偏重，改成左侧色条 + 浅底，观感更轻 */
.el-alert {
  border-radius: var(--radius-md);
  padding: 12px 16px;
}

.el-alert--warning.is-light {
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-8);
}

.el-alert--info.is-light {
  background: var(--el-color-info-light-9);
  border: 1px solid var(--el-color-info-light-8);
}

/* 输入类控件统一圆角与内边距 */
.el-input__wrapper,
.el-textarea__inner {
  border-radius: var(--radius-sm);
  transition: box-shadow var(--transition);
}

.el-input__wrapper.is-focus,
.el-textarea__inner:focus {
  box-shadow: 0 0 0 1px var(--primary-color) inset, 0 0 0 3px var(--primary-soft);
}

/* 分页：当前页做成主色实心块 */
.el-pagination.is-background .el-pager li.is-active {
  background: var(--primary-color);
  box-shadow: 0 2px 8px var(--primary-glow);
}

.el-pagination.is-background .el-pager li {
  border-radius: 8px;
}

/* 统计数字：加大加粗并等宽，撑起「仪表盘」的量感 */
.el-statistic__number {
  font-size: 26px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
}

.el-statistic__head {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.el-descriptions__label.el-descriptions__cell.is-bordered-label {
  background: var(--hint-bg);
  font-weight: 500;
  color: var(--el-text-color-regular);
}

/* 进度条：轨道颜色跟随主题，圆头更柔和 */
.el-progress-bar__outer {
  border-radius: 999px;
  background: var(--border-color);
}

.el-progress-bar__inner {
  border-radius: 999px;
}

/* 描述列表项在抽屉里的间距 */
.el-descriptions {
  --el-descriptions-item-bordered-label-background: var(--hint-bg);
}

/* 表单标签字重统一，避免各页默认值不一致 */
.el-form-item__label {
  font-weight: 500;
}
</style>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  /* 极淡的双色光晕，给纯色背景一点空间感；固定在视口，滚动时不会跟着跑。 */
  background:
    radial-gradient(1200px 420px at 12% -8%, var(--primary-soft), transparent 62%),
    radial-gradient(900px 380px at 92% 0%, rgba(96, 165, 250, 0.10), transparent 58%);
  background-attachment: fixed;
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
  gap: 14px;
  padding: 36px 44px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  box-shadow: var(--box-shadow);
  color: var(--el-text-color-secondary);
}

.boot-card p {
  margin: 0;
  font-size: 14px;
}

.boot-spinner {
  font-size: 26px;
  color: var(--primary-color);
  animation: boot-spin 1s linear infinite;
}

@keyframes boot-spin {
  to { transform: rotate(360deg); }
}

/*
  顶栏：半透明 + backdrop-filter 做成毛玻璃，内容滚动时从下方透出层次。
  CSP 的 style-src 允许内联样式，所以这里不需要额外的权限。
*/
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 28px;
  background: var(--topbar-bg);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  box-shadow: var(--header-shadow);
  position: sticky;
  top: 0;
  z-index: 100;
  transition: background-color 0.3s ease, box-shadow 0.3s ease;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* logo 外框：带主色渐变的浅底，让品牌标识有「被设计过」的痕迹 */
.logo-badge {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 11px;
  background: linear-gradient(140deg, var(--primary-color), #ff9d6e);
  box-shadow: 0 4px 12px var(--primary-glow);
  flex-shrink: 0;
}

.logo-icon {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  object-fit: contain;
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.brand h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  color: var(--el-text-color-primary);
  letter-spacing: 0.2px;
  white-space: nowrap;
}

.brand-text small {
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
  padding-left: 8px;
  border-left: 1px solid var(--border-strong);
  white-space: nowrap;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.theme-toggle {
  font-size: 17px;
  color: var(--el-text-color-regular);
  transition: color var(--transition), background-color var(--transition), transform var(--transition);
}

.theme-toggle:hover {
  color: var(--primary-color);
  background: var(--primary-soft);
  transform: rotate(-18deg);
}

/* 身份区：头像色块 + 双行文字，比一个绿色 el-tag 更像「账号」而不是「状态」 */
.identity {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 5px 12px 5px 6px;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  background: var(--card-bg);
  box-shadow: var(--shadow-xs);
}

.identity-avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 650;
  color: #fff;
  background: linear-gradient(140deg, var(--primary-color), #ff9d6e);
  flex-shrink: 0;
}

.identity-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.identity-text strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.identity-text small {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.logout-btn {
  font-size: 13px;
}

.body {
  flex: 1;
  padding: 24px 28px 48px;
  display: flex;
  justify-content: center;
}

.main-container {
  width: 100%;
  max-width: 1560px;
}

/*
  页签容器：从 `type="border-card"`（灰底 + 外框）改为「卡片顶栏放页签、
  内容区无缝衔接」的现代后台样式。外层卡片负责圆角与阴影，页签自带下划线指示。
*/
.custom-tabs {
  border-radius: var(--radius-lg);
  box-shadow: var(--box-shadow);
  border: 1px solid var(--border-color);
  background: var(--card-bg);
  overflow: hidden;
  transition: background-color 0.3s ease, border-color 0.3s ease;
}

.el-tabs__header,
:deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
  background-color: var(--card-bg) !important;
  border-bottom: 1px solid var(--border-color) !important;
  transition: background-color 0.3s ease;
}

/* 页签本体：去掉默认的整块灰底，只保留底部指示条与 hover 反馈 */
:deep(.el-tabs__nav-wrap::after) {
  display: none;
}

:deep(.el-tabs__item) {
  height: 52px;
  line-height: 52px;
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-regular);
  transition: color var(--transition);
}

:deep(.el-tabs__item:hover) {
  color: var(--primary-color);
}

:deep(.el-tabs__item.is-active) {
  color: var(--primary-color);
  font-weight: 600;
}

:deep(.el-tabs__active-bar) {
  height: 3px;
  border-radius: 999px 999px 0 0;
  background-color: var(--primary-color);
}

:deep(.el-tabs__nav-wrap) {
  padding-bottom: 0;
}

:deep(.el-tabs__content) {
  padding: 22px 24px 26px;
  background: var(--card-bg);
  min-height: calc(100vh - 190px);
  transition: background-color 0.3s ease;
}

.tab-label {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 14px;
  font-weight: 500;
}

.tab-label .el-icon {
  font-size: 15px;
}

/* 响应式移动端适配 */
@media screen and (max-width: 768px) {
  .topbar {
    padding: 0 14px;
    height: 56px;
  }

  .logo-badge {
    width: 32px;
    height: 32px;
    border-radius: 10px;
  }

  .logo-icon {
    width: 21px;
    height: 21px;
  }

  .brand h1 {
    font-size: 15px;
  }

  .brand-text small {
    display: none;
  }

  .topbar-right {
    gap: 6px;
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

  .el-tabs__header,
  :deep(.el-tabs__header) {
    padding: 0 8px;
  }

  :deep(.el-tabs__item) {
    height: 58px;
    line-height: 1.3;
    padding: 0 10px !important;
  }

  :deep(.el-tabs__content) {
    padding: 16px 12px;
  }

  .tab-label {
    font-size: 12px;
    flex-direction: column;
    gap: 3px;
  }

  .tab-label .el-icon {
    font-size: 17px;
  }

  :deep(.el-table) {
    font-size: 13px;
  }

  :deep(.el-button--small) {
    padding: 5px 8px;
  }

  :deep(.el-card__body) {
    padding: 16px;
  }
}
</style>



