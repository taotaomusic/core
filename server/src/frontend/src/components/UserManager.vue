<template>
  <div class="manager">
    <div class="bar">
      <el-input
        v-model="query"
        clearable
        placeholder="搜索用户名、昵称或邮箱"
        @keyup.enter="search"
        @clear="search"
        class="search-input"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="search">搜索</el-button>
      <el-button :loading="loading" @click="fetchUsers">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill">
        <span class="pill-dot"></span>
        共 <strong>{{ total }}</strong> 位用户
      </span>
    </div>

    <el-table :data="users" v-loading="loading" empty-text="没有匹配的用户">
      <el-table-column prop="id" label="ID" width="72" />
      <el-table-column label="用户" min-width="200">
        <template #default="{ row }">
          <!-- 服务端已下发 avatarUrl，此前一直没用上；没有头像时退回首字母色块 -->
          <div class="user-cell">
            <el-avatar :size="32" :src="row.avatarUrl || undefined" class="user-avatar">
              {{ (row.nickname || row.username || "?").charAt(0).toUpperCase() }}
            </el-avatar>
            <div class="user-meta">
              <div class="user-name">{{ row.nickname }}</div>
              <div class="muted">@{{ row.username }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="210">
        <template #default="{ row }">{{ row.email || "未绑定" }}</template>
      </el-table-column>
      <el-table-column label="状态" width="86">
        <template #default="{ row }">
          <el-tag :type="row.disabledAt ? 'danger' : 'success'" size="small" effect="light">
            {{ row.disabledAt ? "已禁用" : "正常" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="歌曲" width="84" align="right">
        <template #default="{ row }">{{ row.songCount }}</template>
      </el-table-column>
      <el-table-column label="有效播放" width="100" align="right">
        <template #default="{ row }">{{ row.playCount }}</template>
      </el-table-column>
      <el-table-column label="播完" width="80" align="right">
        <template #default="{ row }">{{ row.completedCount }}</template>
      </el-table-column>
      <el-table-column label="累计听歌" width="120" align="right">
        <template #default="{ row }">{{ formatDuration(row.totalListenedMs) }}</template>
      </el-table-column>
      <el-table-column label="最后同步" min-width="165">
        <template #default="{ row }">{{ formatTime(row.lastPlayedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" text @click="openDetail(row)">查看</el-button>
          <el-button size="small" :type="row.disabledAt ? 'success' : 'warning'" text @click="toggleDisabled(row)">
            {{ row.disabledAt ? "恢复" : "禁用" }}
          </el-button>
          <el-button size="small" type="danger" text @click="removeUser(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <span>共 {{ total }} 位用户</span>
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        background
        @current-change="fetchUsers"
      />
    </div>

    <el-drawer v-model="detailVisible" :title="detailTitle" size="min(760px, 96vw)" destroy-on-close>
      <template v-if="detail" #default>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="用户名">{{ detail.user.username }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ detail.user.nickname }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ detail.user.email || "未绑定" }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ detail.user.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="账号状态">
            <el-tag :type="detail.user.disabledAt ? 'danger' : 'success'" size="small">
              {{ detail.user.disabledAt ? "已禁用" : "正常" }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-row :gutter="12" class="stats">
          <el-col :span="6"><div class="stat-card"><el-statistic title="听过歌曲" :value="detail.stats.songCount" /></div></el-col>
          <el-col :span="6"><div class="stat-card"><el-statistic title="有效播放" :value="detail.stats.playCount" /></div></el-col>
          <el-col :span="6"><div class="stat-card"><el-statistic title="完整播放" :value="detail.stats.completedCount" /></div></el-col>
          <el-col :span="6"><div class="stat-card"><el-statistic title="累计听歌" :value="formatDuration(detail.stats.totalListenedMs)" /></div></el-col>
        </el-row>
        <p class="hint detail-hint">
          首次听歌：{{ formatTime(detail.stats.firstPlayedAt) }}；最近同步：{{ formatTime(detail.stats.lastPlayedAt) }}。
          最近播放清空版本：{{ detail.history.revision }}。
        </p>

        <h3>最近播放（最多 50 首）</h3>
        <el-table :data="detail.history.entries" size="small" empty-text="暂无已满足 3 秒条件的最近播放">
          <el-table-column prop="source" label="来源" width="100">
            <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.source }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="songId" label="歌曲 ID" min-width="180" show-overflow-tooltip />
          <el-table-column label="播放" width="70" align="right">
            <template #default="{ row }">{{ row.playCount }}</template>
          </el-table-column>
          <el-table-column label="播完" width="70" align="right">
            <template #default="{ row }">{{ row.completedCount }}</template>
          </el-table-column>
          <el-table-column label="时长" width="105" align="right">
            <template #default="{ row }">{{ formatDuration(row.totalListenedMs) }}</template>
          </el-table-column>
          <el-table-column label="最近播放" width="166">
            <template #default="{ row }">{{ formatTime(row.lastPlayedAt) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Search, Refresh } from "@element-plus/icons-vue";
import { apiDelete, apiGet, apiPostJson, formatTime } from "../api";

type UserSummary = {
  id: number;
  username: string;
  nickname: string;
  email: string | null;
  /** 服务端列表接口已下发，无头像时为 null。 */
  avatarUrl: string | null;
  songCount: number;
  playCount: number;
  completedCount: number;
  totalListenedMs: number;
  lastPlayedAt: number | null;
  disabledAt: number | null;
};

type PlaybackDetail = {
  user: { username: string; nickname: string; email: string | null; createdAt: string; disabledAt: number | null };
  stats: {
    songCount: number;
    playCount: number;
    completedCount: number;
    totalListenedMs: number;
    firstPlayedAt: number | null;
    lastPlayedAt: number | null;
  };
  history: {
    revision: number;
    entries: Array<{
      source: string;
      songId: string;
      playCount: number;
      completedCount: number;
      totalListenedMs: number;
      lastPlayedAt: number;
    }>;
  };
};

const props = defineProps<{ adminToken: string }>();
const pageSize = 30;
const query = ref("");
const page = ref(1);
const users = ref<UserSummary[]>([]);
const total = ref(0);
const loading = ref(false);
const detailVisible = ref(false);
const detail = ref<PlaybackDetail | null>(null);
const detailTitle = computed(() => (detail.value ? `${detail.value.user.nickname}的听歌统计` : "听歌统计"));

onMounted(fetchUsers);

async function fetchUsers() {
  loading.value = true;
  try {
    const params = new URLSearchParams({
      limit: String(pageSize),
      offset: String((page.value - 1) * pageSize),
    });
    if (query.value.trim()) params.set("query", query.value.trim());
    const data = await apiGet<{ items: UserSummary[]; total: number }>(`/app/admin/users?${params}`, props.adminToken);
    users.value = data.items;
    total.value = data.total;
  } catch (error) {
    ElMessage.error(`加载用户失败：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  void fetchUsers();
}

async function openDetail(user: UserSummary) {
  try {
    detail.value = await apiGet<PlaybackDetail>(`/app/admin/users/${user.id}/playback?limit=50`, props.adminToken);
    detailVisible.value = true;
  } catch (error) {
    ElMessage.error(`加载统计失败：${(error as Error).message}`);
  }
}

async function toggleDisabled(user: UserSummary) {
  const disabling = !user.disabledAt;
  try {
    await ElMessageBox.confirm(
      disabling
        ? `禁用“${user.nickname}”后，该账号会立即退出登录且无法继续访问。`
        : `恢复“${user.nickname}”后，该账号可重新登录。`,
      disabling ? "确认禁用账号" : "确认恢复账号",
      { type: disabling ? "warning" : "info", confirmButtonText: disabling ? "禁用" : "恢复" },
    );
    await apiPostJson(`/app/admin/users/${user.id}/disabled`, props.adminToken, { disabled: disabling });
    ElMessage.success(disabling ? "账号已禁用" : "账号已恢复");
    await fetchUsers();
    if (detail.value?.user.username === user.username) await openDetail(user);
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(`操作失败：${(error as Error).message}`);
  }
}

async function removeUser(user: UserSummary) {
  try {
    await ElMessageBox.confirm(
      `永久删除“${user.nickname}”及其收藏、播放记录和统计？此操作不可恢复。`,
      "确认永久删除",
      { type: "error", confirmButtonText: "永久删除", confirmButtonClass: "el-button--danger" },
    );
    await apiDelete(`/app/admin/users/${user.id}`, props.adminToken);
    ElMessage.success("用户已永久删除");
    detailVisible.value = false;
    detail.value = null;
    if (users.value.length === 1 && page.value > 1) page.value -= 1;
    await fetchUsers();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(`删除失败：${(error as Error).message}`);
  }
}

function formatDuration(milliseconds: number): string {
  const seconds = Math.floor(milliseconds / 1_000);
  const hours = Math.floor(seconds / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  if (hours > 0) return `${hours}小时${minutes}分`;
  return `${minutes}分${seconds % 60}秒`;
}
</script>

<style scoped>
.manager { padding: 4px 0; }

.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.bar-spacer { flex: 1; min-width: 8px; }
.search-input { width: min(320px, 100%); }

.pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
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

.pill-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary-color);
  box-shadow: 0 0 0 3px var(--primary-soft);
  flex-shrink: 0;
}

/* 用户单元格：头像 + 双行身份信息 */
.user-cell { display: flex; align-items: center; gap: 10px; }

.user-avatar {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 650;
  color: #fff;
  background: linear-gradient(140deg, var(--primary-color), #ff9d6e);
}

.user-meta { min-width: 0; }

.user-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hint, .muted { font-size: 13px; color: var(--el-text-color-secondary); }

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  flex-wrap: wrap;
}

.stats { margin: 22px 0 4px; }

/* 指标卡片：让四个统计数字成为「仪表盘」而不是四行裸文字 */
.stat-card {
  padding: 14px 16px;
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  height: 100%;
  box-sizing: border-box;
}

.detail-hint { margin: 14px 0 22px; line-height: 1.7; }

h3 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

@media (max-width: 720px) {
  .bar { align-items: stretch; }
  .search-input { width: 100%; }
  .stats :deep(.el-col) { margin-bottom: 10px; }
}
</style>
