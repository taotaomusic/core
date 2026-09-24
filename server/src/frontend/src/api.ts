/**
 * 后台管理的 API 薄封装。
 *
 * 服务端所有 JSON 响应都套一层信封 `{ code, message, data }`，
 * 业务失败时 code 非 0 且 HTTP 也是 4xx/5xx。错误文案统一从顶层 `message` 取 ——
 * 那是服务端刻意压平成字符串的（校验失败时 NestJS 原本给的是数组）。
 *
 * 认证方式：只有登录后签发的会话令牌，走 `Authorization: Bearer`。
 * 历史上还支持 `.env` 里的静态 `X-Admin-Token`，但它会绕过 2FA、IP 白名单、
 * 会话撤销与审计归属，已整体移除。
 */

const BASE = "/api/v1";

/** 管理员公开身份。`must_change_password` 为真时前端必须先走强制改密。 */
export interface AdminIdentity {
  id: number;
  username: string;
  role: string;
  display_name: string;
  must_change_password?: boolean;
}

interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

async function unwrap<T>(response: Response): Promise<T> {
  const text = await response.text();
  let body: Envelope<T> | null = null;
  try {
    body = JSON.parse(text) as Envelope<T>;
  } catch {
    throw new Error(text.slice(0, 200) || `HTTP ${response.status}`);
  }
  if (!response.ok || body.code !== 0) {
    throw new Error(body.message || `HTTP ${response.status}`);
  }
  return body.data;
}

/**
 * 拼装鉴权头。
 *
 * 令牌缺失时**直接抛错**，绝不拼出 `Bearer undefined`。那种请求会被服务端判成
 * 无效会话，前端只看到一句「管理员认证失败」，真正的原因被完全掩盖 ——
 * 这个坑踩过一次：`AuditLogViewer` / `AdminUserManager` 收到的是 `:token`
 * 而不是 `:admin-token`，`props.adminToken` 恒为 undefined，两个页签稳定 401。
 */
function authHeaders(adminToken: string): Record<string, string> {
  if (!adminToken) throw new Error("缺少管理员令牌，请重新登录");
  return { "authorization": `Bearer ${adminToken}` };
}

export async function apiGet<T>(path: string, adminToken: string): Promise<T> {
  const headers = authHeaders(adminToken);
  return unwrap<T>(await fetch(`${BASE}${path}`, { headers }));
}

export async function apiPostJson<T>(path: string, adminToken: string, payload: unknown): Promise<T> {
  const headers = { "content-type": "application/json", ...authHeaders(adminToken) };
  return unwrap<T>(await fetch(`${BASE}${path}`, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  }));
}

/** 管理端删除资源。204 无响应体，因此不能走 JSON 信封解包。 */
export async function apiDelete(path: string, adminToken: string): Promise<void> {
  const response = await fetch(`${BASE}${path}`, { method: "DELETE", headers: authHeaders(adminToken) });
  if (response.status === 204) return;
  await unwrap<unknown>(response);
}

/**
 * 上传原始字节。
 *
 * 服务端对这两条上传路由**绕开了 JSON 解析器**（见 main.ts 的 RAW_BODY_PATHS），
 * 所以这里必须直接发 ArrayBuffer，不能用 FormData 也不能 base64。
 */
export async function apiPostBytes<T>(path: string, adminToken: string, bytes: ArrayBuffer): Promise<T> {
  const headers = { "content-type": "application/octet-stream", ...authHeaders(adminToken) };
  return unwrap<T>(await fetch(`${BASE}${path}`, {
    method: "POST",
    headers,
    body: bytes,
  }));
}

/** 不带鉴权的公开接口（bootstrap 刻意允许无令牌访问）。 */
export function apiGetPublic<T>(path: string): Promise<T> {
  return fetch(`${BASE}${path}`).then(unwrap<T>);
}

/** 服务端会边写盘边算 sha256 并比对，不一致直接拒绝，所以上传前必须先算好。 */
export async function sha256Of(bytes: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export function formatSize(bytes: number): string {
  if (!bytes) return "0 B";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

export function formatTime(milliseconds: number): string {
  if (!milliseconds) return "—";
  return new Date(milliseconds).toLocaleString("zh-CN", { hour12: false });
}

/** PATCH 请求（不走 JSON 信封解包，返回原始响应） */
export async function apiPatch<T>(path: string, adminToken: string, payload: unknown): Promise<T> {
  const headers = { "content-type": "application/json", ...authHeaders(adminToken) };
  return unwrap<T>(await fetch(`${BASE}${path}`, {
    method: "PATCH",
    headers,
    body: JSON.stringify(payload),
  }));
}

/**
 * 全量替换一个子资源的 PUT。
 *
 * 与 [apiPatch] 分开是因为语义不同：PATCH 只改提交过的字段，PUT 是「把这块状态设成这个值」。
 * 音源账号的启停走 PUT（`/enabled`），传的就是目标状态本身。
 */
export async function apiPut<T>(path: string, adminToken: string, payload: unknown): Promise<T> {
  const headers = { "content-type": "application/json", ...authHeaders(adminToken) };
  return unwrap<T>(await fetch(`${BASE}${path}`, {
    method: "PUT",
    headers,
    body: JSON.stringify(payload),
  }));
}

/** 管理员认证 —— 登录（用户名密码） */
export async function adminLogin(username: string, password: string) {
  const response = await fetch(`${BASE}/admin/auth/login`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  return unwrap<{ token?: string; admin?: AdminIdentity; requires_totp?: boolean; temp_token?: string; admin_id?: number }>(response);
}

/** 管理员认证 —— TOTP 二次验证 */
export async function adminTotpVerify(tempToken: string, token: string) {
  const response = await fetch(`${BASE}/admin/auth/totp-verify`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    // temp_token 是第一步登录签发的挑战票据：没有它服务端不认第二步，
    // 这是把「密码已验证」这个事实带到第二步的唯一凭据。
    body: JSON.stringify({ temp_token: tempToken, token }),
  });
  return unwrap<{ token: string; admin: AdminIdentity }>(response);
}

/** 管理员认证 —— 退出登录 */
export async function adminLogout(token: string) {
  await fetch(`${BASE}/admin/auth/logout`, {
    method: "POST",
    headers: { "authorization": `Bearer ${token}` },
  });
}

/** 管理员认证 —— 获取当前登录用户信息 */
export async function adminMe(token: string) {
  const response = await fetch(`${BASE}/admin/auth/me`, {
    headers: { "authorization": `Bearer ${token}` },
  });
  return unwrap<AdminIdentity>(response);
}

/**
 * 管理员认证 —— 修改本人密码。
 *
 * 强制改密页与「主动改密」共用这一个接口。服务端改密成功后会顺手清掉
 * `must_change_password` 标记并撤销其它会话（保留当前这条）。
 */
export async function adminChangePassword(token: string, oldPassword: string, newPassword: string) {
  const response = await fetch(`${BASE}/admin/auth/change-password`, {
    method: "POST",
    headers: { "content-type": "application/json", "authorization": `Bearer ${token}` },
    body: JSON.stringify({ oldPassword, newPassword }),
  });
  if (response.status === 204) return;
  await unwrap<unknown>(response);
}
