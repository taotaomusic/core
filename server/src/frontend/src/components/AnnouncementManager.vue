<template>
  <div class="manager">
    <div class="bar">
      <el-button type="primary" @click="openCreate">
        <el-icon><Bell /></el-icon>
        <span>发布公告</span>
      </el-button>
      <el-button :loading="loading" @click="fetchAnnouncements">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill">
        共 <strong>{{ announcements.length }}</strong> 条 · 公开接口只返回已发布的公告
      </span>
    </div>

    <el-table :data="announcements" v-loading="loading" empty-text="还没有公告">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="标题" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="title-cell">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column label="置顶" width="82">
        <template #default="{ row }"><el-tag v-if="row.pinned === 1" type="danger" size="small" effect="light">置顶</el-tag></template>
      </el-table-column>
      <el-table-column prop="content" label="正文" min-width="280" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" effect="light">{{ row.enabled === 1 ? "已发布" : "已下线" }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="175">
        <template #default="{ row }">{{ formatTime(row.published_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" :type="row.pinned === 1 ? 'info' : 'primary'" plain @click="togglePinned(row)">
            {{ row.pinned === 1 ? "取消置顶" : "置顶" }}
          </el-button>
          <el-button size="small" :type="row.enabled === 1 ? 'warning' : 'success'" plain @click="toggle(row)">
            {{ row.enabled === 1 ? "下线" : "发布" }}
          </el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑公告' : '发布公告'" width="620px" destroy-on-close>
      <el-form label-width="64px">
        <el-form-item label="标题" required><el-input v-model="form.title" maxlength="80" show-word-limit /></el-form-item>
        <el-form-item label="正文" required><el-input v-model="form.content" type="textarea" :rows="8" maxlength="5000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ editing ? "保存" : "发布" }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Bell, Refresh } from "@element-plus/icons-vue";
import { apiDelete, apiGet, apiPostJson, formatTime } from "../api";

interface Announcement {
  id: number;
  title: string;
  content: string;
  enabled: number;
  pinned: number;
  published_at: number;
}

const props = defineProps<{ adminToken: string }>();
const announcements = ref<Announcement[]>([]);
const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const editing = ref<Announcement | null>(null);
const form = ref({ title: "", content: "" });

onMounted(fetchAnnouncements);

async function fetchAnnouncements() {
  loading.value = true;
  try {
    announcements.value = await apiGet<Announcement[]>("/app/admin/announcements", props.adminToken);
  } catch (error) {
    ElMessage.error(`加载失败：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  form.value = { title: "", content: "" };
  dialogVisible.value = true;
}

function openEdit(item: Announcement) {
  editing.value = item;
  form.value = { title: item.title, content: item.content };
  dialogVisible.value = true;
}

async function save() {
  const title = form.value.title.trim();
  const content = form.value.content.trim();
  if (!title || !content) return ElMessage.warning("请填写标题和正文");
  saving.value = true;
  try {
    const path = editing.value ? `/app/admin/announcements/${editing.value.id}` : "/app/admin/announcements";
    await apiPostJson(path, props.adminToken, { title, content });
    ElMessage.success(editing.value ? "公告已保存" : "公告已发布");
    dialogVisible.value = false;
    await fetchAnnouncements();
  } catch (error) {
    ElMessage.error(`保存失败：${(error as Error).message}`);
  } finally {
    saving.value = false;
  }
}

async function toggle(item: Announcement) {
  try {
    await apiPostJson(`/app/admin/announcements/${item.id}/enabled`, props.adminToken, { enabled: item.enabled !== 1 });
    ElMessage.success(item.enabled === 1 ? "公告已下线" : "公告已发布");
    await fetchAnnouncements();
  } catch (error) {
    ElMessage.error(`操作失败：${(error as Error).message}`);
  }
}

async function togglePinned(item: Announcement) {
  try {
    await apiPostJson(`/app/admin/announcements/${item.id}/pinned`, props.adminToken, { pinned: item.pinned !== 1 });
    ElMessage.success(item.pinned === 1 ? "已取消置顶" : "公告已置顶");
    await fetchAnnouncements();
  } catch (error) {
    ElMessage.error(`操作失败：${(error as Error).message}`);
  }
}

async function remove(item: Announcement) {
  try {
    await ElMessageBox.confirm(`确定删除公告“${item.title}”吗？删除后无法恢复。`, "确认删除", { type: "warning" });
    await apiDelete(`/app/admin/announcements/${item.id}`, props.adminToken);
    ElMessage.success("公告已删除");
    await fetchAnnouncements();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(`删除失败：${(error as Error).message}`);
  }
}
</script>

<style scoped>
.manager { padding: 4px 0; }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
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

.title-cell { font-weight: 550; color: var(--el-text-color-primary); }

.hint { font-size: 13px; color: var(--el-text-color-secondary); }
</style>
