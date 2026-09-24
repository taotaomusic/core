<template>
  <div class="manager">
    <div class="bar">
      <el-button type="primary" @click="showImport = true">
        <el-icon><Key /></el-icon>
        <span>导入 GPT Image Key</span>
      </el-button>
      <el-button :loading="loading" @click="refresh">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill">Key 仅显示末四位 · 额度为可创建图片任务次数</span>
    </div>

    <el-table :data="keys" v-loading="loading" empty-text="尚未导入 GPT Image Key">
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="maskedKey" label="Key" min-width="220">
        <template #default="{ row }">
          <span class="key-cell">{{ row.maskedKey }}</span>
        </template>
      </el-table-column>
      <el-table-column label="剩余额度" width="130" align="right">
        <template #default="{ row }">
          <span class="quota-cell">{{ row.quota }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-popconfirm title="移除后不可恢复，确认继续？" @confirm="remove(row.id)">
            <template #reference><el-button link type="danger">移除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showImport" title="导入 GPT Image Key" width="440px" @closed="resetDraft">
      <el-form label-position="top">
        <el-form-item label="API Key"><el-input v-model="draftKey" type="password" show-password autocomplete="off" /></el-form-item>
        <el-form-item label="可用额度"><el-input-number v-model="draftQuota" :min="0" :max="1000000" :step="1" /></el-form-item>
      </el-form>
      <p class="hint">Key 只发送到本服务端保存，客户端不会获取明文。</p>
      <template #footer><el-button @click="showImport = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="draftKey.trim().length < 8" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Key, Refresh } from "@element-plus/icons-vue";
import { apiDelete, apiGet, apiPostJson } from "../api";

type ImageKey = { id: number; channel: string; maskedKey: string; quota: number };
const props = defineProps<{ adminToken: string }>();
const keys = ref<ImageKey[]>([]);
const loading = ref(false);
const saving = ref(false);
const showImport = ref(false);
const draftKey = ref("");
const draftQuota = ref(1);

async function refresh() {
  loading.value = true;
  try { keys.value = await apiGet<ImageKey[]>("/app/admin/image-keys", props.adminToken); }
  catch (error) { ElMessage.error(`读取 Key 失败：${(error as Error).message}`); }
  finally { loading.value = false; }
}
function resetDraft() { draftKey.value = ""; draftQuota.value = 1; }
async function save() {
  saving.value = true;
  try {
    await apiPostJson("/app/admin/image-keys", props.adminToken, { key: draftKey.value.trim(), quota: draftQuota.value });
    ElMessage.success("已安全导入 Key"); showImport.value = false; await refresh();
  } catch (error) { ElMessage.error(`导入失败：${(error as Error).message}`); }
  finally { saving.value = false; }
}
async function remove(id: number) {
  try { await apiDelete(`/app/admin/image-keys/${id}`, props.adminToken); ElMessage.success("已移除"); await refresh(); }
  catch (error) { ElMessage.error(`移除失败：${(error as Error).message}`); }
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

/* 原本硬编码 #7a7370，在暗色模式下几乎看不清 —— 统一走主题变量 */
.hint { color: var(--el-text-color-secondary); font-size: 13px; }

.key-cell,
.quota-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.quota-cell { font-variant-numeric: tabular-nums; font-weight: 600; }
</style>
