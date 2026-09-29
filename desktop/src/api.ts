import { fetch as tauriFetch } from "@tauri-apps/plugin-http";

export const ENDPOINT = "https://music.xydaigua.cn";

// 打包后运行在 Tauri webview 里，window.fetch 受 CORS 约束（服务端默认不下发
// CORS 头），必须走 tauri-plugin-http 由 Rust 侧直连；纯浏览器 dev 没注入
// Tauri 环境时退回 window.fetch，保持原有行为。
const httpFetch: typeof fetch =
  "__TAURI_INTERNALS__" in window ? tauriFetch : (input, init) => fetch(input, init);

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
  const resp = await httpFetch(`${ENDPOINT}${path}`, {
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

/** 把 HeadersInit 统一展开成普通记录，便于追加 Authorization 头。 */
function headerRecordOf(headers: HeadersInit | undefined): Record<string, string> {
  const rec: Record<string, string> = {};
  if (!headers) return rec;
  if (headers instanceof Headers) {
    headers.forEach((value, key) => {
      rec[key] = value;
    });
  } else if (Array.isArray(headers)) {
    for (const pair of headers) rec[pair[0]] = pair[1];
  } else {
    Object.assign(rec, headers);
  }
  return rec;
}

/**
 * 带令牌的通用请求：遇 401 自动刷新并重放一次，刷新失败或重放仍 401
 * 抛 SessionExpired。其余方法/头/体由调用方通过 init 自定义。
 */
export async function authedRequest(path: string, init?: RequestInit): Promise<Response> {
  const baseHeaders = headerRecordOf(init?.headers);
  const doFetch = (tok: string | null) =>
    httpFetch(`${ENDPOINT}${path}`, {
      ...init,
      headers: { ...baseHeaders, Authorization: `Bearer ${tok ?? ""}` },
    });
  let resp = await doFetch(accessToken);
  if (resp.status === 401) {
    const t = await refreshAccess();
    if (!t) throw new SessionExpired();
    resp = await doFetch(t);
    if (resp.status === 401) throw new SessionExpired();
  }
  return resp;
}

/** 带令牌的 GET 快捷方式（保留原有 Accept 参数形态）。 */
function authedGet(path: string, accept: string): Promise<Response> {
  return authedRequest(path, { headers: { Accept: accept } });
}

// ---- 搜索与播放 ----

export type Song = {
  id: number;
  title: string;
  artist: string;
  album?: string;
  source: string;
  mid?: string;
  type?: number;
  coverUrl?: string;
  vip?: boolean;
  playable?: boolean;
  favorited?: boolean;
  duration?: string;
};

export type SearchResult = { songs: Song[]; total: number; hasMore: boolean };

/** 搜索：/api/v1/search 返回裸 NDJSON（{type:"song",data} / {type:"end",meta}）。 */
export async function searchSongs(keyword: string, page = 1): Promise<SearchResult> {
  const q = new URLSearchParams({ keyword, page: String(page), num: "60", quality: "4", source: "kuwo" });
  const resp = await authedGet(`/api/v1/search?${q}`, "application/x-ndjson, application/json");
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const text = await resp.text();
  const songs: Song[] = [];
  // 末行 end.meta 携带总数与是否还有下一页；解析不到时按"没有更多"处理
  let metaTotal: number | null = null;
  let metaHasMore = false;
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
          album: d.album || undefined,
          duration: d.duration || undefined,
          vip: !!d.vip,
          playable: !!d.playable,
          favorited: !!d.favorited,
        });
      }
    } else if (rec?.type === "end" && rec.meta) {
      metaTotal = Number(rec.meta.total);
      metaHasMore = rec.meta.hasMore === true;
    }
  }
  return {
    songs,
    total: metaTotal !== null && Number.isFinite(metaTotal) ? metaTotal : songs.length,
    hasMore: metaHasMore,
  };
}

/** 搜索联想词：data 为纯字符串数组。 */
export async function fetchSuggestions(keyword: string, limit = 10): Promise<string[]> {
  const q = new URLSearchParams({ keyword: keyword.trim(), limit: String(limit), source: "kuwo" });
  const resp = await authedGet(`/api/v1/search/suggestions?${q}`, "application/json");
  const data = await unwrap(resp);
  return Array.isArray(data) ? data.map((it) => String(it)) : [];
}

/** 热搜词：data 为对象数组，只取 keyword 字段。 */
export async function fetchHotSearches(limit = 10): Promise<string[]> {
  const q = new URLSearchParams({ limit: String(limit), source: "kuwo" });
  const resp = await authedGet(`/api/v1/search/hot?${q}`, "application/json");
  const data = await unwrap(resp);
  if (!Array.isArray(data)) return [];
  return data.map((it: any) => String(it?.keyword ?? "")).filter((k) => k.length > 0);
}

// ---- 收藏 ----

export type FavoriteRecord = { source: string; songId: string };

/** 收藏库列表（按收藏时间降序）。 */
export async function fetchFavorites(): Promise<FavoriteRecord[]> {
  const resp = await authedGet("/api/v1/favorites", "application/json");
  const data = await unwrap(resp);
  if (!Array.isArray(data)) return [];
  return data
    .filter((it: any) => it && it.source != null && it.songId != null)
    .map((it: any) => ({ source: String(it.source), songId: String(it.songId) }));
}

/** 收藏歌曲。该接口没有请求体，不能设 Content-Type。 */
export async function addFavorite(source: string, songId: string): Promise<void> {
  const resp = await authedRequest(
    `/api/v1/favorites/${encodeURIComponent(source)}/${encodeURIComponent(songId)}`,
    { method: "POST" },
  );
  await unwrap(resp);
}

/** 取消收藏：只要求 2xx 成功，不读 data.removed。 */
export async function removeFavorite(source: string, songId: string): Promise<void> {
  const resp = await authedRequest(
    `/api/v1/favorites/${encodeURIComponent(source)}/${encodeURIComponent(songId)}`,
    { method: "DELETE" },
  );
  await unwrap(resp);
}

/** 收藏唯一键：source + 远端身份（id>0 用 id，否则用 mid）。 */
export function favoriteKeyOf(song: Song): string {
  return `${song.source}:${song.id > 0 ? song.id : song.mid ?? ""}`;
}

/** 列表渲染键：与 SongList 现有 key 口径一致。 */
export function songKeyOf(song: Song): string {
  return `${song.source}:${song.id}:${song.mid ?? ""}`;
}

// ---- 分享与歌曲信息 ----

/** 创建歌曲分享：remoteId 或 mid 至少其一，返回 {token,url}。 */
export async function createSongShare(song: Song): Promise<{ token: string; url: string }> {
  const body: { source: string; remoteId?: string; mid?: string; type?: number } = {
    source: song.source || "kuwo",
  };
  if (song.id > 0) body.remoteId = String(song.id);
  if (song.mid) body.mid = song.mid;
  if (song.type != null) body.type = song.type;
  if (!body.remoteId && !body.mid) throw new Error("该歌曲缺少可分享的远端身份");
  const resp = await authedRequest("/api/v1/shares/songs", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  const data = await unwrap(resp);
  if (!data?.token || !data?.url) throw new Error("分享失败，请稍后重试");
  return { token: String(data.token), url: String(data.url) };
}

/** durationSeconds（秒）转 "mm:ss"；无有效值返回 undefined。 */
function formatSeconds(sec: unknown): string | undefined {
  const n = Number(sec);
  if (!Number.isFinite(n) || n <= 0) return undefined;
  const m = Math.floor(n / 60);
  const s = Math.floor(n % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}

/**
 * 批量拉取歌曲元信息（batch-info 封装）：ids/mids 空数组就不传对应参数，
 * 两者合计不得超过 60，超出由调用方分批。
 */
export async function fetchSongInfos(source: string, ids: string[], mids: string[]): Promise<Song[]> {
  if (ids.length === 0 && mids.length === 0) return [];
  const q = new URLSearchParams({ source: source || "kuwo" });
  if (ids.length > 0) q.set("ids", ids.join(","));
  if (mids.length > 0) q.set("mids", mids.join(","));
  const resp = await authedGet(`/api/v1/songs/batch-info?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.songs) ? data.songs : [];
  return list.map((it) => {
    // songId 与请求侧口径一致：纯数字是远端 id，否则本身就是 mid（酷我部分歌只有 mid）
    const raw = String(it?.songId ?? "");
    const numeric = /^\d+$/.test(raw);
    return {
      id: numeric ? Number(raw) : 0,
      title: it?.title || "未知歌曲",
      artist: it?.artist || "未知歌手",
      source: source || "kuwo",
      mid: it?.mid || (!numeric && raw ? raw : undefined),
      album: it?.album || undefined,
      coverUrl: it?.coverUrl || undefined,
      vip: !!it?.vip,
      duration: formatSeconds(it?.durationSeconds),
    };
  });
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
  // 浏览器侧是 "Failed to fetch"；tauri-plugin-http（reqwest）是
  // "error sending request for url (...)"，内层可能带 refused / reset / 超时 / DNS 文案。
  if (/Failed to fetch|NetworkError|load failed|error sending request|connection refused|connection reset|timed out|name resolution|lookup address/i.test(m))
    return "网络连接失败，请检查网络后重试";
  return m || "操作失败，请稍后重试";
}
