<template>
  <div class="manager">
    <!-- 汇总统计条：四个口径与后端 summary 一致，等宽数字对齐 -->
    <div class="stat-row">
      <div class="stat-card">
        <span class="stat-head">短链总数</span>
        <span class="stat-value">{{ summary.total }}</span>
        <span class="stat-sub">有效 {{ summary.active }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-head">累计访问</span>
        <span class="stat-value">{{ summary.accessCount }}</span>
        <span class="stat-sub">分享页元数据读取次数</span>
      </div>
      <div class="stat-card">
        <span class="stat-head">进入 App 人数</span>
        <span class="stat-value">{{ summary.visitorCount }}</span>
        <span class="stat-sub">按浏览器去重</span>
      </div>
      <div class="stat-card">
        <span class="stat-head">试听播放次数</span>
        <span class="stat-value">{{ summary.playCount }}</span>
        <span class="stat-sub">点「播放」计一次</span>
      </div>
      <span class="bar-spacer"></span>
      <span class="pill">{{ expireHint }}</span>
      <el-button :loading="purging" type="warning" plain @click="purge">
        <el-icon><DeleteFilled /></el-icon>
        <span>清除超期短链</span>
      </el-button>
    </div>

    <div class="bar">
      <el-input
        v-model="keyword"
        class="search-input"
        placeholder="按短码 / 歌名 / 歌手 / 分享者搜索"
        clearable
        @keyup.enter="reload"
        @clear="reload"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </div>

    <el-table :data="rows" v-loading="loading" empty-text="还没有分享短链">
      <el-table-column label="短码" width="132">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="copyLink(row.token)" class="token-cell">
            {{ row.token }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="歌曲" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="title-cell">{{ row.title }}<small class="artist-cell"> · {{ row.artist }}</small></span>
        </template>
      </el-table-column>
      <el-table-column prop="source" label="音源" width="86">
        <template #default="{ row }">
          <el-tag size="small" effect="light">{{ sourceLabel(row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="userName" label="分享者" width="130" show-overflow-tooltip />
      <el-table-column label="访问 / 进App / 播放" width="150" align="center">
        <template #default="{ row }">
          <span class="metric-cell">
            {{ row.accessCount }} / {{ row.visitorCount }} / {{ row.playCount }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" effect="light" size="small">
            {{ row.enabled === 1 ? "有效" : "已隐藏" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近访问" width="165">
        <template #default="{ row }">{{ formatTime(row.lastVisitedAt) }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="165">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button size="small" :type="row.enabled === 1 ? 'warning' : 'success'" plain @click="toggle(row)">
            {{ row.enabled === 1 ? "隐藏" : "恢复" }}
          </el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="onPage"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, computed } from "vue";
import { Refresh, Search, DeleteFilled } from "@element-plus/icons-vue";
import {
  deleteShareLink, listShareLinks, purgeShareLinks, formatTime,
  type ShareLinkRow, type ShareLinkSummary,
} from "../api";

const props = defineProps<{ adminToken: string }>();

const rows = ref<ShareLinkRow[]>([]);
const total = ref(0);
const summary = ref<ShareLinkSummary>({ total: 0, active: 0, accessCount: 0, visitorCount: 0, playCount: 0 });
const expireDays = ref(0);
const keyword = ref("");
const loading = ref(false);
const purging = ref(false);
const page = ref(1);
const pageSize = 30;

/** 自动清除配置的提示语；0 表示关闭。 */
const expireHint = computed(() =>
  expireDays.value > 0
    ? `自动清除：${expireDays.value} 天未访问的短链`
    : "自动清除：未开启（SHARE_EXPIRE_DAYS=0）",
);

onMounted(reload);

async function reload() {
  loading.value = true;
  try {
    const data = await listShareLinks(props.adminToken, {
      keyword: keyword.value.trim() || undefined,
      limit: pageSize,
      offset: (page.value - 1) * pageSize,
    });
    rows.value = data.items;
    total.value = data.total;
    summary.value = data.summary;
    expireDays.value = data.expireDays;
  } catch (error) {
    ElMessage.error(`加载失败：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function onPage(next: number) {
  page.value = next;
  void reload();
}

async function toggle(item: ShareLinkRow) {
  try {
    const disable = item.enabled === 1;
    // 隐藏走「软删除」端点：分享页立即失效但统计保留，误操作可恢复。
    await fetch(`/api/v1/app/admin/share-links/${encodeURIComponent(item.token)}/disabled`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${props.adminToken}` },
      body: JSON.stringify({ enabled: disable ? false : true }),
    }).then(async (response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
    });
    ElMessage.success(disable ? "短链已隐藏（分享页立即失效）" : "短链已恢复");
    await reload();
  } catch (error) {
    ElMessage.error(`操作失败：${(error as Error).message}`);
  }
}

async function remove(item: ShareLinkRow) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${item.title}」的短链 ${item.token} 吗？删除后分享页立即 404，且无法恢复。`,
      "确认删除",
      { type: "warning" },
    );
    await deleteShareLink(props.adminToken, item.token);
    ElMessage.success("短链已删除");
    await reload();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(`删除失败：${(error as Error).message}`);
  }
}

async function purge() {
  try {
    if (expireDays.value <= 0) {
      await ElMessageBox.confirm(
        "自动清除未开启（SHARE_EXPIRE_DAYS=0），手动清除不会删除任何短链。仍要试一次吗？",
        "自动清除未开启",
        { type: "info" },
      );
    }
    purging.value = true;
    const result = await purgeShareLinks(props.adminToken);
    ElMessage.success(
      result.removed.length > 0 ? `已清除 ${result.removed.length} 条超期短链` : "没有需要清除的超期短链",
    );
    await reload();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(`清除失败：${(error as Error).message}`);
  } finally {
    purging.value = false;
  }
}

async function copyLink(token: string) {
  try {
    await navigator.clipboard.writeText(`${window.location.origin}/s/${token}`);
    ElMessage.success("短链已复制");
  } catch {
    ElMessage.info(`短链：/s/${token}`);
  }
}

function sourceLabel(source: string): string {
  switch (source) {
    case "tencent": return "QQ";
    case "netease": return "网易";
    case "kuwo": return "酷我";
    default: return source;
  }
}
</script>

<style scoped>
.manager { padding: 4px 0; }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.search-input { max-width: 300px; }
.bar-spacer { flex: 1; min-width: 8px; }

.stat-row { display: flex; align-items: stretch; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.stat-card {
  display: flex; flex-direction: column; gap: 2px;
  padding: 10px 16px;
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  min-width: 128px;
}
.stat-head { font-size: 12px; color: var(--el-text-color-secondary); }
.stat-value { font-size: 22px; font-weight: 650; color: var(--el-text-color-primary); font-variant-numeric: tabular-nums; }
.stat-sub { font-size: 11px; color: var(--el-text-color-secondary); }

.pill {
  display: inline-flex; align-items: center; align-self: center;
  padding: 6px 13px; font-size: 13px; line-height: 1;
  color: var(--el-text-color-secondary);
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  white-space: nowrap;
}

.title-cell { font-weight: 550; color: var(--el-text-color-primary); }
.artist-cell { color: var(--el-text-color-secondary); font-weight: 400; }
.token-cell { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.metric-cell { font-variant-numeric: tabular-nums; color: var(--el-text-color-regular); }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
