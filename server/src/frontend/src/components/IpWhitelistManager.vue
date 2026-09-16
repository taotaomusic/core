<template>
  <el-card shadow="never" class="card">
    <template #header>IP 白名单</template>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px">
      配置后仅允许列表中的 IP 地址登录管理后台。留空表示不限制。
    </el-alert>
    <el-input v-model="whitelist" type="textarea" :rows="4"
      placeholder="每行一个 IP 地址，例如：&#10;192.168.1.1&#10;10.0.0.1" />
    <div style="margin-top: 12px">
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { apiGet, apiPostJson } from "../api";

const props = defineProps<{ adminToken: string; adminId: number }>();
const whitelist = ref("");
const saving = ref(false);

onMounted(async () => {
  try {
    const data = await apiGet<{ whitelist: string }>(`/admin/ip-whitelist/${props.adminId}`, props.adminToken);
    whitelist.value = data.whitelist ?? "";
  } catch { /* 忽略 */ }
});

async function save() {
  saving.value = true;
  try {
    await apiPostJson(`/admin/ip-whitelist/${props.adminId}`, props.adminToken, { whitelist: whitelist.value });
    ElMessage.success("已保存");
  } catch (e) {
    ElMessage.error(`保存失败：${(e as Error).message}`);
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.card {
  border: 1px solid var(--el-border-color-light);
}
</style>
