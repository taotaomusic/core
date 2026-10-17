import { invoke } from "@tauri-apps/api/core";
import { fetch as tauriFetch } from "@tauri-apps/plugin-http";

export const ENDPOINT = "https://music.xydaigua.cn";

// 换后端域名时必须同步改 src-tauri/capabilities/default.json 里 http:default 的
// allow 白名单——插件按 URL 前缀放行，两边不一致会静默 403（表现像网络失败）。

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

// ---- 传输加密通道（plans/009，与服务端 crypto.middleware.ts / 安卓端同契约） ----

/**
 * 分层说明：Rust 侧（crypto_bridge，taotao-crypto-core）只做密码学，HTTP 仍走
 * 上面的 httpFetch 直连后端。除「永不加密集合」外，authedRequest 的业务请求统一
 * 走 cryptoFetch：请求体在 Rust seal 成二进制帧发送（X-Taotao-Crypto 头携带
 * 会话与序号，AAD = method + pathAndQuery 含 query），响应帧 crypto_open 解开，
 * 再重建 Response 交给 unwrap 等既有解析，调用方无感。
 *
 * 错误语义（与中间件逐条对应）：
 * - 带加密头的请求，响应体（含控制器层 4xx）为整帧密文；中间件自产的
 *   400/4006、409/4091、426/4007 是明文 JSON；
 * - 409/4091 会话失效 → 丢弃本地会话重新握手后重放一次（仅一次）；
 * - 401 令牌过期 → 走既有 refreshAccess 后重放一次（仅一次）；重放必须重新
 *   seal——旧帧序号已消费，原样重放会被服务端按重放攻击拒收；
 * - 加密失败一律抛错，不回退明文（服务端对强制加密路径回 426/4007 防降级）。
 */

/** Rust crypto_seal 的返回。字段名与 Rust 侧 serde 默认一致（snake_case），两端勿单方面改名。 */
type SealedFrame = { header: string; frame_b64: string };

/**
 * 永不加密的路径前缀（与服务端 main.ts isPlaintextOnlyPath 镜像，三端同步改）：
 * crypto（引导自身）、app/admin/public/desktop/open（其它客户端与管理面）、
 * files（匿名图片）。auth 族是**精确路径**而非前缀——/auth/profile 与
 * /auth/email/bind-* 是强制加密路径，只豁免登录前拿不到 PSK 的那六条。
 */
const CRYPTO_NEVER_PREFIXES = [
  "/api/v1/crypto/",
  "/api/v1/app/",
  "/api/v1/admin/",
  "/api/v1/public/",
  "/api/v1/desktop/",
  "/api/v1/open/",
  "/api/v1/files/",
];

/** auth 族精确豁免：调用时还没有访问令牌（拿不到 PSK）或属 multipart 大缓冲。 */
const CRYPTO_NEVER_EXACT = new Set([
  "/api/v1/auth/login",
  "/api/v1/auth/register",
  "/api/v1/auth/refresh",
  "/api/v1/auth/logout",
  "/api/v1/auth/email-verification",
  "/api/v1/auth/avatar",
]);

/** 判定一条请求路径（可含 query）是否永不加密。 */
function isNeverEncryptedPath(path: string): boolean {
  const q = path.indexOf("?");
  const pathname = q >= 0 ? path.slice(0, q) : path;
  // /api/v1/search 是「精确段」：裸 NDJSON 流式契约保持明文；/search/suggestions
  // 等子路径不在其列，仍走加密。
  if (pathname === "/api/v1/search") return true;
  // 公告只豁免精确路径：服务端用的是 `===`，/announcements/xxx 子资源仍是强制加密，
  // 这里跟着收窄——客户端偏宽会走明文去撞服务端的 426。
  if (pathname === "/api/v1/announcements") return true;
  // 音频流代理：逐块 AEAD 得不偿失（core 的「明确不做的事」），与服务端排除表一致。
  // 正则与 server/src/main.ts 的排除表逐字对齐（`.*` 而非写死 `songs` 段）：
  // 三端镜像表必须同步，任一侧收窄都会让某条 /play 代理静默撞 400。
  if (/^\/api\/v1\/.*\/play$/.test(pathname)) return true;
  if (CRYPTO_NEVER_EXACT.has(pathname)) return true;
  return CRYPTO_NEVER_PREFIXES.some((p) => pathname.startsWith(p));
}

/** 是否运行在 Tauri 环境（纯浏览器 #mock dev 模式没有 __TAURI_INTERNALS__）。 */
function isTauriRuntime(): boolean {
  return "__TAURI_INTERNALS__" in window;
}

let warnedCryptoBypass = false;

/** 纯浏览器 dev 模式退回明文时提示一次，不刷屏。 */
function warnCryptoBypassOnce(): void {
  if (warnedCryptoBypass) return;
  warnedCryptoBypass = true;
  console.warn("[api] 非 Tauri 环境（浏览器 dev/#mock），传输加密不可用，全部按明文请求");
}

/** bytes → base64。btoa 只吃 latin1 串，按 32K 分块拼接避免超长参数撑爆调用栈。 */
function bytesToB64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

/** base64 → bytes（Rust 命令以 base64 字符串过桥，invoke 的 JSON 通道不走二进制）。 */
function b64ToBytes(b64: string): Uint8Array {
  const binary = atob(b64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

/**
 * 握手用设备 id：与 AppState 的播放上报共用 localStorage 键 taotao.deviceId
 * （同键同算法，保证同一台机器的播放上报与握手设备绑定是同一个 id）。
 */
function ensureCryptoDeviceId(): string {
  try {
    const existing = localStorage.getItem("taotao.deviceId");
    if (existing) return existing;
    const id = crypto.randomUUID();
    localStorage.setItem("taotao.deviceId", id);
    return id;
  } catch {
    // localStorage 不可用时退化为进程内一次性 id，仅影响设备绑定不影响可用性
    return crypto.randomUUID();
  }
}

/**
 * 加密会话引导：取 PSK（Bearer）→ Rust 建引擎 → ClientHello → 握手（公开路由）→
 * ServerHello 回填 finish。并发去重：同一时刻只跑一次引导；结束（无论成败）即
 * 清空——**成功也不缓存**，会话随时可能失效，REKEY_REQUIRED 必须真的重新握手。
 */
let cryptoBootstrap: Promise<void> | null = null;

function bootstrapCrypto(): Promise<void> {
  if (!cryptoBootstrap) {
    cryptoBootstrap = ensureCryptoSession().finally(() => {
      cryptoBootstrap = null;
    });
  }
  return cryptoBootstrap;
}

async function ensureCryptoSession(): Promise<void> {
  const deviceId = ensureCryptoDeviceId();

  // a. 取 PSK：GET /crypto/psk 需 Bearer；401 时复用既有刷新逻辑重试一次。
  const fetchPsk = async (tok: string | null): Promise<any> => {
    const resp = await httpFetch(`${ENDPOINT}/api/v1/crypto/psk`, {
      headers: { Accept: "application/json", Authorization: `Bearer ${tok ?? ""}` },
    });
    if (resp.status === 401) return null; // 交给外层走刷新重试
    return unwrap(resp);
  };
  let psk = await fetchPsk(accessToken);
  if (psk === null) {
    const t = await refreshAccess();
    if (!t) throw new SessionExpired();
    psk = await fetchPsk(t);
    if (psk === null) throw new SessionExpired();
  }
  const pskId = String(psk?.pskId ?? "");
  const pskHex = String(psk?.pskHex ?? "");
  if (!pskId || !pskHex) throw new Error("取 PSK 失败：响应缺少 pskId/pskHex");

  // b. Rust 建引擎（rekey 时整体替换，旧会话一并作废）。
  await invoke("crypto_session_new", { pskId, pskHex, deviceId });

  // c. 握手：ClientHello → POST /crypto/handshake（公开路由，无 Authorization）→ finish。
  const clientHello = await invoke<string>("crypto_client_hello", { nowMs: Date.now() });
  const handshakeResp = await httpFetch(`${ENDPOINT}/api/v1/crypto/handshake`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ clientHello, deviceId }),
  });
  const data = await unwrap(handshakeResp);
  const serverHello = String(data?.serverHello ?? "");
  if (!serverHello) throw new Error("握手失败：响应缺少 serverHello");
  await invoke("crypto_finish", { serverHelloB64: serverHello, nowMs: Date.now() });
}

/** invoke 对 Rust Err(String) 的 reject 就是那个字符串本身。 */
function isRekeyRequired(e: unknown): boolean {
  return String(e).includes("REKEY_REQUIRED");
}

/**
 * 封帧：Rust seal 出密文帧与 X-Taotao-Crypto 头。无会话/需重键（REKEY_REQUIRED）
 * 时先引导会话再 seal 一次；仍失败原样抛——加密是强制的，不回退明文。
 */
async function sealFrame(method: string, path: string, bodyText: string | null): Promise<SealedFrame> {
  const doSeal = () =>
    invoke<SealedFrame>("crypto_seal", {
      method,
      path,
      bodyB64: bytesToB64(new TextEncoder().encode(bodyText ?? "")),
      nowMs: Date.now(),
    });
  try {
    return await doSeal();
  } catch (e) {
    if (!isRekeyRequired(e)) throw e;
    await bootstrapCrypto();
    return doSeal();
  }
}

/** raw 是否为明文 JSON 信封（中间件自产错误的形状：{code: number, ...}）。 */
function isPlaintextJsonEnvelope(raw: Uint8Array): boolean {
  try {
    const obj = JSON.parse(new TextDecoder().decode(raw));
    return !!obj && typeof obj === "object" && typeof obj.code === "number";
  } catch {
    return false;
  }
}

/**
 * 解密响应并重建 Response（状态码/JSON content-type 保留），让 unwrap 等
 * 既有解析无感。open 失败按明文容错（例如服务端加密链路降级时收到明文）。
 */
async function decryptResponse(resp: Response, method: string, path: string): Promise<Response> {
  const status = resp.status;
  const raw = new Uint8Array(await resp.arrayBuffer());
  // 204/205/304 不允许 Response 带体，构造器会抛 RangeError
  const canHaveBody = status !== 204 && status !== 205 && status !== 304;

  // 中间件自产错误（400/4006、409/4091、426/4007）不加密，是明文 JSON；
  // 其余状态码（含控制器层 4xx）为整帧密文。
  let plain: Uint8Array | null = null;
  if (status === 400 || status === 409 || status === 426) {
    if (isPlaintextJsonEnvelope(raw)) plain = raw;
  }
  if (plain === null && raw.length > 0) {
    try {
      const plainB64 = await invoke<string>("crypto_open", {
        method,
        path,
        frameB64: bytesToB64(raw),
        nowMs: Date.now(),
      });
      plain = b64ToBytes(plainB64);
    } catch {
      plain = raw; // 容错：响应未被加密时按明文交给上层解析
    }
  }

  const headers: Record<string, string> = {};
  const contentType = resp.headers.get("content-type");
  if (contentType) {
    // 密文帧沿用了下游 JSON 的 content-type（服务端只改写 content-length），
    // 明文重建时把它归一回 application/json。
    headers["content-type"] = /json/i.test(contentType) ? "application/json" : contentType;
  }
  // DOM 的 BodyInit 类型不收 Uint8Array<ArrayBufferLike>，运行时（reqwest/webview）
  // 接受字节体——此处按 BodyInit 断言，字节内容不受影响。
  const body = canHaveBody && plain && plain.length > 0 ? (plain as unknown as BodyInit) : null;
  return new Response(body, { status, headers });
}

/**
 * 加密请求通道：seal → 发帧 → 解帧。401 与 409 各自最多重放一次（每次重放都
 * 重新 seal），重放后的 401/409 原样返回给上层按错误处理。
 */
async function cryptoFetch(
  path: string,
  method: string,
  baseHeaders: Record<string, string>,
  bodyText: string | null,
): Promise<Response> {
  let refreshed = false; // 401 令牌刷新重放，仅一次
  let rekeyed = false; // 409 会话失效重握手重放，仅一次
  for (;;) {
    let sealed: SealedFrame;
    try {
      sealed = await sealFrame(method, path, bodyText);
    } catch (e) {
      // SessionExpired 必须原样上抛：上层大量使用 instanceof 判断决定登出
      if (e instanceof SessionExpired) throw e;
      throw new Error(`加密请求失败：${e instanceof Error ? e.message : String(e)}`);
    }

    // GET/HEAD 不能带 body（webview 的 Request 构造器对 GET+body 直接抛
    // TypeError）；服务端对空请求体有显式容忍（frame.length === 0 按空明文
    // 处理），请求身份由 X-Taotao-Crypto 头承载。
    const attachBody = method !== "GET" && method !== "HEAD";
    const resp = await httpFetch(`${ENDPOINT}${path}`, {
      method,
      headers: {
        ...baseHeaders,
        Authorization: `Bearer ${accessToken ?? ""}`,
        "X-Taotao-Crypto": sealed.header,
        "Content-Type": "application/octet-stream",
      },
      body: attachBody ? (b64ToBytes(sealed.frame_b64) as unknown as BodyInit) : undefined,
    });

    if (resp.status === 409 && !rekeyed) {
      // 加密会话失效（明文 JSON {code:4091}）：丢弃引擎重新握手后重放一次。
      rekeyed = true;
      await invoke("crypto_reset");
      await bootstrapCrypto();
      continue;
    }
    if (resp.status === 401 && !refreshed) {
      refreshed = true;
      const t = await refreshAccess();
      if (!t) throw new SessionExpired();
      continue;
    }
    return decryptResponse(resp, method, path);
  }
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
 * 带令牌的通用请求：命中「永不加密集合」或运行在纯浏览器 dev 模式时，维持
 * 原有明文逻辑（401 自动刷新并重放一次，刷新失败或重放仍 401 抛 SessionExpired）；
 * 其余走 cryptoFetch 加密通道，401 语义与明文分支一致。其余方法/头/体由调用方
 * 通过 init 自定义；加密通道的 body 仅支持字符串或无体（现有调用点全部如此）。
 */
export async function authedRequest(path: string, init?: RequestInit): Promise<Response> {
  const baseHeaders = headerRecordOf(init?.headers);

  const neverEncrypted = isNeverEncryptedPath(path);
  if (neverEncrypted || !isTauriRuntime()) {
    if (!neverEncrypted) warnCryptoBypassOnce();
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

  let bodyText: string | null = null;
  if (init?.body != null) {
    if (typeof init.body !== "string") {
      throw new Error(`加密通道不支持 ${typeof init.body} 类型的请求体（仅支持字符串或无体）`);
    }
    bodyText = init.body;
  }
  return cryptoFetch(path, (init?.method ?? "GET").toUpperCase(), baseHeaders, bodyText);
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
  /** 歌曲高潮区间（毫秒，酷我源才有值；其他音源键缺席，缺失即键不存在，0 视为无效） */
  refrainStartMs?: number;
  refrainEndMs?: number;
  /** 关联歌手/专辑的远端 id（/search 的酷我源才下发；其他音源或非搜索来源键缺席，非正数视为无效） */
  artistId?: number;
  albumId?: number;
  /** 命中歌词摘要（/api/v1/search/lyrics 才下发）：一小段含命中词的歌词文本，搜索歌词标签展示用 */
  lyricSnippet?: string;
};

/** 搜索结果里的歌手条目：仅酷我源下发，其他音源没有 artist 行；pic 可能为空。 */
export type SearchArtist = {
  source: string;
  id: number;
  name: string;
  pic?: string;
  songCount?: number;
  albumCount?: number;
};

/** 搜索结果里的专辑条目：仅酷我源下发；pic/showtime 可能缺席或为空串。 */
export type SearchAlbum = {
  source: string;
  id: number;
  name: string;
  artist?: string;
  artistId?: number;
  pic?: string;
  songCount?: number;
  showtime?: string;
};

export type SearchResult = {
  songs: Song[];
  /** 歌手/专辑区块：仅酷我源才有，其他情况为空数组（界面据此整块不渲染） */
  artists: SearchArtist[];
  albums: SearchAlbum[];
  total: number;
  hasMore: boolean;
};

/** 搜索 / 歌手歌曲 / 专辑歌曲共用的歌曲行归一：id 或 mid 至少其一有效才算有效行。 */
function songOfRemoteData(d: any): Song | null {
  if (!d || !(Number(d.id) > 0 || d.mid)) return null;
  // 高潮区间只认正毫秒：0 与键缺失等价，统一归一成 undefined
  const refrainStartMs = Number(d.refrainStartMs);
  const refrainEndMs = Number(d.refrainEndMs);
  return {
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
    refrainStartMs: refrainStartMs > 0 ? refrainStartMs : undefined,
    refrainEndMs: refrainEndMs > 0 ? refrainEndMs : undefined,
    // 歌词摘要：仅 /search/lyrics 下发，其余来源键缺席即 undefined
    lyricSnippet: typeof d.lyricSnippet === "string" && d.lyricSnippet ? d.lyricSnippet : undefined,
  };
}

/** 歌手行归一（搜索歌手区块 / 相似歌手共用）：id 非正视为脏数据丢弃，pic 空串归一成 undefined。 */
function artistOfRemoteData(d: any): SearchArtist | null {
  if (!d || !(Number(d.id) > 0)) return null;
  return {
    source: d.source || "kuwo",
    id: Number(d.id),
    name: d.name || "未知歌手",
    pic: d.pic || undefined,
    songCount: Number(d.songCount) || undefined,
    albumCount: Number(d.albumCount) || undefined,
  };
}

/** 专辑行归一（搜索专辑区块 / 歌手专辑列表共用）：只认正 id；showtime/artist 缺席时键归 undefined。 */
function albumOfRemoteData(d: any): SearchAlbum | null {
  if (!d || !(Number(d.id) > 0)) return null;
  return {
    source: d.source || "kuwo",
    id: Number(d.id),
    name: d.name || "未知专辑",
    artist: d.artist || undefined,
    artistId: Number(d.artistId) || undefined,
    pic: d.pic || undefined,
    songCount: Number(d.songCount) || undefined,
    showtime: d.showtime || undefined,
  };
}

/** 搜索：/api/v1/search 返回裸 NDJSON（{type:"song",data} / {type:"artist"|"album",data} /
 *  {type:"end",meta}）；artist/album 行只由酷我源下发，且出现在所有 song 行之前。
 *  song 行可携带 artistId/albumId（同样仅酷我源下发），供「⋯」菜单跳歌手/专辑详情。 */
export async function searchSongs(keyword: string, page = 1): Promise<SearchResult> {
  const q = new URLSearchParams({ keyword, page: String(page), num: "60", quality: "4", source: "kuwo" });
  const resp = await authedGet(`/api/v1/search?${q}`, "application/x-ndjson, application/json");
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const text = await resp.text();
  const songs: Song[] = [];
  const artists: SearchArtist[] = [];
  const albums: SearchAlbum[] = [];
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
        // 高潮区间只认正毫秒：0 与键缺失等价，统一归一成 undefined
        const refrainStartMs = Number(d.refrainStartMs);
        const refrainEndMs = Number(d.refrainEndMs);
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
          refrainStartMs: refrainStartMs > 0 ? refrainStartMs : undefined,
          refrainEndMs: refrainEndMs > 0 ? refrainEndMs : undefined,
          // 关联歌手/专辑 id：非正数与键缺席等价，统一归一成 undefined
          artistId: Number(d.artistId) > 0 ? Number(d.artistId) : undefined,
          albumId: Number(d.albumId) > 0 ? Number(d.albumId) : undefined,
          // 歌词摘要透传：/search 本身不下发（键缺席归 undefined），/search/lyrics 复用同一行结构
          lyricSnippet: typeof d.lyricSnippet === "string" && d.lyricSnippet ? d.lyricSnippet : undefined,
        });
      }
    } else if (rec?.type === "artist" && rec.data) {
      // 歌手行：仅酷我源下发；id 非正视为脏数据丢弃，pic 空串归一成 undefined
      const d = rec.data;
      if (Number(d.id) > 0) {
        artists.push({
          source: d.source || "kuwo",
          id: Number(d.id),
          name: d.name || "未知歌手",
          pic: d.pic || undefined,
          songCount: Number(d.songCount) || undefined,
          albumCount: Number(d.albumCount) || undefined,
        });
      }
    } else if (rec?.type === "album" && rec.data) {
      // 专辑行：同歌手行，只认正 id；showtime/artist 缺席时键归 undefined
      const d = rec.data;
      if (Number(d.id) > 0) {
        albums.push({
          source: d.source || "kuwo",
          id: Number(d.id),
          name: d.name || "未知专辑",
          artist: d.artist || undefined,
          artistId: Number(d.artistId) || undefined,
          pic: d.pic || undefined,
          songCount: Number(d.songCount) || undefined,
          showtime: d.showtime || undefined,
        });
      }
    } else if (rec?.type === "end" && rec.meta) {
      metaTotal = Number(rec.meta.total);
      metaHasMore = rec.meta.hasMore === true;
    }
  }
  return {
    songs,
    artists,
    albums,
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

/** 搜索歌手分页结果：信封 data.artists + data.meta 归一后的形状。 */
export type PagedSearchArtists = { artists: SearchArtist[]; page: number; total: number; hasMore: boolean };

/** 搜索专辑分页结果：信封 data.albums + data.meta 归一后的形状。 */
export type PagedSearchAlbums = { albums: SearchAlbum[]; page: number; total: number; hasMore: boolean };

/** 搜索歌手分页（/api/v1/search/artists，仅酷我源提供；行结构与 /search 歌手区块同构，复用同一归一）。 */
export async function searchArtistsPaged(keyword: string, page: number, num = 30): Promise<PagedSearchArtists> {
  const q = new URLSearchParams({ keyword, page: String(page), num: String(num), source: "kuwo" });
  const resp = await authedGet(`/api/v1/search/artists?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.artists) ? data.artists : [];
  const artists = list.map(artistOfRemoteData).filter((a): a is SearchArtist => a !== null);
  return { ...pagedMetaOf(data?.meta, page), artists };
}

/** 搜索专辑分页（/api/v1/search/albums，仅酷我源提供；行结构与 /search 专辑区块同构，复用同一归一）。 */
export async function searchAlbumsPaged(keyword: string, page: number, num = 30): Promise<PagedSearchAlbums> {
  const q = new URLSearchParams({ keyword, page: String(page), num: String(num), source: "kuwo" });
  const resp = await authedGet(`/api/v1/search/albums?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.albums) ? data.albums : [];
  const albums = list.map(albumOfRemoteData).filter((a): a is SearchAlbum => a !== null);
  return { ...pagedMetaOf(data?.meta, page), albums };
}

/** 搜索结果里的歌单条目：仅酷我源下发；pic/creator 可能缺席或为空串。 */
export type SearchPlaylist = {
  source: string;
  id: number;
  name: string;
  pic?: string;
  creator?: string;
  trackCount?: number;
  playCount?: number;
};

/** 搜索结果里的视频（MV）条目：仅酷我源下发；id 是关联歌曲的身份，点视频 = 播该歌的 MV。 */
export type SearchVideo = {
  source: string;
  id: number;
  name: string;
  artist?: string;
  artistId?: number;
  album?: string;
  albumId?: number;
  cover?: string;
  durationSeconds?: number;
  vid?: string;
  mvName?: string;
  mvCover?: string;
  mvDurationSeconds?: number;
  playable?: boolean;
};

/** 歌单行归一：只认正 id；pic/creator 空串归一成 undefined，计数非正视为缺席。 */
function playlistOfRemoteData(d: any): SearchPlaylist | null {
  if (!d || !(Number(d.id) > 0)) return null;
  return {
    source: d.source || "kuwo",
    id: Number(d.id),
    name: d.name || "未知歌单",
    pic: d.pic || undefined,
    creator: d.creator || undefined,
    trackCount: Number(d.trackCount) > 0 ? Number(d.trackCount) : undefined,
    playCount: Number(d.playCount) > 0 ? Number(d.playCount) : undefined,
  };
}

/** 视频行归一：只认正 id；封面优先 mvCover，其次 cover；计数/秒数非正视为缺席。 */
function videoOfRemoteData(d: any): SearchVideo | null {
  if (!d || !(Number(d.id) > 0)) return null;
  const durationSeconds = Number(d.durationSeconds);
  const mvDurationSeconds = Number(d.mvDurationSeconds);
  return {
    source: d.source || "kuwo",
    id: Number(d.id),
    name: d.name || "未知视频",
    artist: d.artist || undefined,
    artistId: Number(d.artistId) > 0 ? Number(d.artistId) : undefined,
    album: d.album || undefined,
    albumId: Number(d.albumId) > 0 ? Number(d.albumId) : undefined,
    cover: d.mvCover || d.cover || undefined,
    durationSeconds: durationSeconds > 0 ? durationSeconds : undefined,
    vid: d.vid || undefined,
    mvName: d.mvName || undefined,
    mvCover: d.mvCover || undefined,
    mvDurationSeconds: mvDurationSeconds > 0 ? mvDurationSeconds : undefined,
    playable: !!d.playable,
  };
}

/** 搜索歌单分页结果：信封 data.playlists + data.meta 归一后的形状。 */
export type PagedSearchPlaylists = { playlists: SearchPlaylist[]; page: number; total: number; hasMore: boolean };

/** 搜索视频分页结果：信封 data.videos + data.meta 归一后的形状。 */
export type PagedSearchVideos = { videos: SearchVideo[]; page: number; total: number; hasMore: boolean };

/** 搜索歌词分页结果：信封 data.songs + data.meta 归一后的形状（行结构与 /search 歌曲同构 + lyricSnippet）。 */
export type PagedSearchLyricSongs = { songs: Song[]; page: number; total: number; hasMore: boolean };

/** 搜索歌单分页（/api/v1/search/playlists，仅酷我源提供）。 */
export async function searchPlaylistsPaged(keyword: string, page: number, num = 30): Promise<PagedSearchPlaylists> {
  const q = new URLSearchParams({ keyword, page: String(page), num: String(num), source: "kuwo" });
  const resp = await authedGet(`/api/v1/search/playlists?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.playlists) ? data.playlists : [];
  const playlists = list.map(playlistOfRemoteData).filter((p): p is SearchPlaylist => p !== null);
  return { ...pagedMetaOf(data?.meta, page), playlists };
}

/** 搜索视频分页（/api/v1/search/videos，仅酷我源提供；quality 语义与 /search 相同的数字档位）。 */
export async function searchVideosPaged(keyword: string, page: number, num = 30, quality = 4): Promise<PagedSearchVideos> {
  const q = new URLSearchParams({ keyword, page: String(page), num: String(num), quality: String(quality), source: "kuwo" });
  const resp = await authedGet(`/api/v1/search/videos?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.videos) ? data.videos : [];
  const videos = list.map(videoOfRemoteData).filter((v): v is SearchVideo => v !== null);
  return { ...pagedMetaOf(data?.meta, page), videos };
}

/** 搜索歌词分页（/api/v1/search/lyrics，仅酷我源提供；行结构与 /search 歌曲同构，复用同一归一并透传 lyricSnippet）。 */
export async function searchLyricsPaged(keyword: string, page: number, num = 30, quality = 4): Promise<PagedSearchLyricSongs> {
  const q = new URLSearchParams({ keyword, page: String(page), num: String(num), quality: String(quality), source: "kuwo" });
  const resp = await authedGet(`/api/v1/search/lyrics?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.songs) ? data.songs : [];
  const songs = list.map(songOfRemoteData).filter((s): s is Song => s !== null);
  return { ...pagedMetaOf(data?.meta, page), songs };
}

// ---- 歌手与专辑详情 ----

/** 歌手详情：/api/v1/artists/:id 信封 data.artist；aliasName/pic/desc 可能缺席或为空。 */
export type ArtistDetail = {
  source: string;
  id: number;
  name: string;
  aliasName?: string;
  pic?: string;
  desc?: string;
  fansCount?: number;
  musicCount?: number;
  albumCount?: number;
};

/** 专辑详情：/api/v1/albums/:id 信封 data.album；desc 为长简介，可缺席。 */
export type AlbumDetail = {
  source: string;
  id: number;
  name: string;
  pic?: string;
  artist?: string;
  artistId?: number;
  songCount?: number;
  showtime?: string;
  desc?: string;
};

/** 歌手/专辑歌曲分页结果：信封 data.songs + data.meta 归一后的形状。 */
export type PagedSongs = { songs: Song[]; page: number; total: number; hasMore: boolean };

/** 歌手专辑分页结果：信封 data.albums + data.meta 归一后的形状。 */
export type PagedAlbums = { albums: SearchAlbum[]; page: number; total: number; hasMore: boolean };

/** 分页信封 meta 归一：page 非正退回请求页码，total 非法按 0，hasMore 只认 true。 */
function pagedMetaOf(meta: any, requestPage: number): { page: number; total: number; hasMore: boolean } {
  const page = Number(meta?.page);
  const total = Number(meta?.total);
  return {
    page: Number.isFinite(page) && page > 0 ? page : requestPage,
    total: Number.isFinite(total) && total > 0 ? total : 0,
    hasMore: meta?.hasMore === true,
  };
}

/** 歌手详情（pic/desc/aliasName 可能空，空串归一成 undefined）。 */
export async function fetchArtistDetail(id: number, source = "kuwo"): Promise<ArtistDetail> {
  const q = new URLSearchParams({ source });
  const resp = await authedGet(`/api/v1/artists/${id}?${q}`, "application/json");
  const data = await unwrap(resp);
  const d = data?.artist;
  if (!d || !(Number(d.id) > 0)) throw new Error("歌手不存在或已下线");
  return {
    source: d.source || source,
    id: Number(d.id),
    name: d.name || "未知歌手",
    aliasName: d.aliasName || undefined,
    pic: d.pic || undefined,
    desc: d.desc || undefined,
    fansCount: Number(d.fansCount) || undefined,
    musicCount: Number(d.musicCount) || undefined,
    albumCount: Number(d.albumCount) || undefined,
  };
}

/** 歌手歌曲分页（quality 语义与 /search 相同的数字档位；num 服务端缺省 30、上限 60）。 */
export async function fetchArtistSongs(id: number, page: number, source = "kuwo"): Promise<PagedSongs> {
  const q = new URLSearchParams({ page: String(page), num: "30", quality: "4", source });
  const resp = await authedGet(`/api/v1/artists/${id}/songs?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.songs) ? data.songs : [];
  const songs = list.map(songOfRemoteData).filter((s): s is Song => s !== null);
  return { ...pagedMetaOf(data?.meta, page), songs };
}

/** 歌手专辑分页（num 服务端缺省 20、上限 60），专辑行与搜索专辑区块同构。 */
export async function fetchArtistAlbums(id: number, page: number, source = "kuwo"): Promise<PagedAlbums> {
  const q = new URLSearchParams({ page: String(page), num: "20", source });
  const resp = await authedGet(`/api/v1/artists/${id}/albums?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.albums) ? data.albums : [];
  const albums = list.map(albumOfRemoteData).filter((a): a is SearchAlbum => a !== null);
  return { ...pagedMetaOf(data?.meta, page), albums };
}

/** 相似歌手列表（一次性返回，无分页），歌手行与搜索歌手区块同构。 */
export async function fetchSimilarArtists(id: number, source = "kuwo"): Promise<SearchArtist[]> {
  const q = new URLSearchParams({ source });
  const resp = await authedGet(`/api/v1/artists/${id}/similar?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.artists) ? data.artists : [];
  return list.map(artistOfRemoteData).filter((a): a is SearchArtist => a !== null);
}

/** 专辑详情（desc 为长简介，可缺席）。 */
export async function fetchAlbumDetail(id: number, source = "kuwo"): Promise<AlbumDetail> {
  const q = new URLSearchParams({ source });
  const resp = await authedGet(`/api/v1/albums/${id}?${q}`, "application/json");
  const data = await unwrap(resp);
  const d = data?.album;
  if (!d || !(Number(d.id) > 0)) throw new Error("专辑不存在或已下线");
  return {
    source: d.source || source,
    id: Number(d.id),
    name: d.name || "未知专辑",
    pic: d.pic || undefined,
    artist: d.artist || undefined,
    artistId: Number(d.artistId) || undefined,
    songCount: Number(d.songCount) || undefined,
    showtime: d.showtime || undefined,
    desc: d.desc || undefined,
  };
}

/** 专辑歌曲分页（quality/num 语义与歌手歌曲一致）。 */
export async function fetchAlbumSongs(id: number, page: number, source = "kuwo"): Promise<PagedSongs> {
  const q = new URLSearchParams({ page: String(page), num: "30", quality: "4", source });
  const resp = await authedGet(`/api/v1/albums/${id}/songs?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.songs) ? data.songs : [];
  const songs = list.map(songOfRemoteData).filter((s): s is Song => s !== null);
  return { ...pagedMetaOf(data?.meta, page), songs };
}

// ---- 排行榜 ----

/** 排行榜简述（/api/v1/rankings 的 bangs 条目）：pic/pubStr 上游可能缺失或空串，归一成 undefined。 */
export type RankingBrief = {
  source: string;
  id: number;
  name: string;
  pic?: string;
  /** 更新时间展示串（如「10-05更新」），语义完整可直接展示 */
  pubStr?: string;
  /** 前 5 首歌曲预览，与 /search 的歌曲行同构（目录首屏预览数据，卡片视图不展示） */
  preview: Song[];
};

/** 排行榜目录的一个模块分组（置顶榜/热力榜/全球榜/特色榜…）。 */
export type RankingGroup = {
  moduleName: string;
  bangs: RankingBrief[];
};

/** 榜单详情头（/api/v1/rankings/:id/songs 的 data.ranking）；pub 为更新日期（如 2026-10-05）。 */
export type RankingInfo = {
  source: string;
  id: number;
  name: string;
  pic?: string;
  pub?: string;
  /** 上游写死的展示值（实测恒 100），不是真实条数，禁止当「共 N 首」使用 */
  total: number;
};

/**
 * 榜单歌曲分页结果：信封 data.songs + data.meta 归一后的形状。
 * 注意 total（真实条数，主流榜单恒 100，可当「共 N 首」展示）与 ranking.total
 * （上游写死的展示值）不是同一个数，别混用。
 */
export type RankingSongsResult = {
  ranking: RankingInfo;
  songs: Song[];
  /** 本次返回的页码（1 基） */
  page: number;
  /** 榜单真实条数（取自 meta.total） */
  total: number;
  /** 是否还有下一页（服务端口径：page*num < total） */
  hasMore: boolean;
};

/** 榜单简述行归一：只认正 id；pic/pubStr 空串归一成 undefined，preview 复用歌曲行归一。 */
function rankingBriefOf(d: any): RankingBrief | null {
  if (!d || !(Number(d.id) > 0)) return null;
  const list: any[] = Array.isArray(d.preview) ? d.preview : [];
  return {
    source: d.source || "kuwo",
    id: Number(d.id),
    name: d.name || "未知榜单",
    pic: d.pic || undefined,
    pubStr: d.pubStr || undefined,
    preview: list.map(songOfRemoteData).filter((s): s is Song => s !== null),
  };
}

/** 排行榜目录（/api/v1/rankings，仅酷我源提供）：模块分组 + 榜单简述 + 前 5 首预览。 */
export async function fetchRankings(source = "kuwo"): Promise<RankingGroup[]> {
  const q = new URLSearchParams({ source });
  const resp = await authedGet(`/api/v1/rankings?${q}`, "application/json");
  const data = await unwrap(resp);
  const list: any[] = Array.isArray(data?.groups) ? data.groups : [];
  return list
    .map((g) => {
      const bangs: any[] = Array.isArray(g?.bangs) ? g.bangs : [];
      return {
        moduleName: String(g?.moduleName ?? ""),
        bangs: bangs.map(rankingBriefOf).filter((b): b is RankingBrief => b !== null),
      };
    })
    .filter((g) => g.bangs.length > 0);
}

/**
 * 榜单歌曲分页（/api/v1/rankings/:id/songs）：page 1 基、num 服务端缺省 30 上限 100，
 * quality 语义与 /search 相同。条目顺序即排名顺序（第 1 名在最前），
 * 全局名次 = (page-1)*num + 页内下标 + 1。翻页前进不交叠（仍按身份 key 去重兜底）。
 */
export async function fetchRankingSongs(
  id: number,
  quality = 4,
  page = 1,
  num = 30,
  source = "kuwo",
): Promise<RankingSongsResult> {
  const q = new URLSearchParams({
    page: String(page),
    num: String(num),
    quality: String(quality),
    source,
  });
  const resp = await authedGet(`/api/v1/rankings/${id}/songs?${q}`, "application/json");
  const data = await unwrap(resp);
  const d = data?.ranking;
  if (!d || !(Number(d.id) > 0)) throw new Error("榜单不存在或已下线");
  const ranking: RankingInfo = {
    source: d.source || source,
    id: Number(d.id),
    name: d.name || "未知榜单",
    pic: d.pic || undefined,
    pub: d.pub || undefined,
    total: Number(d.total) || 0,
  };
  const list: any[] = Array.isArray(data?.songs) ? data.songs : [];
  const songs = list.map(songOfRemoteData).filter((s): s is Song => s !== null);
  return { ranking, songs, ...pagedMetaOf(data?.meta, page) };
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
    // 高潮区间只认正毫秒：0 与键缺失等价（收藏/最近播放补全元数据时顺带入队）
    const refrainStartMs = Number(it?.refrainStartMs);
    const refrainEndMs = Number(it?.refrainEndMs);
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
      refrainStartMs: refrainStartMs > 0 ? refrainStartMs : undefined,
      refrainEndMs: refrainEndMs > 0 ? refrainEndMs : undefined,
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

/** 单曲 info 的轻量结果：只关心高潮区间两个字段（毫秒，酷我源才有值）。 */
export type SongRefrainInfo = { refrainStartMs?: number; refrainEndMs?: number };

/**
 * 拉取歌曲高潮区间（复用 /api/v1/songs/:id/info，data 顶层与 qualities 并列带这两个字段）。
 * 供播放时兜底补拉：歌单/最近播放/收藏入队的歌来自服务端快照，快照不带区间。
 */
export async function fetchSongRefrain(song: Song): Promise<SongRefrainInfo> {
  const q = new URLSearchParams({ source: song.source || "kuwo" });
  if (song.mid) q.set("mid", song.mid);
  const resp = await authedGet(`/api/v1/songs/${song.id}/info?${q}`, "application/json");
  const data = await unwrap(resp);
  // 只认正毫秒：0 与键缺失等价，统一归一成 undefined
  const start = Number(data?.refrainStartMs);
  const end = Number(data?.refrainEndMs);
  return {
    refrainStartMs: start > 0 ? start : undefined,
    refrainEndMs: end > 0 ? end : undefined,
  };
}

/** 高潮区间在进度条上的归一结果：start/end 为 0..1 轨道比例，startMs/endMs 供 mm:ss 文案。 */
export type SongRefrainRange = {
  start: number;
  end: number;
  startMs: number;
  endMs: number;
};

/**
 * 把歌曲的高潮区间按当前时长归一成进度条比例：时长有效（>0）、两值齐全且
 * end > start 才算有效；比例收敛到 0..1（终点超出时长夹到 1，负起点夹到 0）。
 * 无效返回 null，界面据此不渲染标记与小字。
 */
export function refrainRangeOf(song: Song | null, durationSec: number): SongRefrainRange | null {
  if (!song || !(durationSec > 0)) return null;
  const startMs = song.refrainStartMs;
  const endMs = song.refrainEndMs;
  if (startMs == null || endMs == null || endMs <= startMs) return null;
  const total = durationSec * 1000;
  const start = Math.min(1, Math.max(0, startMs / total));
  const end = Math.min(1, Math.max(0, endMs / total));
  // 收敛后退化成一个点（如区间整体越出时长）同样视为无效
  return end > start ? { start, end, startMs, endMs } : null;
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
