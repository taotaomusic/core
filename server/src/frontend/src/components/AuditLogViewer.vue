<template>
  <div class="manager">
    <div class="bar">
      <el-select v-model="filterAction" clearable filterable placeholder="全部操作类型" style="width: 220px">
        <el-option-group v-for="group in ACTION_GROUPS" :key="group.label" :label="group.label">
          <el-option v-for="item in group.actions" :key="item[0]" :label="item[1]" :value="item[0]" />
        </el-option-group>
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
      <el-table-column prop="action" label="操作" width="170">
        <template #default="{ row }">
          <el-tag size="small" effect="light" :type="actionTag(row.action)">{{ actionLabel(row.action) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="目标" width="150">
        <template #default="{ row }">
          <span v-if="row.target_type">{{ targetTypeLabel(row.target_type) }}<template v-if="row.target_id"> #{{ row.target_id }}</template></span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="ip_address" label="IP" width="140" />
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="详情" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ formatDetail(row.detail) }}</template>
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

/**
 * 操作类型分组表，既是筛选下拉的数据源，也派生出整张动作名翻译表。
 * 元组格式为 [后端 action 原文, 中文展示名]；后端新增动作时在这里补一行即可。
 */
const ACTION_GROUPS: Array<{ label: string; actions: Array<[string, string]> }> = [
  {
    label: "登录与账号",
    actions: [
      ["auth.login", "登录"], ["auth.login_ldap", "LDAP 登录"], ["auth.login_totp", "TOTP 登录"],
      ["auth.change_password", "修改密码"], ["auth.totp_enable_requested", "请求开启两步验证"],
      ["auth.totp_enabled", "开启两步验证"], ["auth.totp_disabled", "关闭两步验证"],
    ],
  },
  {
    label: "管理员",
    actions: [
      ["admin.create", "新建管理员"], ["admin.update", "编辑管理员"],
      ["admin.delete", "删除管理员"], ["admin.ip_whitelist", "调整 IP 白名单"],
    ],
  },
  {
    label: "公告",
    actions: [
      ["announcement.create", "新建公告"], ["announcement.update", "编辑公告"],
      ["announcement.set_enabled", "启停公告"], ["announcement.set_pinned", "置顶公告"],
      ["announcement.delete", "删除公告"],
    ],
  },
  {
    label: "客户端版本",
    actions: [
      ["release.publish", "发布版本"], ["release.rollout", "调整放量"],
      ["release.min_version", "调整最低版本"], ["release.patch_publish", "发布热更补丁"],
      ["release.patch_rollout", "调整补丁放量"], ["release.config_set", "设置远程配置"],
      ["release.config_remove", "删除远程配置"],
    ],
  },
  {
    label: "桌面版本",
    actions: [
      ["desktop.artifact_upload", "上传桌面产物"], ["desktop.publish", "发布桌面版本"],
      ["desktop.rollout", "调整桌面放量"], ["desktop.min_version", "调整桌面最低版本"],
    ],
  },
  {
    label: "音源账号",
    actions: [
      ["music_source.create", "新建音源账号"], ["music_source.update", "编辑音源账号"],
      ["music_source.enable", "启用音源账号"], ["music_source.disable", "停用音源账号"],
      ["music_source.probe", "探测音源账号"], ["music_source.delete", "删除音源账号"],
      ["music_source.sms", "发送登录短信"], ["music_source.login", "音源账号登录"],
    ],
  },
  {
    label: "密钥",
    actions: [
      ["image_key.import", "导入绘图密钥"], ["image_key.delete", "删除绘图密钥"],
      ["open_api_key.create", "新建开放接口密钥"], ["open_api_key.set_enabled", "启停开放接口密钥"],
      ["open_api_key.revoke", "吊销开放接口密钥"],
    ],
  },
  {
    label: "用户",
    actions: [
      ["user.set_disabled", "禁用/恢复用户"], ["user.delete", "删除用户"],
    ],
  },
];

const ACTION_LABELS: Record<string, string> = Object.fromEntries(
  ACTION_GROUPS.flatMap((group) => group.actions),
);

/** 目标类型翻译，取值与各控制器 `audit.record(...)` 传入的 targetType 一致。 */
const TARGET_TYPE_LABELS: Record<string, string> = {
  admin_user: "管理员", announcement: "公告", desktop_artifact: "桌面产物",
  desktop_release: "桌面版本", image_key: "绘图密钥", music_source_account: "音源账号",
  open_api_key: "开放接口密钥", patch: "热更补丁", release: "客户端版本",
  remote_config: "远程配置", user: "用户",
};

/** 详情 JSON 里的键名翻译；没有收录的键原样展示。 */
const DETAIL_KEY_LABELS: Record<string, string> = {
  title: "标题", enabled: "启用", disabled: "禁用", pinned: "置顶", fields: "变更字段",
  channel: "渠道", architecture: "架构", fileCount: "文件数", size: "大小", totalSize: "总大小",
  versionCode: "版本号", versionName: "版本名", targetVersionCode: "目标版本号",
  patchVersion: "补丁版本号", minSdk: "最低 SDK", rolloutPercent: "放量", percent: "放量",
  apkSize: "APK 大小", apkSha256: "APK 校验值", patchSize: "补丁大小", patchSha256: "补丁校验值",
  minSupportedVersionCode: "最低支持版本", minVersionCode: "最低版本号", maxVersionCode: "最高版本号",
  rescueVersionCode: "救援版本号", key: "配置键", value: "配置值", maskedKey: "密钥掩码",
  keyPrefix: "密钥前缀", maskedPhone: "手机号", source: "音源", uid: "账号 ID", status: "状态",
};

/** 详情值里的枚举翻译，例如公告更新只记改了哪些字段。 */
const DETAIL_VALUE_LABELS: Record<string, string> = {
  title: "标题", content: "正文",
};

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
  if (/(delete|revoke|remove|disable)/.test(action)) return "danger";
  if (action.startsWith("auth.") || action.startsWith("admin.")) return "info";
  if (action.startsWith("release.") || action.startsWith("desktop.")) return "success";
  return "warning";
}

function actionLabel(action: string) { return ACTION_LABELS[action] ?? action; }

function targetTypeLabel(targetType: string) { return TARGET_TYPE_LABELS[targetType] ?? targetType; }

/**
 * 详情列：后端把补充信息 JSON 序列化后整串入库，这里还原成
 * 「中文键名：中文值」的一行文本；不是合法 JSON 时原样展示。
 */
function formatDetail(detail: string | null): string {
  if (!detail) return "—";
  let entries: Array<[string, unknown]>;
  try {
    const parsed: unknown = JSON.parse(detail);
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) return detail;
    entries = Object.entries(parsed);
  } catch {
    return detail;
  }
  return entries
    .map(([key, value]) => `${DETAIL_KEY_LABELS[key] ?? key}：${formatDetailValue(key, value)}`)
    .join("；");
}

function formatDetailValue(key: string, value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (Array.isArray(value)) {
    return value.map((item) => formatDetailValue(key, item)).join("、");
  }
  if (typeof value === "number") {
    if (key === "rolloutPercent" || key === "percent") return `${value}%`;
    if (key === "apkSize" || key === "patchSize" || key === "size" || key === "totalSize") return formatBytes(value);
  }
  return DETAIL_VALUE_LABELS[String(value)] ?? String(value);
}

function formatBytes(size: number): string {
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(2)} MB`;
  if (size >= 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${size} B`;
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
