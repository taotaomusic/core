<template>
  <div class="manager">
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 16px">
      <template #title>补丁只能改 <code>data</code> / <code>player</code> / <code>update</code> 包里的方法</template>
      Compose 界面没有插桩（在可组合函数开头插提前 return 会破坏 group 配对），UI bug 只能发整包。
      另外<strong>有整包更新时服务端不下发补丁</strong>，补丁只给「来不及发版或用户还没升级」兜底。
    </el-alert>

    <div class="bar">
      <el-button type="primary" @click="openUpload">
        <el-icon><Upload /></el-icon>
        <span>上传补丁</span>
      </el-button>
      <el-button @click="fetchPatches" :loading="loading">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill" v-if="patches.length">
        共 <strong>{{ patches.length }}</strong> 个补丁
      </span>
    </div>

    <el-table :data="patches" v-loading="loading" empty-text="还没有任何补丁">
      <el-table-column prop="target_version_code" label="宿主版本" width="100" sortable />
      <el-table-column prop="patch_version" label="补丁版本" width="100" sortable />
      <el-table-column label="放量" width="168">
        <template #default="{ row }">
          <el-progress
            :percentage="row.rollout_percent"
            :status="row.rollout_percent === 100 ? 'success' : undefined"
            :stroke-width="10"
          />
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'danger'" size="small" effect="light">
            {{ row.enabled === 1 ? "启用" : "已停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="note" label="修复说明" min-width="200" show-overflow-tooltip />
      <el-table-column label="体积" width="100">
        <template #default="{ row }">{{ formatSize(row.patch_size) }}</template>
      </el-table-column>
      <el-table-column label="登记时间" width="170">
        <template #default="{ row }">{{ formatTime(row.published_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openRollout(row)">调放量</el-button>
          <el-button
            size="small"
            :type="row.enabled === 1 ? 'danger' : 'success'"
            plain
            @click="toggleEnabled(row)"
          >
            {{ row.enabled === 1 ? "下架" : "启用" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="uploadVisible" title="上传补丁" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        宿主版本必须是<strong>补丁所基于的那个已发布版本</strong>，服务端会校验它真的发布过。
        客户端要求宿主 versionCode 与这个值<strong>严格相等</strong>才会应用 —— 填错了补丁装不上。
      </el-alert>
      <el-form label-width="110px">
        <el-form-item label="补丁文件" required>
          <input type="file" accept=".apk" @change="onFilePicked" />
          <div class="hint" v-if="file">
            {{ file.name }} · {{ formatSize(file.size) }}
            <span v-if="sha256">· sha256 已算出</span>
          </div>
        </el-form-item>
        <el-form-item label="宿主版本" required>
          <el-select v-model="form.targetVersionCode" placeholder="选择已发布的版本" style="width: 240px">
            <el-option
              v-for="item in releases"
              :key="item.version_code"
              :label="`${item.version_code}（${item.version_name}）`"
              :value="item.version_code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="补丁版本" required>
          <el-input-number v-model="form.patchVersion" :min="1" :controls="false" style="width: 160px" />
          <span class="hint" style="margin-left: 10px">同一宿主版本内递增</span>
        </el-form-item>
        <el-form-item label="修复说明">
          <el-input v-model="form.note" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="初始放量">
          <el-input-number v-model="form.rollout" :min="0" :max="100" />
          <span class="hint" style="margin-left: 10px">默认 0 —— 先登记不下发</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rolloutVisible" title="调整补丁放量" width="440px">
      <el-form label-width="90px">
        <el-form-item label="宿主版本">{{ current?.target_version_code }}</el-form-item>
        <el-form-item label="补丁版本">{{ current?.patch_version }}</el-form-item>
        <el-form-item label="当前">{{ current?.rollout_percent }}%</el-form-item>
        <el-form-item label="调整到">
          <el-slider v-model="percent" :min="0" :max="100" :marks="{ 0: '0', 10: '10', 50: '50', 100: '全量' }" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rolloutVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRollout">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { Upload, Refresh } from "@element-plus/icons-vue";
import { formatSize, formatTime, sha256Of, apiGet, apiPostJson, apiPostBytes } from "../api";

interface Patch {
  id: number;
  channel: string;
  target_version_code: number;
  patch_version: number;
  patch_size: number;
  note: string;
  rollout_percent: number;
  enabled: number;
  published_at: number;
}

interface ReleaseOption {
  version_code: number;
  version_name: string;
}

const props = defineProps<{ adminToken: string }>();

const patches = ref<Patch[]>([]);
const releases = ref<ReleaseOption[]>([]);
const loading = ref(false);
const uploadVisible = ref(false);
const rolloutVisible = ref(false);
const uploading = ref(false);
const saving = ref(false);
const current = ref<Patch | null>(null);
const percent = ref(0);
const file = ref<File | null>(null);
const sha256 = ref("");

const form = ref({ targetVersionCode: undefined as number | undefined, patchVersion: 1, note: "", rollout: 0 });

onMounted(() => {
  fetchPatches();
  fetchReleases();
});

async function fetchPatches() {
  loading.value = true;
  try {
    patches.value = await apiGet<Patch[]>("/app/admin/patches", props.adminToken);
  } catch (error) {
    ElMessage.error(`加载失败：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

async function fetchReleases() {
  try {
    releases.value = await apiGet<ReleaseOption[]>("/app/admin/releases", props.adminToken);
  } catch {
    // 宿主版本下拉是便利功能，拿不到不影响主流程。
  }
}

function openUpload() {
  file.value = null;
  sha256.value = "";
  form.value = { targetVersionCode: undefined, patchVersion: 1, note: "", rollout: 0 };
  uploadVisible.value = true;
}

async function onFilePicked(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0] ?? null;
  file.value = picked;
  sha256.value = picked ? await sha256Of(await picked.arrayBuffer()) : "";
}

async function submitUpload() {
  if (!file.value) return ElMessage.warning("请选择补丁文件");
  if (!form.value.targetVersionCode) return ElMessage.warning("请选择宿主版本");

  uploading.value = true;
  try {
    const query = new URLSearchParams({
      targetVersionCode: String(form.value.targetVersionCode),
      patchVersion: String(form.value.patchVersion),
      note: form.value.note,
      rollout: String(form.value.rollout),
      sha256: sha256.value,
    });
    await apiPostBytes(
      `/app/admin/patches?${query}`,
      props.adminToken,
      await file.value.arrayBuffer(),
    );
    ElMessage.success("上传成功");
    uploadVisible.value = false;
    await fetchPatches();
  } catch (error) {
    ElMessage.error(`上传失败：${(error as Error).message}`);
  } finally {
    uploading.value = false;
  }
}

function openRollout(row: Patch) {
  current.value = row;
  percent.value = row.rollout_percent;
  rolloutVisible.value = true;
}

async function submitRollout() {
  if (!current.value) return;
  saving.value = true;
  try {
    await apiPostJson("/app/admin/patch-rollout", props.adminToken, {
      targetVersionCode: current.value.target_version_code,
      patchVersion: current.value.patch_version,
      percent: percent.value,
    });
    ElMessage.success("已调整");
    rolloutVisible.value = false;
    await fetchPatches();
  } catch (error) {
    ElMessage.error(`调整失败：${(error as Error).message}`);
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row: Patch) {
  const enabling = row.enabled !== 1;
  try {
    await ElMessageBox.confirm(
      enabling
        ? `确定要启用补丁 ${row.target_version_code}/${row.patch_version} 吗？`
        : `确定要下架补丁 ${row.target_version_code}/${row.patch_version} 吗？已装上的客户端会在下次启动时失效。`,
      "确认",
      { type: "warning" },
    );
  } catch {
    return;
  }
  try {
    await apiPostJson("/app/admin/patch-rollout", props.adminToken, {
      targetVersionCode: row.target_version_code,
      patchVersion: row.patch_version,
      percent: row.rollout_percent,
      enabled: enabling,
    });
    ElMessage.success(enabling ? "已启用" : "已下架");
    await fetchPatches();
  } catch (error) {
    ElMessage.error(`操作失败：${(error as Error).message}`);
  }
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

.bar-spacer {
  flex: 1;
  min-width: 8px;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 13px;
  font-size: 13px;
  line-height: 1;
  color: var(--el-text-color-regular);
  background: var(--hint-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  white-space: nowrap;
}

.pill strong {
  color: var(--el-text-color-primary);
  font-weight: 650;
}

.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

code {
  background: var(--code-bg);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12.5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
