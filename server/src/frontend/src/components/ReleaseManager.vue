<template>
  <div class="manager">
    <div class="bar">
      <el-button type="primary" @click="openUpload">
        <el-icon><Upload /></el-icon>
        <span>上传新版本</span>
      </el-button>
      <el-button @click="fetchReleases" :loading="loading">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill" v-if="rolledOut">
        <span class="pill-dot ok"></span>
        当前全量版本 <strong>{{ rolledOut.version_code }}</strong>（{{ rolledOut.version_name }}）
      </span>
      <span class="pill warn" v-else>
        <span class="pill-dot warn"></span>
        还没有放量 100% 的版本
      </span>
    </div>

    <el-table :data="releases" v-loading="loading" empty-text="还没有任何发布记录">
      <el-table-column prop="version_code" label="版本号" width="90" sortable />
      <el-table-column prop="version_name" label="版本名" width="110" />
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
      <el-table-column prop="release_note" label="更新说明" min-width="200" show-overflow-tooltip />
      <el-table-column label="体积" width="100">
        <template #default="{ row }">{{ formatSize(row.apk_size) }}</template>
      </el-table-column>
      <el-table-column label="发布时间" width="170">
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
            {{ row.enabled === 1 ? "停用" : "启用" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="uploadVisible" title="上传新版本" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        版本号必须取自构建产物的 <code>output-metadata.json</code>，不能读
        <code>version.properties</code>（递增发生在构建之后，那里的值已经比包大 1）。
        重号会静默覆盖已发布记录的 sha256。
      </el-alert>
      <el-form label-width="110px">
        <el-form-item label="APK 文件" required>
          <input type="file" accept=".apk" @change="onFilePicked" />
          <div class="hint" v-if="file">
            {{ file.name }} · {{ formatSize(file.size) }}
            <span v-if="sha256">· sha256 已算出</span>
          </div>
        </el-form-item>
        <el-form-item label="版本号" required>
          <el-input-number v-model="form.versionCode" :min="1" :controls="false" style="width: 160px" />
        </el-form-item>
        <el-form-item label="版本名" required>
          <el-input v-model="form.versionName" placeholder="1.0.70" style="width: 200px" />
        </el-form-item>
        <el-form-item label="更新说明">
          <el-input v-model="form.note" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="初始放量">
          <el-input-number v-model="form.rollout" :min="0" :max="100" />
          <span class="hint" style="margin-left: 10px">
            默认 0 —— 先登记不下发，留出自测窗口
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rolloutVisible" title="调整放量" width="440px">
      <el-form label-width="90px">
        <el-form-item label="版本">
          {{ current?.version_code }}（{{ current?.version_name }}）
        </el-form-item>
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
import { ref, computed, onMounted } from "vue";
import { Upload, Refresh } from "@element-plus/icons-vue";
import { formatSize, formatTime, sha256Of, apiGet, apiPostJson, apiPostBytes } from "../api";

/** 服务端原样返回数据库行，所以字段是下划线命名。 */
interface Release {
  id: number;
  channel: string;
  version_code: number;
  version_name: string;
  apk_size: number;
  release_note: string;
  rollout_percent: number;
  enabled: number;
  published_at: number;
}

const props = defineProps<{ adminToken: string }>();

const releases = ref<Release[]>([]);
const loading = ref(false);
const uploadVisible = ref(false);
const rolloutVisible = ref(false);
const uploading = ref(false);
const saving = ref(false);
const current = ref<Release | null>(null);
const percent = ref(0);
const file = ref<File | null>(null);
const sha256 = ref("");

const form = ref({ versionCode: 1, versionName: "", note: "", rollout: 0 });

const rolledOut = computed(() =>
  releases.value
    .filter((item) => item.rollout_percent === 100 && item.enabled === 1)
    .sort((a, b) => b.version_code - a.version_code)[0],
);

onMounted(fetchReleases);

async function fetchReleases() {
  loading.value = true;
  try {
    releases.value = await apiGet<Release[]>("/app/admin/releases", props.adminToken);
  } catch (error) {
    ElMessage.error(`加载失败：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function openUpload() {
  file.value = null;
  sha256.value = "";
  form.value = { versionCode: 1, versionName: "", note: "", rollout: 0 };
  uploadVisible.value = true;
}

async function onFilePicked(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0] ?? null;
  file.value = picked;
  sha256.value = picked ? await sha256Of(await picked.arrayBuffer()) : "";
}

async function submitUpload() {
  if (!file.value) return ElMessage.warning("请选择 APK 文件");
  if (!form.value.versionName.trim()) return ElMessage.warning("请填写版本名");

  uploading.value = true;
  try {
    const query = new URLSearchParams({
      versionCode: String(form.value.versionCode),
      versionName: form.value.versionName.trim(),
      note: form.value.note,
      rollout: String(form.value.rollout),
      sha256: sha256.value,
    });
    await apiPostBytes(
      `/app/admin/releases?${query}`,
      props.adminToken,
      await file.value.arrayBuffer(),
    );
    ElMessage.success("上传成功");
    uploadVisible.value = false;
    await fetchReleases();
  } catch (error) {
    ElMessage.error(`上传失败：${(error as Error).message}`);
  } finally {
    uploading.value = false;
  }
}

function openRollout(row: Release) {
  current.value = row;
  percent.value = row.rollout_percent;
  rolloutVisible.value = true;
}

async function submitRollout() {
  if (!current.value) return;
  saving.value = true;
  try {
    await apiPostJson("/app/admin/rollout", props.adminToken, {
      versionCode: current.value.version_code,
      percent: percent.value,
    });
    ElMessage.success("已调整");
    rolloutVisible.value = false;
    await fetchReleases();
  } catch (error) {
    ElMessage.error(`调整失败：${(error as Error).message}`);
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row: Release) {
  const enabling = row.enabled !== 1;
  try {
    await ElMessageBox.confirm(
      `确定要${enabling ? "启用" : "停用"}版本 ${row.version_code} 吗？`,
      "确认",
      { type: "warning" },
    );
  } catch {
    return;
  }
  try {
    await apiPostJson("/app/admin/rollout", props.adminToken, {
      versionCode: row.version_code,
      percent: row.rollout_percent,
      enabled: enabling,
    });
    ElMessage.success(enabling ? "已启用" : "已停用");
    await fetchReleases();
  } catch (error) {
    ElMessage.error(`操作失败：${(error as Error).message}`);
  }
}
</script>

<style scoped>
.manager {
  padding: 4px 0;
}

/*
  工具栏：左侧动作、右侧状态摘要。`.bar-spacer` 把后面的状态指示推到右端，
  避免状态文字紧贴在按钮后面、读起来像按钮的附属说明。
*/
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

/* 状态指示胶囊：比裸文字更像「仪表盘」的一部分 */
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

.pill-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.pill-dot.ok {
  background: var(--el-color-success);
  box-shadow: 0 0 0 3px var(--el-color-success-light-9);
}

.pill.warn {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-8);
}

.pill-dot.warn {
  background: var(--el-color-warning);
  box-shadow: 0 0 0 3px rgba(230, 162, 60, 0.18);
}

.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.hint.warn {
  color: var(--el-color-warning);
}

code {
  background: var(--code-bg);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12.5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
