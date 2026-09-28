export const ENDPOINT = "https://music.xydaigua.cn";

// ---- 会话与令牌 ----

const KEY = "taotao.session";
type Stored = { accessToken: string; refreshToken: string; expiresAt: number };

let accessToken: string | null = null;

/** 抛出它表示刷新也失败、需要重新登录（上层据此登出）。 */
export class SessionExpired extends Error {
  constructor() {
    super("SESSION_EXPIRED");
  }
}

function loadStored(): Stored | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Stored) : null;
  } catch {
    return null;
  }
}

/** 登录/注册/刷新拿到的 token 包落盘并更新内存 accessToken。 */
export function saveSession(data: any): void {
  if (!data?.accessToken || !data?.refreshToken) return;
  const s: Stored = {
    accessToken: data.accessToken,
    refreshToken: data.refreshToken,
    expiresAt: Date.now() + (Number(data.expiresIn) || 900) * 1000,
  };
  localStorage.setItem(KEY, JSON.stringify(s));
  accessToken = data.accessToken;
}

export function clearSession(): void {
  localStorage.removeItem(KEY);
  accessToken = null;
}

/** 用信封响应取 data；非 0 码抛错。 */
async function unwrap(resp: Response): Promise<any> {
  const json = await resp.json().catch(() => ({}));
  if (!resp.ok || (json && json.code !== undefined && json.code !== 0)) {
    throw new Error(json?.message || `HTTP ${resp.status}`);
  }
  return json?.data ?? json;
}

/** POST 明文接口（登录/注册/验证码），不带令牌。 */
export async function postJson(path: string, body: unknown): Promise<any> {
  const resp = await fetch(`${ENDPOINT}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  return unwrap(resp);
}

/** 用 refreshToken 续期（后端轮换），成功返回新 accessToken 并落盘，失败清会话。 */
async function refreshAccess(): Promise<string | null> {
  const s = loadStored();
  if (!s?.refreshToken) {
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

/** 启动恢复登录：accessToken 未过期直接用，过期则刷新；都不行返回 null。 */
export async function restoreToken(): Promise<string | null> {
  const s = loadStored();
  if (!s) return null;
  if (s.accessToken && s.expiresAt > Date.now() + 30_000) {
    accessToken = s.accessToken;
    return s.accessToken;
  }
  return refreshAccess();
}

/** 带令牌的 GET；遇 401 自动刷新并重放一次，刷新失败抛 SessionExpired。 */
async function authedGet(path: string, accept: string): Promise<Response> {
  const doFetch = (tok: string | null) =>
    fetch(`${ENDPOINT}${path}`, { headers: { Authorization: `Bearer ${tok ?? ""}`, Accept: accept } });
  let resp = await doFetch(accessToken);
  if (resp.status === 401) {
    const t = await refreshAccess();
    if (!t) throw new SessionExpired();
    resp = await doFetch(t);
    if (resp.status === 401) throw new SessionExpired();
  }
  return resp;
}

// ---- 搜索与播放 ----

export type Song = {
  id: number;
  title: string;
  artist: string;
  source: string;
  mid?: string;
  type?: number;
  coverUrl?: string;
};

/** 搜索：/api/v1/search 返回裸 NDJSON（{type:"song",data} / {type:"end",meta}）。 */
export async function searchSongs(keyword: string): Promise<Song[]> {
  const q = new URLSearchParams({ keyword, page: "1", num: "60", quality: "4", source: "kuwo" });
  const resp = await authedGet(`/api/v1/search?${q}`, "application/x-ndjson, application/json");
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const text = await resp.text();
  const songs: Song[] = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    let rec: any;
    try { rec = JSON.parse(line); } catch { continue; }
    if (rec?.type === "song" && rec.data) {
      const d = rec.data;
      if (Number(d.id) > 0 || d.mid) {
        songs.push({
          id: Number(d.id) || 0,
          title: d.title || "未知歌曲",
          artist: d.artist || "未知歌手",
          source: d.source || "kuwo",
          mid: d.mid || undefined,
          type: d.type ?? undefined,
          coverUrl: d.coverUrl || undefined,
        });
      }
    }
  }
  return songs;
}

/** 解析上游直链：/api/v1/songs/:id/link（信封 data.url）。 */
export async function resolveLink(song: Song): Promise<string> {
  const q = new URLSearchParams({ quality: "4", source: song.source || "kuwo" });
  if (song.mid) q.set("mid", song.mid);
  if (song.type != null) q.set("type", String(song.type));
  const resp = await authedGet(`/api/v1/songs/${song.id}/link?${q}`, "application/json");
  const data = await unwrap(resp);
  if (!data?.url) throw new Error("无法获取播放地址");
  return data.url as string;
}

/** 把异常转成用户可读文案：网络类统一提示，其它保留原文。 */
export function readableError(e: unknown): string {
  const m = e instanceof Error ? e.message : String(e);
  if (/Failed to fetch|NetworkError|load failed/i.test(m)) return "网络连接失败，请检查网络后重试";
  return m || "操作失败，请稍后重试";
}
