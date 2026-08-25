<template>
  <el-config-provider :locale="zhCn">
    <div class="shell">
      <header class="topbar">
        <div class="brand">
          <span class="dot" />
          <h1>桃桃音乐 · 后台管理</h1>
        </div>
        <el-tag v-if="token" size="small" type="success" effect="dark">已授权</el-tag>
      </header>

      <main class="body">
        <el-tabs v-if="token" v-model="tab">
          <el-tab-pane label="发布管理" name="releases" lazy>
            <ReleaseManager :admin-token="token" />
          </el-tab-pane>
          <el-tab-pane label="补丁管理" name="patches" lazy>
            <PatchManager :admin-token="token" />
          </el-tab-pane>
          <el-tab-pane label="公告管理" name="announcements" lazy>
            <AnnouncementManager :admin-token="token" />
          </el-tab-pane>
          <el-tab-pane label="AI 密钥管理" name="supporters" lazy>
            <SupporterKeyManager :admin-token="token" />
          </el-tab-pane>
          <el-tab-pane label="系统设置" name="settings" lazy>
            <SystemSettings :admin-token="token" @forget-token="forgetToken" />
          </el-tab-pane>
        </el-tabs>
      </main>

      <el-dialog
        v-model="askToken"
        title="输入管理令牌"
        width="420px"
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        :show-close="false"
      >
        <p class="dialog-hint">
          即服务端 <code>.env</code> 里的 <code>ADMIN_TOKEN</code>。只存在这台浏览器的
          localStorage 里，不会发给别处。
        </p>
        <el-input
          v-model="draft"
          type="password"
          show-password
          placeholder="ADMIN_TOKEN"
          @keyup.enter="acceptToken"
        />
        <template #footer>
          <el-button type="primary" :disabled="!draft.trim()" @click="acceptToken">进入</el-button>
        </template>
      </el-dialog>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import ReleaseManager from "./components/ReleaseManager.vue";
import PatchManager from "./components/PatchManager.vue";
import AnnouncementManager from "./components/AnnouncementManager.vue";
import SupporterKeyManager from "./components/SupporterKeyManager.vue";
import SystemSettings from "./components/SystemSettings.vue";

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
}

body {
  margin: 0;
  background: #f6f7f9;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
    "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
}
</style>

<style scoped>
.shell {
  min-height: 100vh;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #f0a13c;
}

.body {
  max-width: 1360px;
  margin: 0 auto;
  padding: 20px 24px 48px;
}

.dialog-hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

code {
  background: var(--el-fill-color-light);
  padding: 1px 5px;
  border-radius: 3px;
}
</style>
