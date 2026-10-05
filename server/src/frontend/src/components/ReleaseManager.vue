<template>
  <div class="manager">
    <div class="bar">
      <el-button type="primary" @click="openUpload">
        <el-icon><Upload /></el-icon>
        <span>创建新版本</span>
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
      <el-table-column prop="version_code" label="版本号" width="90" />
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
      <el-table-column label="体积" width="100">
        <template #default="{ row }">{{ formatSize(row.apk_size) }}</template>
      </el-table-column>
      <el-table-column label="sha256" width="150">
        <template #default="{ row }">
          <!-- 完整哈希 64 位放不进表格：展示首尾各 6 位，悬停看全值，点击复制 -->
          <el-tooltip :content="row.apk_sha256 || '（空）'" placement="top">
            <span class="hash-cell" @click="copyText(row.apk_sha256)">
              {{ shortHash(row.apk_sha256) }}
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="release_note" label="更新说明" min-width="200" show-overflow-tooltip />
      <el-table-column label="发布时间" width="170">
        <template #default="{ row }">{{ formatTime(row.published_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="252" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
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

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="pageSize"
      class="pager"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="fetchReleases"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="uploadVisible" title="创建新版本" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
        版本号必须取自构建产物的 <code>output-metadata.json</code>，不能读
        <code>version.properties</code>（递增发生在构建之后，那里的值已经比包大 1）。
        重号会静默覆盖已发布记录的 sha256。
      </el-alert>
      <el-form label-width="110px">
        <el-form-item label="登记方式">
          <el-radio-group v-model="createMode">
            <el-radio-button value="upload">上传安装包</el-radio-button>
            <el-radio-button value="link">填写下载地址</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <!-- 上传模式：包字节直传本机，走 /app/apk 端点下发 -->
        <el-form-item v-if="createMode === 'upload'" label="APK 文件" required>
          <input type="file" accept=".apk" @change="onFilePicked" />
          <div class="hint" v-if="file">
            {{ file.name }} · {{ formatSize(file.size) }}
            <span v-if="sha256">· sha256 已算出</span>
          </div>
        </el-form-item>
        <!-- 外链模式：包留在外部（GitHub Release / 对象存储），本机只登记地址 -->
        <template v-if="createMode === 'link'">
          <el-form-item label="下载地址" required>
            <el-input v-model="linkForm.apkUrl" placeholder="https://github.com/.../TaotaoMusic-1.0.70.apk" />
          </el-form-item>
          <el-form-item label="sha256">
            <el-input v-model="linkForm.sha256" placeholder="64 位十六进制，选填；断更比对的依据" />
          </el-form-item>
          <el-form-item label="大小 (MB)">
            <el-input-number v-model="linkForm.sizeMb" :min="0" :controls="false" style="width: 140px" />
            <span class="hint" style="margin-left: 10px">选填，仅用于后台展示</span>
          </el-form-item>
        </template>
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
        <el-button type="primary" :loading="uploading" @click="submitCreate">
          {{ createMode === "upload" ? "上传" : "登记" }}
        </el-button>
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

    <el-dialog v-model="editVisible" title="编辑发布记录" width="560px">
      <el-form label-width="110px">
        <el-form-item label="版本">
          {{ editing?.version_code }}（{{ editing?.version_name }}）
        </el-form-item>
        <el-form-item label="更新说明">
          <el-input v-model="editForm.releaseNote" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="安装包地址">
          <el-input
            v-model="editForm.apkUrl"
            placeholder="https://github.com/.../releases/download/....apk"
          />
          <div class="hint" style="margin-top: 4px">
            下发更新和分享页「下载完整版」都用这个外链；清空则回落本机 /app/apk 端点
          </div>
        </el-form-item>
        <el-form-item label="sha256">
          <span class="hash">{{ editing?.apk_sha256 || "（空）" }}</span>
          <el-button size="small" text type="primary" @click="copyText(editing?.apk_sha256 ?? '')">
            复制
          </el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingEdit" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { Upload, Refresh } from "@element-plus/icons-vue";
import { formatSize, formatTime, sha256Of, apiGet, apiPostJson, apiPostBytes } from "../api";

/** 服务端原样返回数据库行，所以字段是下划线命名。 */
interface Release {
  id: number;
  channel: string;
  version_code: number;
  version_name: string;
  apk_size: number;
  apk_url: string;
  apk_sha256: string;
  release_note: string;
  rollout_percent: number;
  enabled: number;
  published_at: number;
}

/** 带 page/pageSize 请求时的分页信封；rolledOut 是服务端代查的当前全量版本。 */
interface PagedReleases {
  items: Release[];
  total: number;
  rolledOut: Release | null;
}

const props = defineProps<{ adminToken: string }>();

const releases = ref<Release[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = ref(20);
const rolledOut = ref<Release | null>(null);
const loading = ref(false);
const uploadVisible = ref(false);
// 登记方式：upload = 包字节直传本机；link = 只登记外部下载地址（GitHub Release / 对象存储）。
const createMode = ref<"upload" | "link">("upload");
const linkForm = ref({ apkUrl: "", sha256: "", sizeMb: 0 });
const rolloutVisible = ref(false);
const uploading = ref(false);
const saving = ref(false);
const current = ref<Release | null>(null);
const percent = ref(0);
const file = ref<File | null>(null);
const sha256 = ref("");
const editVisible = ref(false);
const editing = ref<Release | null>(null);
const savingEdit = ref(false);
const editForm = ref({ releaseNote: "", apkUrl: "" });

const form = ref({ versionCode: 1, versionName: "", note: "", rollout: 0 });

onMounted(fetchReleases);

let fetchSeq = 0;
async function fetchReleases() {
  const seq = ++fetchSeq;
  loading.value = true;
  try {
    const data = await apiGet<PagedReleases>(
      `/app/admin/releases?page=${page.value}&pageSize=${pageSize.value}`,
      props.adminToken,
    );
    // 翻页快翻或改每页条数会并发两个请求，过期响应直接丢弃，避免旧页盖掉新页。
    if (seq !== fetchSeq) return;
    releases.value = data.items;
    total.value = data.total;
    rolledOut.value = data.rolledOut;
  } catch (error) {
    if (seq !== fetchSeq) return;
    ElMessage.error(`加载失败：${(error as Error).message}`);
  } finally {
    if (seq === fetchSeq) loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  fetchReleases();
}

function openUpload() {
  file.value = null;
  sha256.value = "";
  form.value = { versionCode: 1, versionName: "", note: "", rollout: 0 };
  linkForm.value = { apkUrl: "", sha256: "", sizeMb: 0 };
  createMode.value = "upload";
  uploadVisible.value = true;
}

async function onFilePicked(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0] ?? null;
  file.value = picked;
  sha256.value = picked ? await sha256Of(await picked.arrayBuffer()) : "";
}

async function submitCreate() {
  if (createMode.value === "upload") return submitUpload();
  return submitLink();
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
    // 新登记的版本号是当前最大的，必然落在第一页，翻回去让上传者立刻看到它。
    page.value = 1;
    await fetchReleases();
  } catch (error) {
    ElMessage.error(`上传失败：${(error as Error).message}`);
  } finally {
    uploading.value = false;
  }
}

/** 外链登记：包留在外部，本机只存地址（与 webhook 登记的记录同形状）。 */
async function submitLink() {
  if (!form.value.versionName.trim()) return ElMessage.warning("请填写版本名");
  const url = linkForm.value.apkUrl.trim();
  if (!/^https?:\/\//i.test(url)) return ElMessage.warning("下载地址必须是 http(s) 链接");
  const sha = linkForm.value.sha256.trim();
  if (sha && !/^[0-9a-fA-F]{64}$/.test(sha)) return ElMessage.warning("sha256 必须是 64 位十六进制");

  uploading.value = true;
  try {
    await apiPostJson("/app/admin/releases/link", props.adminToken, {
      versionCode: form.value.versionCode,
      versionName: form.value.versionName.trim(),
      apkUrl: url,
      sha256: sha || undefined,
      // MB → 字节；0/空按未填处理。
      apkSize: linkForm.value.sizeMb > 0 ? Math.round(linkForm.value.sizeMb * 1024 * 1024) : undefined,
      releaseNote: form.value.note,
      rollout: form.value.rollout,
    });
    ElMessage.success("登记成功");
    uploadVisible.value = false;
    page.value = 1;
    await fetchReleases();
  } catch (error) {
    ElMessage.error(`登记失败：${(error as Error).message}`);
  } finally {
    uploading.value = false;
  }
}

function openRollout(row: Release) {
  current.value = row;
  percent.value = row.rollout_percent;
  rolloutVisible.value = true;
}

function openEdit(row: Release) {
  editing.value = row;
  editForm.value = { releaseNote: row.release_note, apkUrl: row.apk_url ?? "" };
  editVisible.value = true;
}

async function submitEdit() {
  if (!editing.value) return;
  savingEdit.value = true;
  try {
    await apiPostJson("/app/admin/release-edit", props.adminToken, {
      versionCode: editing.value.version_code,
      releaseNote: editForm.value.releaseNote,
      apkUrl: editForm.value.apkUrl.trim(),
    });
    ElMessage.success("已保存");
    editVisible.value = false;
    await fetchReleases();
  } catch (error) {
    ElMessage.error(`保存失败：${(error as Error).message}`);
  } finally {
    savingEdit.value = false;
  }
}

/** 64 位哈希在表格里放不下，只露首尾各 6 位；全值悬停可见、点击即复制。 */
function shortHash(hash: string): string {
  return hash ? `${hash.slice(0, 6)}…${hash.slice(-6)}` : "—";
}

async function copyText(text: string) {
  if (!text) return ElMessage.warning("没有可复制的内容");
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success("已复制");
  } catch {
    ElMessage.error("复制失败：浏览器未授权剪贴板");
  }
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

/* 分页条：与表格留出一行呼吸空间，整体靠右更像工具条的延续 */
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* 表格里的哈希：等宽字体、可点击复制，交互暗示靠下划线虚线 */
.hash-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  cursor: copy;
  text-decoration: underline dashed var(--border-color);
  text-underline-offset: 3px;
}

/* 编辑对话框里的完整哈希：太长会撑破布局，允许折行 */
.hash {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  word-break: break-all;
  line-height: 1.5;
  margin-right: 8px;
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
