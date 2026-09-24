<template>
  <div class="manager">
    <el-card shadow="never" class="card">
      <template #header>强制更新下限</template>

      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 18px">
        <template #title>顺序永远是：先把目标版本放到 100%，再抬高下限</template>
        接口内置守卫 —— 必须已存在放量 100% 且版本号不低于该下限的发布，否则返回 409。
        这是为了堵住一条变砖路径：强制更新的客户端只会收到全量版本，若不存在这样的版本，
        它们会被拦在门外却拿不到升级包。
      </el-alert>

      <el-descriptions :column="2" border style="margin-bottom: 18px">
        <el-descriptions-item label="当前下限">
          <strong class="value-strong">{{ minSupported === 0 ? "未设置" : minSupported }}</strong>
        </el-descriptions-item>
        <el-descriptions-item label="可作为下限的最高版本">
          <strong v-if="rolledOut" class="value-strong">{{ rolledOut.version_code }}（{{ rolledOut.version_name }}）</strong>
          <span v-else class="warn">无 —— 还没有放量 100% 的版本</span>
        </el-descriptions-item>
        <el-descriptions-item label="发布记录">{{ releases.length }} 条</el-descriptions-item>
        <el-descriptions-item label="补丁记录">{{ patchCount }} 条</el-descriptions-item>
      </el-descriptions>

      <el-form label-width="110px">
        <el-form-item label="设为">
          <el-input-number v-model="target" :min="0" :max="rolledOut?.version_code ?? 0" />
          <span class="hint" style="margin-left: 10px">0 表示取消强制更新下限</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submit">更新下限</el-button>
          <el-button @click="refresh" :loading="loading">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card">
      <template #header>连通性</template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="API 根地址">{{ apiBase }}</el-descriptions-item>
        <el-descriptions-item label="管理令牌">
          <el-tag :type="tokenOk === null ? 'info' : tokenOk ? 'success' : 'danger'" size="small">
            {{ tokenOk === null ? "未验证" : tokenOk ? "有效" : "无效或已变更" }}
          </el-tag>
          <el-button link type="primary" style="margin-left: 10px" @click="forgetToken">
            清除并重新输入
          </el-button>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { apiGet, apiGetPublic, apiPostJson } from "../api";

interface ReleaseRow {
  version_code: number;
  version_name: string;
  rollout_percent: number;
  enabled: number;
}

/** bootstrap 的下限藏在 update 里，不是顶层 —— 这里取错会一直显示 0。 */
interface Bootstrap {
  update: { minSupportedVersionCode: number };
}

const props = defineProps<{ adminToken: string }>();
const emit = defineEmits<{ (event: "forget-token"): void }>();

const releases = ref<ReleaseRow[]>([]);
const patchCount = ref(0);
const minSupported = ref(0);
const target = ref(0);
const loading = ref(false);
const saving = ref(false);
const tokenOk = ref<boolean | null>(null);

const apiBase = `${window.location.origin}/api/v1`;

const rolledOut = computed(() =>
  releases.value
    .filter((item) => item.rollout_percent === 100 && item.enabled === 1)
    .sort((a, b) => b.version_code - a.version_code)[0],
);

onMounted(refresh);

async function refresh() {
  loading.value = true;
  try {
    // bootstrap 是公开接口，用一个不可能存在的低版本号问出当前下限。
    const bootstrap = await apiGetPublic<Bootstrap>("/app/bootstrap?versionCode=1&sdk=36&deviceId=admin-panel");
    minSupported.value = bootstrap.update.minSupportedVersionCode ?? 0;
    target.value = minSupported.value;
  } catch (error) {
    ElMessage.error(`读取下限失败：${(error as Error).message}`);
  }
  try {
    releases.value = await apiGet<ReleaseRow[]>("/app/admin/releases", props.adminToken);
    const patches = await apiGet<unknown[]>("/app/admin/patches", props.adminToken);
    patchCount.value = patches.length;
    tokenOk.value = true;
  } catch (error) {
    tokenOk.value = false;
    ElMessage.error(`管理接口不可用：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

async function submit() {
  if (target.value === minSupported.value) return ElMessage.info("下限没有变化");
  try {
    await ElMessageBox.confirm(
      target.value === 0
        ? "确定要取消强制更新下限吗？"
        : `确定要把下限抬到 ${target.value} 吗？低于它的客户端会被强制更新，无法继续使用。`,
      "确认",
      { type: "warning" },
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    await apiPostJson("/app/admin/min-version", props.adminToken, { versionCode: target.value });
    ElMessage.success("已更新");
    await refresh();
  } catch (error) {
    ElMessage.error(`更新失败：${(error as Error).message}`);
  } finally {
    saving.value = false;
  }
}

function forgetToken() {
  emit("forget-token");
}
</script>

<style scoped>
.manager {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 4px 0;
  max-width: 900px;
}

.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.warn {
  color: var(--el-color-warning);
}

/* 关键数值加粗等宽，在一堆描述项里一眼能定位 */
.value-strong {
  font-size: 15px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: var(--el-text-color-primary);
}
</style>
