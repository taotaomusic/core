<template>
  <div class="manager">
    <el-alert type="info" :closable="false" show-icon class="notice">
      选择 <code>desktop-update</code> 构建目录。浏览器会读取 <code>manifest.json</code>，按 sha256
      上传 <code>current/</code> 内的模块，再登记发布；服务端会为相邻版本预计算 bsdiff，原生
      DLL/EXE 配置 Courgette 后会优先使用 Courgette。
    </el-alert>
    <div class="bar">
      <el-button type="primary" @click="openUpload">
        <el-icon><Monitor /></el-icon>
        <span>上传 Windows 版本</span>
      </el-button>
      <el-button :loading="loading" @click="fetchReleases">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span v-if="rolledOut" class="pill">
        <span class="pill-dot ok"></span>
        当前全量版本 <strong>{{ rolledOut.version_code }}</strong>（{{ rolledOut.version_name }}）
      </span>
      <span v-else class="pill warn">
        <span class="pill-dot warn"></span>
        还没有放量 100% 的 Windows 版本
      </span>
    </div>

    <el-table :data="releases" v-loading="loading" empty-text="还没有 Windows 发布记录">
      <el-table-column prop="version_code" label="版本号" width="90" sortable />
      <el-table-column prop="version_name" label="版本名" width="120" />
      <el-table-column prop="architecture" label="架构" width="120" />
      <el-table-column label="放量" width="168">
        <template #default="{ row }">
          <el-progress :percentage="row.rollout_percent" :status="row.rollout_percent === 100 ? 'success' : undefined" :stroke-width="10" />
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'danger'" size="small" effect="light">{{ row.enabled === 1 ? "启用" : "已停用" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="entrypoint" label="入口模块" min-width="170" show-overflow-tooltip />
      <el-table-column prop="release_note" label="更新说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="发布时间" width="170">
        <template #default="{ row }">{{ formatTime(row.published_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openRollout(row)">调放量</el-button>
          <el-button size="small" :type="row.enabled === 1 ? 'danger' : 'success'" plain @click="toggleEnabled(row)">
            {{ row.enabled === 1 ? "停用" : "启用" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="uploadVisible" title="上传 Windows 模块化版本" width="600px">
      <el-form label-width="110px">
        <el-form-item label="构建目录" required>
          <input ref="folderInput" type="file" webkitdirectory multiple @change="onFolderPicked" />
          <div v-if="manifest" class="hint">
            {{ manifest.versionCode }} · {{ manifest.versionName }} · {{ manifest.files.length }} 个模块 · {{ formatSize(totalSize) }}
          </div>
        </el-form-item>
        <el-form-item label="渠道"><el-input v-model="channel" placeholder="release" /></el-form-item>
        <el-form-item label="更新说明"><el-input v-model="releaseNote" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="初始放量">
          <el-input-number v-model="rollout" :min="0" :max="100" />
          <span class="hint inline">默认 0，上传后先自测</span>
        </el-form-item>
        <el-progress v-if="uploading" :percentage="uploadPercent" />
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传并登记</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rolloutVisible" title="调整 Windows 放量" width="440px">
      <el-form label-width="90px">
        <el-form-item label="版本">{{ current?.version_code }}（{{ current?.version_name }}）</el-form-item>
        <el-form-item label="调整到"><el-slider v-model="percent" :min="0" :max="100" :marks="{ 0: '0', 10: '10', 50: '50', 100: '全量' }" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rolloutVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRollout">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Monitor, Refresh } from "@element-plus/icons-vue";
import { apiGet, apiPostBytes, apiPostJson, formatSize, formatTime, sha256Of } from "../api";

interface DesktopRelease {
  id: number;
  architecture: string;
  version_code: number;
  version_name: string;
  entrypoint: string;
  release_note: string;
  rollout_percent: number;
  enabled: number;
  published_at: number;
}

interface ManifestFile { path: string; category: string; size: number; sha256: string }
interface Manifest {
  channel?: string;
  versionCode: number;
  versionName: string;
  architecture: string;
  entrypoint: string;
  releaseNote?: string;
  rollout?: number;
  files: ManifestFile[];
}

const props = defineProps<{ adminToken: string }>();
const releases = ref<DesktopRelease[]>([]);
const loading = ref(false);
const uploadVisible = ref(false);
const rolloutVisible = ref(false);
const uploading = ref(false);
const saving = ref(false);
const manifest = ref<Manifest | null>(null);
const selectedFiles = ref(new Map<string, File>());
const releaseNote = ref("");
const channel = ref("release");
const rollout = ref(0);
const uploadPercent = ref(0);
const current = ref<DesktopRelease | null>(null);
const percent = ref(0);

const totalSize = computed(() => manifest.value?.files.reduce((sum, file) => sum + file.size, 0) ?? 0);
const rolledOut = computed(() => releases.value.filter((item) => item.enabled === 1 && item.rollout_percent === 100).sort((a, b) => b.version_code - a.version_code)[0]);

onMounted(fetchReleases);

async function fetchReleases() {
  loading.value = true;
  try { releases.value = await apiGet<DesktopRelease[]>("/desktop/admin/releases", props.adminToken); }
  catch (error) { ElMessage.error(`加载失败：${(error as Error).message}`); }
  finally { loading.value = false; }
}

function openUpload() {
  manifest.value = null;
  selectedFiles.value = new Map();
  releaseNote.value = "";
  channel.value = "release";
  rollout.value = 0;
  uploadPercent.value = 0;
  uploadVisible.value = true;
}

async function onFolderPicked(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? []);
  const manifestFile = files.find((file) => relativePath(file).endsWith("/manifest.json") || relativePath(file) === "manifest.json");
  if (!manifestFile) return ElMessage.warning("所选目录中没有 manifest.json");
  try {
    const parsed = JSON.parse(await manifestFile.text()) as Manifest;
    if (!Array.isArray(parsed.files) || parsed.files.length === 0) throw new Error("清单没有模块");
    const byName = new Map<string, File>();
    for (const file of files) {
      const path = relativePath(file).replace(/^.*?current\//, "");
      if (parsed.files.some((item) => item.path === path)) byName.set(path, file);
    }
    const missing = parsed.files.filter((item) => !byName.has(item.path));
    if (missing.length) throw new Error(`缺少模块：${missing.slice(0, 3).map((item) => item.path).join("、")}`);
    manifest.value = parsed;
    selectedFiles.value = byName;
    releaseNote.value = parsed.releaseNote ?? "";
    channel.value = parsed.channel ?? "release";
    rollout.value = parsed.rollout ?? 0;
  } catch (error) {
    manifest.value = null;
    ElMessage.error(`清单读取失败：${(error as Error).message}`);
  }
}

async function submitUpload() {
  if (!manifest.value) return ElMessage.warning("请先选择有效的构建目录");
  uploading.value = true;
  try {
    for (let index = 0; index < manifest.value.files.length; index++) {
      const item = manifest.value.files[index];
      const file = selectedFiles.value.get(item.path)!;
      if (file.size !== item.size) throw new Error(`${item.path} 大小与清单不一致`);
      const bytes = await file.arrayBuffer();
      if (await sha256Of(bytes) !== item.sha256) throw new Error(`${item.path} sha256 与清单不一致`);
      await apiPostBytes(`/desktop/admin/artifacts?sha256=${item.sha256}`, props.adminToken, bytes);
      uploadPercent.value = Math.round(((index + 1) / (manifest.value.files.length + 1)) * 100);
    }
    await apiPostJson("/desktop/admin/releases", props.adminToken, {
      ...manifest.value,
      channel: channel.value,
      releaseNote: releaseNote.value,
      rollout: rollout.value,
    });
    uploadPercent.value = 100;
    ElMessage.success("Windows 版本已登记，差分已预计算");
    uploadVisible.value = false;
    await fetchReleases();
  } catch (error) { ElMessage.error(`上传失败：${(error as Error).message}`); }
  finally { uploading.value = false; }
}

function openRollout(row: DesktopRelease) { current.value = row; percent.value = row.rollout_percent; rolloutVisible.value = true; }

async function submitRollout() {
  if (!current.value) return;
  saving.value = true;
  try {
    await apiPostJson("/desktop/admin/rollout", props.adminToken, { versionCode: current.value.version_code, architecture: current.value.architecture, percent: percent.value });
    rolloutVisible.value = false;
    await fetchReleases();
  } catch (error) { ElMessage.error(`调整失败：${(error as Error).message}`); }
  finally { saving.value = false; }
}

async function toggleEnabled(row: DesktopRelease) {
  const enabled = row.enabled !== 1;
  try { await ElMessageBox.confirm(`确定要${enabled ? "启用" : "停用"} Windows 版本 ${row.version_code} 吗？`, "确认", { type: "warning" }); }
  catch { return; }
  try {
    await apiPostJson("/desktop/admin/rollout", props.adminToken, { versionCode: row.version_code, architecture: row.architecture, percent: row.rollout_percent, enabled });
    await fetchReleases();
  } catch (error) { ElMessage.error(`操作失败：${(error as Error).message}`); }
}

function relativePath(file: File): string { return (file.webkitRelativePath || file.name).replace(/\\/g, "/"); }
</script>

<style scoped>
.manager { padding: 4px 0; }
.notice { margin-bottom: 18px; }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.bar-spacer { flex: 1; min-width: 8px; }

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

.pill strong { color: var(--el-text-color-primary); font-weight: 650; }

.pill-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.pill-dot.ok { background: var(--el-color-success); box-shadow: 0 0 0 3px var(--el-color-success-light-9); }
.pill.warn { color: var(--el-color-warning); background: var(--el-color-warning-light-9); border-color: var(--el-color-warning-light-8); }
.pill-dot.warn { background: var(--el-color-warning); box-shadow: 0 0 0 3px rgba(230, 162, 60, 0.18); }

.hint { font-size: 13px; color: var(--el-text-color-secondary); }
.hint.inline { margin-left: 10px; }

code {
  background: var(--code-bg);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12.5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
