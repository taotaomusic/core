<template>
  <div class="manager">
    <div class="bar">
      <el-button v-if="!readonly" type="primary" @click="showCreate = true">
        <el-icon><Plus /></el-icon>
        <span>创建 Key</span>
      </el-button>
      <el-button :loading="loading" @click="refresh">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span v-if="readonly" class="pill">只读账号：可查看 Key 列表，创建 / 停用 / 吊销需管理员权限</span>
      <span v-else class="pill">明文仅创建时显示一次 · 建议 X-API-Key 请求头</span>
    </div>

    <el-table :data="keys" v-loading="loading" empty-text="尚未创建开放 API Key">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="前缀" min-width="150">
        <template #default="{ row }">
          <span class="key-cell">{{ row.keyPrefix }}…</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.revokedAt" type="info" size="small" effect="plain">已吊销</el-tag>
          <el-tag v-else-if="row.enabled === 1" type="success" size="small" effect="light">启用</el-tag>
          <el-tag v-else type="warning" size="small" effect="light">停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">
          <span class="time-cell">{{ formatTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="最后使用" width="160">
        <template #default="{ row }">
          <span class="time-cell">{{ formatTime(row.lastUsedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="!readonly" label="操作" width="170">
        <template #default="{ row }">
          <template v-if="!row.revokedAt">
            <el-switch
              class="enable-switch"
              :model-value="row.enabled === 1"
              @change="toggle(row, $event as boolean)"
            />
            <el-popconfirm title="吊销后该 Key 立即失效且不可恢复，确认继续？" @confirm="revoke(row.id)">
              <template #reference><el-button link type="danger">吊销</el-button></template>
            </el-popconfirm>
          </template>
          <span v-else class="sub">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showCreate" title="创建开放 API Key" width="460px" @closed="resetDraft">
      <el-form label-position="top">
        <el-form-item label="名称（用途备注）">
          <el-input v-model="draftName" maxlength="64" placeholder="例如：站外搜索、小工具" autocomplete="off" />
        </el-form-item>
      </el-form>
      <p class="hint">服务端只保存 Key 的 sha256，明文仅在创建成功后返回一次。</p>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="draftName.trim().length < 1" @click="create">
          创建
        </el-button>
      </template>
    </el-dialog>

    <!-- 明文仅此一次：创建成功后单独弹出，关闭即不可再查看 -->
    <el-dialog v-model="showIssued" title="Key 已创建" width="560px" :close-on-click-modal="false" @closed="issuedPlaintext = ''">
      <el-alert type="warning" :closable="false" show-icon title="明文只显示这一次">
        <p>关闭本窗口后只能看到前缀，无法再次获取完整 Key。请立即复制并妥善保存。</p>
      </el-alert>
      <div class="issued-row">
        <el-input v-model="issuedPlaintext" readonly class="issued-input" />
        <el-button type="primary" @click="copyIssued">复制</el-button>
      </div>
      <p class="hint">请求时使用 <code>X-API-Key: {{ issuedPlaintext }}</code> 或 <code>Authorization: Bearer …</code>。</p>
      <template #footer>
        <el-button type="primary" @click="showIssued = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Plus, Refresh } from "@element-plus/icons-vue";
import { apiDelete, apiGet, apiPatch, apiPostJson, formatTime } from "../api";

/** 开放 API Key 的公开字段（列表与启停/吊销返回）。明文只在创建响应里出现一次。 */
type OpenApiKey = {
  id: number;
  name: string;
  keyPrefix: string;
  enabled: number;
  createdAt: number;
  lastUsedAt: number | null;
  revokedAt: number | null;
  createdBy: number | null;
};

type IssuedOpenApiKey = OpenApiKey & { apiKey: string };

/**
 * `role` 用于隐藏写操作：读接口是 READ_ROLES（含 viewer），观察者能看到列表，
 * 但创建 / 停用 / 吊销是 WRITE_ROLES，后端会拒 403。前端据此把写控件藏起来，
 * 避免观察者点了按钮只拿到一个报错。
 */
const props = defineProps<{ adminToken: string; role?: string }>();
const readonly = computed(() => props.role === "viewer");
const keys = ref<OpenApiKey[]>([]);
const loading = ref(false);
const saving = ref(false);
const showCreate = ref(false);
const draftName = ref("");
const showIssued = ref(false);
const issuedPlaintext = ref("");

async function refresh() {
  loading.value = true;
  try {
    keys.value = await apiGet<OpenApiKey[]>("/app/admin/open-api-keys", props.adminToken);
  } catch (error) {
    ElMessage.error(`读取 Key 失败：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function resetDraft() {
  draftName.value = "";
}

async function create() {
  saving.value = true;
  try {
    const issued = await apiPostJson<IssuedOpenApiKey>("/app/admin/open-api-keys", props.adminToken, {
      name: draftName.value.trim(),
    });
    showCreate.value = false;
    issuedPlaintext.value = issued.apiKey;
    showIssued.value = true;
    await refresh();
  } catch (error) {
    ElMessage.error(`创建失败：${(error as Error).message}`);
  } finally {
    saving.value = false;
  }
}

async function toggle(row: OpenApiKey, next: boolean) {
  try {
    const updated = await apiPatch<OpenApiKey>(`/app/admin/open-api-keys/${row.id}`, props.adminToken, {
      enabled: next,
    });
    row.enabled = updated.enabled;
    ElMessage.success(next ? "已启用" : "已停用");
  } catch (error) {
    ElMessage.error(`更新状态失败：${(error as Error).message}`);
    await refresh();
  }
}

async function revoke(id: number) {
  try {
    await apiDelete(`/app/admin/open-api-keys/${id}`, props.adminToken);
    ElMessage.success("已吊销");
    await refresh();
  } catch (error) {
    ElMessage.error(`吊销失败：${(error as Error).message}`);
  }
}

async function copyIssued() {
  try {
    await navigator.clipboard.writeText(issuedPlaintext.value);
    ElMessage.success("已复制到剪贴板");
  } catch {
    ElMessage.error("复制失败，请手动选中文本复制");
  }
}

onMounted(refresh);
</script>

<style scoped>
.manager { padding: 8px 0; }

.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.bar-spacer { flex: 1; min-width: 8px; }

.pill {
  display: inline-flex;
  align-items: center;
  padding: 6px 13px;
  font-size: 13px;
  line-height: 1;
  color: var(--el-text-color-secondary);
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  white-space: nowrap;
}

.hint { color: var(--el-text-color-secondary); font-size: 13px; }
.hint code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--el-text-color-primary);
}

.key-cell,
.time-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.time-cell { font-variant-numeric: tabular-nums; }

.enable-switch { margin-right: 10px; vertical-align: middle; }

.sub { color: var(--el-text-color-secondary); font-size: 13px; }

.issued-row {
  display: flex;
  gap: 10px;
  margin-top: 14px;
  align-items: center;
}

.issued-input :deep(input) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}
</style>
