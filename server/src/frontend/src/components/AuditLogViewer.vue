<template>
  <div class="manager">
    <div class="bar">
      <el-select v-model="filterAction" clearable placeholder="全部操作类型" style="width: 180px">
        <el-option label="登录" value="auth.login" />
        <el-option label="TOTP登录" value="auth.login_totp" />
        <el-option label="修改密码" value="auth.change_password" />
        <el-option label="发布版本" value="release.publish" />
        <el-option label="调整放量" value="release.rollout" />
        <el-option label="禁用用户" value="user.disable" />
        <el-option label="删除用户" value="user.delete" />
        <el-option label="发布公告" value="announcement.publish" />
      </el-select>
      <el-button @click="fetchLogs" :loading="loading">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill">共 <strong>{{ total }}</strong> 条记录</span>
    </div>

    <el-table :data="logs" v-loading="loading" empty-text="暂无审计日志">
      <el-table-column prop="id" label="ID" width="80" sortable />
      <el-table-column label="管理员" width="130">
        <template #default="{ row }">{{ adminName(row.admin_id) }}</template>
      </el-table-column>
      <el-table-column prop="action" label="操作" width="160">
        <template #default="{ row }">
          <el-tag size="small" effect="light" :type="actionTag(row.action)">{{ actionLabel(row.action) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="目标" width="140">
        <template #default="{ row }">
          <span v-if="row.target_type">{{ row.target_type }} #{{ row.target_id }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="ip_address" label="IP" width="140" />
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="详情" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.detail || "—" }}</template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination v-model:current-page="page" :page-size="50" :total="total"
        layout="prev, pager, next" background @current-change="fetchLogs" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { Refresh } from "@element-plus/icons-vue";
import { apiGet, formatTime } from "../api";

type AuditLog = {
  id: number; admin_id: number; action: string; target_type: string | null;
  target_id: string | null; detail: string | null; ip_address: string | null;
  created_at: number;
};

const props = defineProps<{ adminToken: string }>();
const logs = ref<AuditLog[]>([]);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const filterAction = ref("");
const adminMap = ref<Record<number, string>>({});

onMounted(() => { fetchLogs(); fetchAdmins(); });

watch(filterAction, () => { page.value = 1; fetchLogs(); });

async function fetchLogs() {
  loading.value = true;
  try {
    const params = new URLSearchParams({ limit: "50", offset: String((page.value - 1) * 50) });
    if (filterAction.value) params.set("action", filterAction.value);
    const data = await apiGet<{ items: AuditLog[]; total: number }>(`/admin/auth/audit-log?${params}`, props.adminToken);
    logs.value = data.items;
    total.value = data.total;
  } catch (e) {
    ElMessage.error(`加载失败：${(e as Error).message}`);
  } finally {
    loading.value = false;
  }
}

async function fetchAdmins() {
  try {
    const users = await apiGet<Array<{ id: number; username: string }>>("/admin/auth/users", props.adminToken);
    const map: Record<number, string> = {};
    for (const u of users) map[u.id] = u.username;
    adminMap.value = map;
  } catch { /* 忽略 */ }
}

function adminName(id: number) { return adminMap.value[id] ?? `#${id}`; }

function actionTag(action: string) {
  if (action.startsWith("auth.")) return "info";
  if (action.startsWith("release.")) return "success";
  if (action.includes("delete") || action.includes("disable")) return "danger";
  return "warning";
}

function actionLabel(action: string) {
  const map: Record<string, string> = {
    "auth.login": "登录", "auth.login_totp": "TOTP登录", "auth.change_password": "修改密码",
    "release.publish": "发布版本", "release.rollout": "调整放量", "release.toggle": "启用停用",
    "user.disable": "禁用用户", "user.delete": "删除用户",
    "announcement.publish": "发布公告", "announcement.delete": "删除公告",
  };
  return map[action] ?? action;
}
</script>

<style scoped>
.manager {
  padding: 4px 0;
}

.bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.bar-spacer { flex: 1; min-width: 8px; }

.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 13px;
  font-size: 13px;
  line-height: 1;
  color: var(--el-text-color-secondary);
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  white-space: nowrap;
}

.pill strong { color: var(--el-text-color-primary); font-weight: 650; }

.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}
</style>
