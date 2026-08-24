/**
 * 后台管理的 API 薄封装。
 *
 * 服务端所有 JSON 响应都套一层信封 `{ code, message, data }`，
 * 业务失败时 code 非 0 且 HTTP 也是 4xx/5xx。错误文案统一从顶层 `message` 取 ——
 * 那是服务端刻意压平成字符串的（校验失败时 NestJS 原本给的是数组）。
 */

const BASE = "/api/v1";

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

export function apiGet<T>(path: string, adminToken: string): Promise<T> {
  return fetch(`${BASE}${path}`, { headers: { "X-Admin-Token": adminToken } }).then(unwrap<T>);
}

export function apiPostJson<T>(path: string, adminToken: string, payload: unknown): Promise<T> {
  return fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", "X-Admin-Token": adminToken },
    body: JSON.stringify(payload),
  }).then(unwrap<T>);
}

/**
 * 上传原始字节。
 *
 * 服务端对这两条上传路由**绕开了 JSON 解析器**（见 main.ts 的 RAW_BODY_PATHS），
 * 所以这里必须直接发 ArrayBuffer，不能用 FormData 也不能 base64。
 */
export function apiPostBytes<T>(path: string, adminToken: string, bytes: ArrayBuffer): Promise<T> {
  return fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "content-type": "application/octet-stream", "X-Admin-Token": adminToken },
    body: bytes,
  }).then(unwrap<T>);
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
