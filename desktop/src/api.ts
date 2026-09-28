export const ENDPOINT = "https://music.xydaigua.cn";

/** POST 后端接口，取信封 {code,message,data} 的 data（兼容无信封），非 0 码抛错。 */
export async function postJson(path: string, body: unknown): Promise<any> {
  const resp = await fetch(`${ENDPOINT}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  const json = await resp.json().catch(() => ({}));
  if (!resp.ok || (json && json.code !== undefined && json.code !== 0)) {
    throw new Error(json?.message || `HTTP ${resp.status}`);
  }
  return json?.data ?? json;
}

// ---- 会话持久化（localStorage，Tauri webview 跨启动保留） ----

const KEY = "taotao.session";
type Stored = { accessToken: string; refreshToken: string; expiresAt: number };

/** 登录/注册/刷新拿到的 token 包（含 accessToken/refreshToken/expiresIn）落盘。 */
export function saveSession(data: any): void {
  if (!data?.accessToken || !data?.refreshToken) return;
  const s: Stored = {
    accessToken: data.accessToken,
    refreshToken: data.refreshToken,
    expiresAt: Date.now() + (Number(data.expiresIn) || 900) * 1000,
  };
  localStorage.setItem(KEY, JSON.stringify(s));
}

export function clearSession(): void {
  localStorage.removeItem(KEY);
}

function load(): Stored | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Stored) : null;
  } catch {
    return null;
  }
}

/**
 * 启动时恢复登录：accessToken 未过期直接用；过期则用 refreshToken 走 /auth/refresh
 * 续期（后端会轮换 refreshToken，续期结果重新落盘）。都不行则返回 null 走登录页。
 */
export async function restoreToken(): Promise<string | null> {
  const s = load();
  if (!s) return null;
  if (s.accessToken && s.expiresAt > Date.now() + 30_000) return s.accessToken;
  if (!s.refreshToken) {
    clearSession();
    return null;
  }
  try {
    const data = await postJson("/api/v1/auth/refresh", { refreshToken: s.refreshToken });
    saveSession(data);
    return data.accessToken as string;
  } catch {
    clearSession();
    return null;
  }
}
