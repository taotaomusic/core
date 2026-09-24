<template>
  <div class="manager">
    <div class="bar">
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>添加管理员</span>
      </el-button>
      <el-button @click="fetchUsers" :loading="loading">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill">共 <strong>{{ users.length }}</strong> 位管理员</span>
    </div>

    <el-table :data="users" v-loading="loading" empty-text="暂无管理员">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="用户" min-width="170">
        <template #default="{ row }">
          <div class="admin-cell">
            <el-avatar :size="30" class="admin-avatar">
              {{ (row.display_name || row.username || "?").charAt(0).toUpperCase() }}
            </el-avatar>
            <div class="admin-meta">
              <div class="admin-name">{{ row.display_name || row.username }}</div>
              <div class="muted">@{{ row.username }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="180">
        <template #default="{ row }">{{ row.email || "未设置" }}</template>
      </el-table-column>
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-tag :type="roleTag(row.role)" size="small" effect="light">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="2FA" width="88">
        <template #default="{ row }">
          <el-tag :type="row.totp_enabled ? 'success' : 'info'" size="small" effect="light">
            {{ row.totp_enabled ? "已启用" : "未启用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="86">
        <template #default="{ row }">
          <el-tag :type="row.disabled_at ? 'danger' : 'success'" size="small" effect="light">
            {{ row.disabled_at ? "已禁用" : "正常" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后登录" width="170">
        <template #default="{ row }">{{ row.last_login_at ? formatTime(row.last_login_at) : "从未" }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="openEdit(row)">编辑</el-button>
          <el-button size="small" text :type="row.disabled_at ? 'success' : 'warning'" @click="toggleDisabled(row)">
            {{ row.disabled_at ? "恢复" : "禁用" }}
          </el-button>
          <el-popconfirm title="确定删除？" @confirm="remove(row)">
            <template #reference><el-button size="small" text type="danger">删除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑管理员' : '添加管理员'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!editing" placeholder="字母数字下划线" />
        </el-form-item>
        <el-form-item v-if="!editing" label="密码" required>
          <el-input v-model="form.password" type="password" show-password placeholder="至少8位" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.role">
            <el-option label="超级管理员" value="super_admin" />
            <el-option label="管理员" value="admin" />
            <el-option label="观察者" value="viewer" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Plus, Refresh } from "@element-plus/icons-vue";
import { apiGet, apiPostJson, apiPatch, apiDelete, formatTime } from "../api";

type AdminUser = {
  id: number; username: string; display_name: string; email: string | null;
  role: string; totp_enabled: number; ip_whitelist: string | null;
  last_login_at: number | null; disabled_at: number | null;
};

const props = defineProps<{ adminToken: string }>();
const users = ref<AdminUser[]>([]);
const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const editing = ref<AdminUser | null>(null);
const form = ref({ username: "", password: "", displayName: "", email: "", role: "viewer" });

onMounted(fetchUsers);

async function fetchUsers() {
  loading.value = true;
  try {
    users.value = await apiGet<AdminUser[]>("/admin/auth/users", props.adminToken);
  } catch (e) {
    ElMessage.error(`加载失败：${(e as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  form.value = { username: "", password: "", displayName: "", email: "", role: "viewer" };
  dialogVisible.value = true;
}

function openEdit(user: AdminUser) {
  editing.value = user;
  form.value = {
    username: user.username,
    password: "",
    displayName: user.display_name,
    email: user.email ?? "",
    role: user.role,
  };
  dialogVisible.value = true;
}

async function save() {
  saving.value = true;
  try {
    if (editing.value) {
      await apiPatch(`/admin/auth/users/${editing.value.id}`, props.adminToken, {
        role: form.value.role,
        display_name: form.value.displayName,
        email: form.value.email || null,
      });
    } else {
      await apiPostJson("/admin/auth/users", props.adminToken, form.value);
    }
    ElMessage.success("已保存");
    dialogVisible.value = false;
    await fetchUsers();
  } catch (e) {
    ElMessage.error(`保存失败：${(e as Error).message}`);
  } finally {
    saving.value = false;
  }
}

async function toggleDisabled(user: AdminUser) {
  try {
    await apiPatch(`/admin/auth/users/${user.id}`, props.adminToken, {
      disabled: !user.disabled_at,
    });
    await fetchUsers();
  } catch (e) {
    ElMessage.error(`操作失败：${(e as Error).message}`);
  }
}

async function remove(user: AdminUser) {
  try {
    await apiDelete(`/admin/auth/users/${user.id}`, props.adminToken);
    ElMessage.success("已删除");
    await fetchUsers();
  } catch (e) {
    ElMessage.error(`删除失败：${(e as Error).message}`);
  }
}

function roleTag(role: string) {
  return role === "super_admin" ? "danger" : role === "admin" ? "warning" : "info";
}

function roleLabel(role: string) {
  return role === "super_admin" ? "超级管理员" : role === "admin" ? "管理员" : "观察者";
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

.admin-cell { display: flex; align-items: center; gap: 10px; }

.admin-avatar {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 650;
  color: #fff;
  background: linear-gradient(140deg, var(--primary-color), #ff9d6e);
}

.admin-meta { min-width: 0; }

.admin-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
