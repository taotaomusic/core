<template>
  <div class="manager">
    <div class="bar">
      <el-select v-model="sourceFilter" placeholder="全部音源" clearable style="width: 160px" @change="refresh">
        <el-option v-for="item in sources" :key="item.source" :label="item.displayName" :value="item.source" />
      </el-select>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新增账号</span>
      </el-button>
      <el-button
        type="success"
        plain
        :disabled="!loginableSources.length"
        @click="openLogin"
      >
        <el-icon><Iphone /></el-icon>
        <span>短信登录</span>
      </el-button>
      <el-button :loading="loading" @click="refresh">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
      <span class="bar-spacer"></span>
      <span class="pill">凭据只显示末四位 · 手机号保留号段与尾号</span>
    </div>

    <el-alert
      v-if="loginableSources.length"
      type="warning"
      :closable="false"
      show-icon
      class="notice"
      title="本页配置目前不影响播放结果"
    >
      <p>
        凭据会被带上，但<strong>上游当前不读取它</strong> —— 实测真实 VIP 账号与匿名的
        搜索、单曲信息、取址结果完全相同，请求头里去掉 <code>uid</code>/<code>token</code>
        后响应一个字节都不变。所以「配了账号音质或曲库会变好」目前不成立。
      </p>
      <p>
        真正起作用的是 <strong>uid 必须是纯数字</strong>：格式不对时上游会静默不下发
        播放地址，表现为「搜得到、放不出」。「测试」通过只说明这份凭据能取到地址 ——
        匿名同样能取到，所以它<strong>证明不了凭据已被上游识别</strong>。
      </p>
      <p>
        没有启用账号时按匿名请求，这是合法状态。保留这套配置是为了留一个唯一的接回点，
        等上游开放账号维度接口时能直接接上。
      </p>
    </el-alert>

    <el-table :data="accounts" v-loading="loading" empty-text="尚未配置音源账号">
      <el-table-column label="音源" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ displayNameOf(row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注名" min-width="130">
        <template #default="{ row }">
          <span class="label-cell">{{ row.label || "—" }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="maskedPhone" label="手机号" width="140">
        <template #default="{ row }">
          <span class="mono-cell">{{ row.maskedPhone || "—" }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="maskedToken" label="凭据" min-width="150">
        <template #default="{ row }">
          <span v-if="row.maskedToken" class="mono-cell">{{ row.maskedToken }}</span>
          <el-tag v-else type="info" size="small" effect="plain">未配置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="170">
        <template #default="{ row }">
          <el-tag :type="statusTypeOf(row.lastStatus)" size="small" effect="light">{{ statusTextOf(row.lastStatus) }}</el-tag>
          <div v-if="row.lastNote" class="sub">{{ row.lastNote }}</div>
          <div v-else-if="row.lastError" class="sub error">{{ row.lastError }}</div>
        </template>
      </el-table-column>
      <el-table-column label="上次测试" width="160">
        <template #default="{ row }">
          <span v-if="row.lastCheckedAt">{{ formatTime(row.lastCheckedAt) }}</span>
          <span v-else class="sub">未测试</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled === 1" @change="toggle(row, $event as boolean)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <el-button link type="primary" :loading="probingId === row.id" @click="probe(row)">测试</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="移除后播放链路不再取用它，确认继续？" @confirm="remove(row.id)">
            <template #reference><el-button link type="danger">移除</el-button></template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- 手工新增或编辑 -->
    <el-dialog v-model="showForm" :title="formId === null ? '新增音源账号' : `编辑账号 #${formId}`" width="480px">
      <el-form label-position="top">
        <el-form-item label="音源">
          <el-select v-model="form.source" :disabled="formId !== null" style="width: 100%">
            <el-option v-for="item in sources" :key="item.source" :label="item.displayName" :value="item.source" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注名">
          <el-input v-model="form.label" maxlength="40" placeholder="例如：主号" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" maxlength="11" placeholder="留空表示该账号不记手机号" />
        </el-form-item>
        <el-form-item label="Token">
          <el-input v-model="form.token" type="password" show-password autocomplete="off" placeholder="留空表示暂不配置凭据" />
        </el-form-item>
        <el-form-item label="上游账号 ID（uid）">
          <el-input
            v-model="form.uid"
            maxlength="64"
            :placeholder="uidMustBeNumeric ? '必须是纯数字，例如 12345678' : '留空表示不记账号 ID'"
          />
          <p v-if="uidMustBeNumeric" class="uid-hint">
            {{ displayNameOf(form.source) }}只认纯数字账号 ID。填成非数字时上游不会报错，
            但会拒绝下发任何播放地址 —— 表现为「搜得到、放不出」，换 token 也救不回来。
            保存时服务端会拦下非数字的值。
          </p>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <p class="hint">凭据只发送到本服务端保存，客户端与列表接口都不会拿到明文。</p>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 短信验证码登录 -->
    <el-dialog v-model="showLogin" title="短信验证码登录" width="440px" @closed="resetLogin">
      <el-form label-position="top">
        <el-form-item label="音源">
          <el-select v-model="login.source" style="width: 100%">
            <el-option
              v-for="item in loginableSources"
              :key="item.source"
              :label="item.displayName"
              :value="item.source"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="login.phone" maxlength="11" placeholder="11 位手机号" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="code-row">
            <el-input v-model="login.code" maxlength="8" placeholder="收到的验证码" />
            <el-button :loading="sending" :disabled="!phoneValid" @click="sendCode">
              {{ countdown > 0 ? `${countdown} 秒后重发` : "获取验证码" }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="备注名（可选）">
          <el-input v-model="login.label" maxlength="40" placeholder="留空则沿用已有备注名" />
        </el-form-item>
      </el-form>
      <p class="hint">验证码由上游下发并按条计费，同一号码 15 分钟内最多 3 次。</p>
      <template #footer>
        <el-button @click="showLogin = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!phoneValid || login.code.length < 4" @click="submitLogin">
          登录并保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { Plus, Refresh, Iphone } from "@element-plus/icons-vue";
import { apiDelete, apiGet, apiPatch, apiPostJson, apiPut, formatTime } from "../api";

type MusicSourceAccount = {
  id: number;
  source: string;
  label: string;
  maskedPhone: string;
  maskedToken: string;
  uid: string;
  enabled: number;
  remark: string;
  lastStatus: string;
  lastError: string;
  lastNote: string;
  lastCheckedAt: number | null;
};

type SourceOption = {
  source: string;
  displayName: string;
  supportsLogin: boolean;
  /**
   * 该音源的账号 ID（`uid`）是否必须是纯数字。
   *
   * 由服务端 `/available` 下发（源头是 `MusicSourceCredentialManager.numericUidOnly`），
   * 前端不硬编码音源名 —— 否则后端加一个新音源时这里还得跟着改。
   */
  numericUidOnly: boolean;
};

const props = defineProps<{ adminToken: string }>();

const accounts = ref<MusicSourceAccount[]>([]);
const sources = ref<SourceOption[]>([]);
const sourceFilter = ref("");
const loading = ref(false);
const saving = ref(false);
const probingId = ref<number | null>(null);

const showForm = ref(false);
const formId = ref<number | null>(null);
const form = ref({ source: "", label: "", phone: "", token: "", uid: "", remark: "" });

const showLogin = ref(false);
const sending = ref(false);
const countdown = ref(0);
const login = ref({ source: "", phone: "", code: "", label: "" });
let countdownTimer: number | undefined;

/**
 * 只有支持手机号登录的音源才出现在登录表单里。
 *
 * 这个判断由服务端给出（`/available` 的 `supportsLogin`），前端不硬编码音源名 ——
 * 否则后端加一个新音源时前端还得跟着改一遍。
 */
const loginableSources = computed(() => sources.value.filter((item) => item.supportsLogin));

/**
 * 当前表单选中的音源是否要求 uid 必须是纯数字。
 *
 * 规则来自服务端（`/available` 的 `numericUidOnly`），这里只用来决定提示文案与提交前的预检。
 * **真正的拦截在服务端**（`MusicSourceAdminController.assertUidShape`）——
 * 前端能绕过（改请求、旧版页面），服务端那层不能省。
 */
const uidMustBeNumeric = computed(
  () => sources.value.find((item) => item.source === form.value.source)?.numericUidOnly === true,
);

const phoneValid = computed(() => /^1[3-9]\d{9}$/.test(login.value.phone.trim()));

function displayNameOf(source: string): string {
  return sources.value.find((item) => item.source === source)?.displayName ?? source;
}

function statusTypeOf(status: string): "success" | "danger" | "info" {
  if (status === "ok") return "success";
  if (status === "invalid") return "danger";
  return "info";
}

function statusTextOf(status: string): string {
  if (status === "ok") return "可用";
  if (status === "invalid") return "不可用";
  return "未测试";
}

async function loadSources() {
  try {
    sources.value = await apiGet<SourceOption[]>("/app/admin/music-sources/available", props.adminToken);
  } catch (error) {
    ElMessage.error(`读取音源清单失败：${(error as Error).message}`);
  }
}

async function refresh() {
  loading.value = true;
  try {
    const query = sourceFilter.value ? `?source=${encodeURIComponent(sourceFilter.value)}` : "";
    accounts.value = await apiGet<MusicSourceAccount[]>(`/app/admin/music-sources${query}`, props.adminToken);
  } catch (error) {
    ElMessage.error(`读取音源账号失败：${(error as Error).message}`);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  formId.value = null;
  form.value = { source: sources.value[0]?.source ?? "", label: "", phone: "", token: "", uid: "", remark: "" };
  showForm.value = true;
}

function openEdit(row: MusicSourceAccount) {
  formId.value = row.id;
  // 掩码值不能回填进输入框 —— 提交上去会把 `••••••••1234` 当成新 token 存起来。
  // 留空表示「不改动」，这是后端 PATCH 的语义。
  form.value = { source: row.source, label: row.label, phone: "", token: "", uid: row.uid, remark: row.remark };
  showForm.value = true;
}

async function save() {
  // 提交前先本地拦一次非数字 uid：省一次往返，也把原因说清楚。
  // 服务端同样会拦（`assertUidShape`），这层只是体验优化，不能替代它。
  if (uidMustBeNumeric.value && form.value.uid !== "" && !/^\d+$/.test(form.value.uid)) {
    ElMessage.error(`${displayNameOf(form.value.source)}的账号 ID 必须是纯数字`);
    return;
  }
  saving.value = true;
  try {
    if (formId.value === null) {
      await apiPostJson("/app/admin/music-sources", props.adminToken, {
        source: form.value.source,
        label: form.value.label,
        phone: form.value.phone,
        token: form.value.token,
        uid: form.value.uid,
        remark: form.value.remark,
      });
      ElMessage.success("已新增音源账号");
    } else {
      // 只提交真正填过的字段：空字符串代表「清空」，未出现代表「不动」。
      const payload: Record<string, string> = {
        label: form.value.label,
        uid: form.value.uid,
        remark: form.value.remark,
      };
      if (form.value.phone !== "") payload.phone = form.value.phone;
      if (form.value.token !== "") payload.token = form.value.token;
      await apiPatch(`/app/admin/music-sources/${formId.value}`, props.adminToken, payload);
      ElMessage.success("已更新音源账号");
    }
    showForm.value = false;
    await refresh();
  } catch (error) {
    ElMessage.error(`保存失败：${(error as Error).message}`);
  } finally {
    saving.value = false;
  }
}

async function toggle(row: MusicSourceAccount, enabled: boolean) {
  try {
    await apiPut(`/app/admin/music-sources/${row.id}/enabled`, props.adminToken, { enabled });
    ElMessage.success(enabled ? "已启用" : "已停用");
    await refresh();
  } catch (error) {
    ElMessage.error(`操作失败：${(error as Error).message}`);
    await refresh();
  }
}

async function probe(row: MusicSourceAccount) {
  probingId.value = row.id;
  try {
    const result = await apiPostJson<{ note: string }>(
      `/app/admin/music-sources/${row.id}/probe`,
      props.adminToken,
      {},
    );
    ElMessage.success(`测试通过：${result.note}`);
  } catch (error) {
    ElMessage.error(`测试失败：${(error as Error).message}`);
  } finally {
    probingId.value = null;
    await refresh();
  }
}

async function remove(id: number) {
  try {
    await apiDelete(`/app/admin/music-sources/${id}`, props.adminToken);
    ElMessage.success("已移除");
    await refresh();
  } catch (error) {
    ElMessage.error(`移除失败：${(error as Error).message}`);
  }
}

function openLogin() {
  login.value = { source: loginableSources.value[0]?.source ?? "", phone: "", code: "", label: "" };
  showLogin.value = true;
}

function resetLogin() {
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer);
  countdownTimer = undefined;
  countdown.value = 0;
}

function startCountdown() {
  countdown.value = 60;
  resetLoginTimerOnly();
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0) resetLoginTimerOnly();
  }, 1000);
}

function resetLoginTimerOnly() {
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer);
  countdownTimer = undefined;
}

async function sendCode() {
  sending.value = true;
  try {
    await apiPostJson("/app/admin/music-sources/sms", props.adminToken, {
      source: login.value.source,
      phone: login.value.phone.trim(),
    });
    ElMessage.success("验证码已发送");
    startCountdown();
  } catch (error) {
    ElMessage.error(`发送失败：${(error as Error).message}`);
  } finally {
    sending.value = false;
  }
}

async function submitLogin() {
  saving.value = true;
  try {
    await apiPostJson("/app/admin/music-sources/login", props.adminToken, {
      source: login.value.source,
      phone: login.value.phone.trim(),
      code: login.value.code.trim(),
      label: login.value.label,
    });
    ElMessage.success("登录成功，凭据已保存");
    showLogin.value = false;
    await refresh();
  } catch (error) {
    ElMessage.error(`登录失败：${(error as Error).message}`);
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  await loadSources();
  await refresh();
});

onUnmounted(resetLoginTimerOnly);
</script>

<style scoped>
.manager { padding: 8px 0; }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.bar-spacer { flex: 1; min-width: 8px; }
.notice { margin-bottom: 18px; }
.notice p { margin: 4px 0; font-size: 13px; line-height: 1.65; }

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

/*
  以下三处原本硬编码 #7a7370 —— 那是按亮色背景调出来的灰，
  暗色模式下对比度极低。一律改用主题变量。
*/
.hint { color: var(--el-text-color-secondary); font-size: 13px; }

/* uid 输入项下的形状提示。el-form-item__content 是 flex 行，不占满宽会挤在输入框右侧。 */
.uid-hint {
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin: 6px 0 0;
  padding: 8px 10px;
  background: var(--hint-bg);
  border-radius: var(--radius-sm);
}

.sub { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 3px; line-height: 1.5; }
.sub.error { color: var(--el-color-danger); }

.label-cell { font-weight: 550; color: var(--el-text-color-primary); }

.mono-cell {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.code-row { display: flex; gap: 8px; width: 100%; }
.code-row .el-input { flex: 1; }
</style>
