import { fetch as tauriFetch } from "@tauri-apps/plugin-http";

export const ENDPOINT = "https://music.xydaigua.cn";

/**
 * 把服务端下发的资源地址归一成绝对地址：相对路径（如 /api/v1/...）在 webview 里
 * 会解析到应用自身 origin 导致裂图，必须补上 API 域名；已是绝对地址原样返回。
 */
export function absoluteUrl(url: string | undefined | null): string {
  const u = (url ?? "").trim();
  if (!u) return "";
  if (/^(https?:)?\/\//i.test(u) || /^(data|blob):/i.test(u)) return u;
  if (u.startsWith("/")) return `${ENDPOINT}${u}`;
  return u;
}

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
  /** 服务端侧的原样 songId（歌单/最近播放返回），排序与删除按它回传，避免 id/mid 推断错位 */
  remoteId?: string;
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

/** 直链解析结果：url 可直接投喂 <audio>；quality 为服务端实际下发的档位。 */
export type ResolvedLink = { url: string; quality: number; fallback: boolean };

/** 解析上游直链：/api/v1/songs/:id/link。quality 缺省 4（标准），服务端会沿阶梯降级。 */
export async function resolveLink(song: Song, quality?: number): Promise<ResolvedLink> {
  const q = new URLSearchParams({ quality: String(quality ?? 4), source: song.source || "kuwo" });
  if (song.mid) q.set("mid", song.mid);
  if (song.type != null) q.set("type", String(song.type));
  const resp = await authedGet(`/api/v1/songs/${song.id}/link?${q}`, "application/json");
  const data = await unwrap(resp);
  if (!data?.url) throw new Error("无法获取播放地址");
  return {
    url: data.url as string,
    quality: Number(data.quality ?? quality ?? 4),
    fallback: data.fallback === true,
  };
}

// ---- 歌词与音质档位 ----

/** 拉取歌词：format=json 信封 data {lrc, yrc}。上游没有歌词（502）返回空串，界面显示「暂无歌词」。 */
export async function fetchLyrics(song: Song): Promise<{ lrc: string; yrc: string }> {
  const q = new URLSearchParams({ format: "json", source: song.source || "kuwo" });
  if (song.mid) q.set("mid", song.mid);
  const resp = await authedGet(`/api/v1/songs/${song.id}/lyrics?${q}`, "application/json");
  if (resp.status === 502) return { lrc: "", yrc: "" };
  const data = await unwrap(resp);
  return { lrc: String(data?.lrc ?? ""), yrc: String(data?.yrc ?? "") };
}

/** 单曲音质档位：size 为字节数（服务端已滤掉 size=0 的不存在档位）。 */
export type QualityTier = { quality: number; label: string; size: number };

/** 拉取当前歌曲的可用音质档位：/api/v1/songs/:id/info 信封 data.qualities。 */
export async function fetchQualityTiers(song: Song): Promise<QualityTier[]> {
  const q = new URLSearchParams({ source: song.source || "kuwo" });
  if (song.mid) q.set("mid", song.mid);
  const resp = await authedGet(`/api/v1/songs/${song.id}/info?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.qualities) ? data.qualities : [];
  return list
    .map((t) => ({ quality: Number(t?.quality), label: String(t?.label ?? ""), size: Number(t?.size) }))
    .filter((t) => Number.isFinite(t.quality));
}

/** 任意音质档位的中文名（与 shared AudioQuality.kt 的 labelOfQuality 同口径），档位标签兜底用。 */
export function labelOfQuality(value: number): string {
  if (value === 0) return "试听";
  if (value === 1 || value === 2) return "有损";
  if (value >= 3 && value <= 7) return "标准";
  if (value === 8 || value === 9) return "HQ 高音质";
  if (value === 10) return "SQ 无损";
  if (value === 11) return "Hi-Res";
  if (value === 12) return "杜比全景声";
  if (value === 13) return "臻品全景声";
  if (value === 14) return "臻品母带";
  if (value === 15) return "AI 伴奏消音";
  if (value === 16) return "AI 人声消音";
  if (value === 17) return "AI 钢琴";
  if (value === 18) return "NAC";
  return `音质 ${value}`;
}

// ---- 歌单 ----

export type PlaylistRecord = {
  id: number;
  name: string;
  description: string;
  coverUrl: string;
  songCount: number;
  revision: number;
  createdAt: string;
  updatedAt: string;
};

export type PlaylistDetail = PlaylistRecord & { songs: Song[] };

/** 服务端侧的稳定键：优先后端原样 songId，其次按 id/mid 推断。 */
export function songIdStringOf(song: Song): string {
  if (song.remoteId) return song.remoteId;
  if (song.id > 0) return String(song.id);
  return song.mid ?? "";
}

/** 歌单接口的歌曲输入体：songId=0 且有 mid 时服务端以 mid 为键。 */
function playlistSongInput(song: Song) {
  const body: Record<string, unknown> = {
    source: song.source || "kuwo",
    songId: songIdStringOf(song),
  };
  if (song.mid) body.mid = song.mid;
  if (song.title) body.title = song.title;
  if (song.artist) body.artist = song.artist;
  if (song.album) body.album = song.album;
  if (song.coverUrl) body.coverUrl = song.coverUrl;
  if (song.duration) body.duration = song.duration;
  if (song.type != null) body.type = song.type;
  return body;
}

/** 歌单详情里的曲目记录转 Song：保留原样 songId 进 remoteId。 */
function songOfPlaylistEntry(it: any): Song {
  const raw = String(it?.songId ?? "");
  const numeric = /^\d+$/.test(raw);
  return {
    id: numeric ? Number(raw) : 0,
    title: it?.title || "未知歌曲",
    artist: it?.artist || "未知歌手",
    source: it?.source || "kuwo",
    mid: it?.mid || (!numeric && raw ? raw : undefined),
    type: it?.type ?? undefined,
    coverUrl: it?.coverUrl || undefined,
    album: it?.album || undefined,
    duration: it?.duration || undefined,
    remoteId: raw || undefined,
  };
}

/** 歌单列表（按更新时间降序）。 */
export async function fetchPlaylists(): Promise<PlaylistRecord[]> {
  const resp = await authedGet("/api/v1/playlists", "application/json");
  const data = await unwrap(resp);
  return Array.isArray(data) ? data : [];
}

/** 歌单详情（songs 按 position 升序）。 */
export async function fetchPlaylistDetail(id: number): Promise<PlaylistDetail> {
  const resp = await authedGet(`/api/v1/playlists/${id}`, "application/json");
  const data = await unwrap(resp);
  return {
    id: Number(data?.id ?? id),
    name: String(data?.name ?? "未命名歌单"),
    description: String(data?.description ?? ""),
    coverUrl: String(data?.coverUrl ?? ""),
    songCount: Number(data?.songCount ?? 0),
    revision: Number(data?.revision ?? 0),
    createdAt: String(data?.createdAt ?? ""),
    updatedAt: String(data?.updatedAt ?? ""),
    songs: Array.isArray(data?.songs) ? data.songs.map(songOfPlaylistEntry) : [],
  };
}

/** 新建歌单，返回创建后的记录。 */
export async function createPlaylist(name: string, description = ""): Promise<PlaylistRecord> {
  const resp = await authedRequest("/api/v1/playlists", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ name, description }),
  });
  const data = await unwrap(resp);
  if (!data?.id) throw new Error("创建歌单失败");
  return {
    id: Number(data.id),
    name: String(data.name ?? name),
    description: String(data.description ?? ""),
    coverUrl: String(data.coverUrl ?? ""),
    songCount: Number(data.songCount ?? 0),
    revision: Number(data.revision ?? 0),
    createdAt: String(data.createdAt ?? ""),
    updatedAt: String(data.updatedAt ?? ""),
  };
}

/** 改名/改简介（至少一项）。 */
export async function updatePlaylist(
  id: number,
  patch: { name?: string; description?: string },
): Promise<void> {
  const resp = await authedRequest(`/api/v1/playlists/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(patch),
  });
  await unwrap(resp);
}

/** 删除歌单（204，无响应体）。 */
export async function deletePlaylist(id: number): Promise<void> {
  const resp = await authedRequest(`/api/v1/playlists/${id}`, { method: "DELETE" });
  await unwrap(resp);
}

/** 加歌到歌单末尾（幂等，重复添加只更新快照）。 */
export async function addToPlaylist(playlistId: number, song: Song): Promise<void> {
  const resp = await authedRequest(`/api/v1/playlists/${playlistId}/songs`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(playlistSongInput(song)),
  });
  await unwrap(resp);
}

/** 从歌单移除一首。 */
export async function removeFromPlaylist(playlistId: number, song: Song): Promise<void> {
  const resp = await authedRequest(
    `/api/v1/playlists/${playlistId}/songs/${encodeURIComponent(song.source)}/${encodeURIComponent(songIdStringOf(song))}`,
    { method: "DELETE", headers: { Accept: "application/json" } },
  );
  await unwrap(resp);
}

/** 歌单曲目整体重排：键集合必须与服务端当前内容完全一致，不一致服务端 400/4004。 */
export async function reorderPlaylist(playlistId: number, songs: Song[]): Promise<void> {
  const resp = await authedRequest(`/api/v1/playlists/${playlistId}/songs/order`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({
      songs: songs.map((s) => ({ source: s.source, songId: songIdStringOf(s) })),
    }),
  });
  await unwrap(resp);
}

// ---- 播放上报与最近播放 ----

/** 一次播放会话的上报体（服务端按 sessionId 幂等 upsert）。 */
export type PlaybackSessionReport = {
  sessionId: string;
  deviceId: string;
  source: string;
  songId: string;
  startedAt: number;
  lastPlayedAt: number;
  listenedMs: number;
  completed?: boolean;
  durationSeconds?: number | null;
};

/** 上报播放会话：听满 3 秒才会计入最近播放（服务端阈值），失败静默。 */
export async function reportPlaybackSession(report: PlaybackSessionReport): Promise<void> {
  const resp = await authedRequest("/api/v1/playback/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(report),
  });
  await unwrap(resp);
}

/** 最近播放条目：仅身份与统计，不带歌曲元数据。 */
export type RecentPlayEntry = {
  source: string;
  songId: string;
  firstPlayedAt: number;
  lastPlayedAt: number;
  playCount: number;
  completedCount: number;
  totalListenedMs: number;
};

/** 最近播放（按最后播放时间降序，同歌聚合为一行，上限 500）。 */
export async function fetchRecentPlays(limit = 500): Promise<RecentPlayEntry[]> {
  const resp = await authedGet(`/api/v1/playback/recent?limit=${limit}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data) ? data : Array.isArray(data?.entries) ? data.entries : [];
  return list.map((it) => ({
    source: String(it?.source ?? "kuwo"),
    songId: String(it?.songId ?? ""),
    firstPlayedAt: Number(it?.firstPlayedAt ?? 0),
    lastPlayedAt: Number(it?.lastPlayedAt ?? 0),
    playCount: Number(it?.playCount ?? 0),
    completedCount: Number(it?.completedCount ?? 0),
    totalListenedMs: Number(it?.totalListenedMs ?? 0),
  }));
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
