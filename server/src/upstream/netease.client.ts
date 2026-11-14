import { Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { MusicSourceAccountRepository } from "./music-source-account.repository";
import type { MusicSource, MusicSourceClient, SongKey } from "./music-source.client";
import type { QualityTier, RichLyric, SearchResult, UpstreamLink, UpstreamSong, UpstreamSongInfo } from "./tencent.client";

/** 官方接口根地址与请求头，对齐 App 端 NetEaseOfficialHttp。 */
const NETEASE_BASE = "https://music.163.com";
const NETEASE_REFERER = "https://music.163.com/";
const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
const REQUEST_TIMEOUT_MS = 15_000;
const PROBE_TIMEOUT_MS = 8_000;
/** 与 App 端 MusicAudioProbe 一致：只拉 8KiB 判断直链可播性。 */
const PROBE_RANGE = "bytes=0-8191";

/** App 端 qualityChain 的降级梯子（高 → 低）。 */
const QUALITY_CHAIN = ["jymaster", "hires", "lossless", "exhigh", "standard"] as const;
type NeteaseLevel = (typeof QUALITY_CHAIN)[number];

/** App 端 brForLevel：level → enhance/player/url 的 br 上限（单位 bps）。 */
const BR_FOR_LEVEL: Record<NeteaseLevel, string> = {
  jymaster: "1999000",
  hires: "1999000",
  lossless: "1411000",
  exhigh: "999000",
  standard: "128000",
};

/** level → 服务端统一音质档位，回填 UpstreamLink.quality。 */
const TYPE_FOR_LEVEL: Record<NeteaseLevel, number> = {
  jymaster: 18,
  hires: 12,
  lossless: 11,
  exhigh: 10,
  standard: 4,
};

/**
 * 官方 detail 接口不提供各档位体积，size 只能以 0（未知）占位；
 * 本音源 supportsTierProbe=false，控制器不会用这张表判定可用性。
 */
const NETEASE_TIERS: QualityTier[] = [
  { type: 18, size: 0, label: "超清母带" },
  { type: 12, size: 0, label: "Hi-Res" },
  { type: 11, size: 0, label: "无损" },
  { type: 10, size: 0, label: "极高" },
  { type: 4, size: 0, label: "标准" },
];

/**
 * 网易云音乐官方接口客户端，对齐 Android 端 NetEaseApiClient 的明文 GET 协议：
 *
 * - 读取类操作（搜索/详情/直链/歌词）全部走 music.163.com 的 /api/* 明文接口，
 *   不需要 WEAPI 加密；写操作目前不在服务端音源范围内。
 * - 登录态来自管理端保存的 Cookie（扫码/粘贴导入）。没有启用的账号时按匿名访问，
 *   官方接口匿名也能搜索/取详情，只是 VIP 歌曲拿不到完整音源。
 * - 播放地址按 App 端 resolvePlayUrl 的语义按 level 梯子逐档降级，
 *   每个候选先剔除加密容器（.mflac/.mgg/.mmp4），再做 8KiB 连通探测；
 *   全部失败时兜底返回第一个可播的试听片段。
 */
@Injectable()
export class NeteaseClient implements MusicSourceClient {
  readonly source: MusicSource = "netease";
  readonly displayName = "网易云";
  readonly numericIdOnly = true;
  /** 官方接口不提供各档位可用性/体积探测，链接解析靠 level 链式兜底。 */
  readonly supportsTierProbe = false;

  constructor(private readonly accounts: MusicSourceAccountRepository) {}

  /** 搜索只返回元数据；播放地址按需在 [resolveLink] 中获取。 */
  async searchSongs(keyword: string, page: number, limit: number): Promise<SearchResult> {
    const safePage = Math.max(1, Math.floor(page) || 1);
    const safeLimit = Math.max(1, Math.floor(limit) || 30);
    const cookie = await this.currentCookie();
    const payload = await this.officialGet(
      "api/cloudsearch/pc",
      { s: keyword, type: "1", limit: String(safeLimit), offset: String((safePage - 1) * safeLimit) },
      cookie,
    );
    // cloudsearch 固定是 result.songs；兼容历史接口的 data.result.songs / songs 形状。
    const result = payload?.result ?? payload?.data?.result ?? {};
    const songs = Array.isArray(result?.songs) ? result.songs : [];
    const total = Number(result?.songCount ?? songs.length);
    return {
      total,
      perPage: safeLimit,
      nextPage: safePage * safeLimit < total ? safePage + 1 : null,
      list: songs.map((song: any) => this.toSong(song)),
      // 本音源不做可播性预筛，上游返回什么就下发什么。
      dropped: 0,
    };
  }

  /** 官方 detail 接口取回歌曲资料；档位体积官方不提供，以 0 占位（见 NETEASE_TIERS）。 */
  async requestSongInfo(key: SongKey): Promise<UpstreamSongInfo> {
    const id = this.requireSongId(key);
    const cookie = await this.currentCookie();
    const payload = await this.officialGet("api/song/detail", { ids: `[${id}]` }, cookie);
    const song = Array.isArray(payload?.songs) ? payload.songs[0] : undefined;
    if (!song) {
      throw ApiErrors.upstream("歌曲详情不可用");
    }
    return {
      songID: Number(song?.id ?? id),
      songMID: "",
      title: String(song?.name ?? ""),
      singer: this.artistNames(song),
      album: String(song?.al?.name ?? song?.album?.name ?? ""),
      cover: this.httpsUrl(song?.al?.picUrl ?? song?.album?.picUrl),
      pay: this.payOfFee(song?.fee),
      interval: this.intervalOf(song),
      tiers: NETEASE_TIERS,
    };
  }

  /**
   * App 端 resolvePlayUrl 语义：按 level 梯子逐档请求
   * /api/song/enhance/player/url（ids=[id]&br=brForLevel(level)）。
   * App 端 v1 失败会回退 /song/url，但官方通道下两者是同一个 GET，重试无意义，
   * 网络层错误直接上抛（与旧客户端的非 2xx 即 502 行为一致）。
   */
  async resolveLink(
    key: SongKey,
    quality: number,
    verify?: (url: string) => Promise<boolean>,
    // 本音源不做档位探测，available 恒为 undefined，参数只为对齐接口签名。
    _available?: Set<number>,
  ): Promise<UpstreamLink> {
    const id = this.requireSongId(key);
    const cookie = await this.currentCookie();
    const check = verify ?? ((url: string) => this.probePlayable(url));
    let message = "该歌曲暂无音源（可能需会员或无版权）";
    let trial: UpstreamLink | null = null;
    for (const level of this.qualityChain(this.levelForQuality(quality))) {
      const payload = await this.officialGet(
        "api/song/enhance/player/url",
        { ids: `[${id}]`, br: BR_FOR_LEVEL[level] },
        cookie,
      );
      const entry = Array.isArray(payload?.data) ? payload.data[0] : undefined;
      if (!entry) {
        continue;
      }
      const url = this.httpsUrl(entry.url);
      const freeTrial = entry.freeTrialInfo != null;
      if (!url) {
        if (freeTrial) {
          message = "试听受限，请开通网易云会员后重试";
        } else if (Number(entry.code) === -110) {
          message = "该歌曲可能需会员或无版权";
        }
        continue;
      }
      if (this.looksEncryptedContainer(url)) {
        message = "音源格式不可播，尝试下一音质";
        continue;
      }
      if (!(await check(url))) {
        message = "音源探测失败，尝试下一音质";
        continue;
      }
      const link: UpstreamLink = { url, kbps: this.kbpsOf(entry), quality: TYPE_FOR_LEVEL[level] };
      if (!freeTrial) {
        return link;
      }
      // 记住第一个可播的试听片段，全梯子走完后兜底返回它。
      trial ??= link;
    }
    if (trial) {
      return trial;
    }
    throw ApiErrors.upstream(message);
  }

  /** /api/song/lyric：lrc 为主、tlyric 作翻译；两者都空按无歌词上抛。 */
  async requestLyric(key: SongKey): Promise<RichLyric> {
    const id = this.requireSongId(key);
    const cookie = await this.currentCookie();
    const payload = await this.officialGet(
      "api/song/lyric",
      { id: String(id), lv: "-1", kv: "-1", tv: "-1" },
      cookie,
    );
    const lrc = String(payload?.lrc?.lyric ?? "").trim();
    const trans = String(payload?.tlyric?.lyric ?? "").trim();
    if (!lrc && !trans) {
      throw ApiErrors.upstream("没有歌词");
    }
    return { lrc, yrc: "", trans };
  }

  /** 每次调用现取凭据：网易凭据来自扫码/粘贴的 Cookie，没有缓存一说。 */
  private async currentCookie(): Promise<string> {
    const credential = await this.accounts.credentialFor("netease");
    return credential?.token ?? "";
  }

  /** App 端 getOfficial 的明文 GET 通道：固定浏览器头 + 可选 Cookie + 宽容 envelope code。 */
  private async officialGet(path: string, params: Record<string, string>, cookie: string): Promise<any> {
    const query = Object.entries(params)
      .filter(([, value]) => value !== undefined && value !== null && value !== "")
      .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
      .join("&");
    const response = await fetch(`${NETEASE_BASE}/${path}?${query}`, {
      headers: {
        accept: "application/json",
        "user-agent": USER_AGENT,
        referer: NETEASE_REFERER,
        origin: "https://music.163.com",
        ...(cookie ? { cookie } : {}),
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!response.ok) {
      throw ApiErrors.upstream(`上游接口错误：${response.status}`);
    }
    const text = await response.text();
    let payload: any;
    try {
      payload = JSON.parse(text);
    } catch {
      throw ApiErrors.upstream(`上游接口返回异常：${text.slice(0, 100)}`);
    }
    const code = Number(payload?.code);
    // 800/801/802/803 是「需要登录」族，与 200 一样放行，由调用方按空结果处理。
    if (![200, 800, 801, 802, 803].includes(code)) {
      throw ApiErrors.upstream(String(payload?.message ?? payload?.msg ?? `code=${code}`));
    }
    return payload;
  }

  /** App 端 qualityChain：未知 level 从 hires 起步，逐级降到底。 */
  private qualityChain(level: string): NeteaseLevel[] {
    const normalized = String(level ?? "").toLowerCase();
    const index = (QUALITY_CHAIN as readonly string[]).indexOf(normalized);
    return QUALITY_CHAIN.slice(index >= 0 ? index : 1);
  }

  /** 服务端统一 0–18 档位 → 网易云 level 起始档；14(MASTER) 与 18 同为「最高」。 */
  private levelForQuality(quality: number): NeteaseLevel {
    const value = Number.isFinite(quality) ? quality : 10;
    if (value >= 14) return "jymaster";
    if (value >= 12) return "hires";
    if (value >= 11) return "lossless";
    if (value >= 6) return "exhigh";
    return "standard";
  }

  /** 8KiB Range 探测候选直链可播性（等价 App 端 MusicAudioProbe.firstPlayable）。 */
  private async probePlayable(url: string): Promise<boolean> {
    try {
      const response = await fetch(url, {
        headers: { "user-agent": USER_AGENT, referer: NETEASE_REFERER, range: PROBE_RANGE },
        signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
      });
      // 只看状态码与内容类型，不消费正文；主动断开避免占用连接。
      void response.body?.cancel().catch(() => undefined);
      const contentType = response.headers.get("content-type") ?? "";
      return response.ok && !/text\/html|application\/json/i.test(contentType);
    } catch {
      return false;
    }
  }

  /** App 端 MusicStreamHeaders.looksEncryptedContainer：纯 URL 判定加密容器。 */
  private looksEncryptedContainer(url: string): boolean {
    const path = url.toLowerCase().split("?")[0];
    return (
      path.includes(".mflac") ||
      path.includes(".mgg") ||
      path.includes(".mmp4") ||
      path.endsWith(".mflac") ||
      path.endsWith(".mgg")
    );
  }

  /** App 端 parseSongs 的字段映射：ar/artists、al/album 双形状兼容。 */
  private toSong(song: any): UpstreamSong {
    return {
      songID: Number(song?.id ?? 0),
      title: String(song?.name ?? ""),
      singer: this.artistNames(song),
      album: String(song?.al?.name ?? song?.album?.name ?? ""),
      cover: this.httpsUrl(song?.al?.picUrl ?? song?.album?.picUrl),
      pay: this.payOfFee(song?.fee),
      interval: this.intervalOf(song),
    };
  }

  /** ar[] 优先、artists[] 兜底；空名过滤后用 " / " 连接。 */
  private artistNames(song: any): string {
    const artists = Array.isArray(song?.ar) ? song.ar : Array.isArray(song?.artists) ? song.artists : [];
    const names = artists
      .map((artist: any) => String(artist?.name ?? "").trim())
      .filter((name: string) => name.length > 0);
    return names.length > 0 ? names.join(" / ") : "未知歌手";
  }

  /** fee → SongMapper 的 vip 判定文案（includes("付费")）；可免费收听的档位返回空串。 */
  private payOfFee(fee: unknown): string {
    const value = Number(fee ?? 0);
    if (value === 1) return "付费（VIP 歌曲）";
    if (value === 4) return "付费（数字专辑）";
    return "";
  }

  /** dt/duration 毫秒 → 整秒；缺失或异常时返回 0（界面显示「网络歌曲」）。 */
  private intervalOf(song: any): number {
    const durationMs = Number(song?.dt ?? song?.duration ?? 0);
    return Number.isFinite(durationMs) && durationMs > 0 ? Math.round(durationMs / 1000) : 0;
  }

  /** enhance/player/url 的 br 是 bps，转成 kbps 文案；缺失时留空。 */
  private kbpsOf(entry: any): string {
    const br = Number(entry?.br ?? 0);
    return Number.isFinite(br) && br > 0 ? String(Math.round(br / 1000)) : "";
  }

  /** http: 一律换成 https:，避免 https 页面直链播放被混合内容拦掉。 */
  private httpsUrl(url: unknown): string {
    const value = String(url ?? "");
    return value ? value.replace(/^http:/i, "https:") : "";
  }

  /** 统一的 ID 守卫：网易只有数字 ID，缺 ID 属于调用方错误（4001）。 */
  private requireSongId(key: SongKey): number {
    const id = Number(key?.id ?? 0);
    if (!Number.isInteger(id) || id <= 0) {
      throw ApiErrors.badRequest(4001, "网易云歌曲必须提供正整数 ID");
    }
    return id;
  }
}
