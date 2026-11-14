import { Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { ApiErrors } from "../common/api.exception";
import { MusicSourceAccountRepository } from "./music-source-account.repository";
import type { MusicSource, MusicSourceClient, SongKey } from "./music-source.client";

/** v3 搜索结果里的一首歌。字段名以实际响应为准，文档里的 albumImage 实际是 cover。 */
export type UpstreamSong = {
  songID: number;
  songMID?: string;
  title?: string;
  singer?: string;
  album?: string;
  /**
   * 上游的歌手 / 专辑 ID。**仅波点的歌曲条目自带**（腾讯 / 网易恒缺席），是歌曲行
   * 「查看歌手 / 查看专辑」跳详情页的钥匙。缺席 = 上游没给，适配器不得伪造 ——
   * 下游契约是「正数或整个键缺席，绝不发 0 / null」。
   */
  artistId?: number;
  albumId?: number;
  /** 榜单内排名（仅巅峰潮流榜下发，1 基）；非榜单场景缺席。 */
  rank?: number;
  subtitle?: string;
  time?: string;
  interval?: number;
  cover?: string;
  albumImage?: string;
  type?: number;
  pay?: string;
  /**
   * 这首歌**能不能拿到播放地址**。
   *
   * `undefined` 表示「适配器没有判断」，客户端应当按可播处理 —— 只有酷我会填它，
   * QQ 音乐与网易云一律不填。**不要把它当成 `vip` 的同义词**：`vip` 说的是
   * 「上游把它标成付费」，而这里是「实测取不到地址」。两者常常同时成立，
   * 但「已下线」的正版曲（见下）才是酷我上的主要来源。
   *
   * 酷我的判据是搜索响应自带的 `payInfo.listen_fragment === "1"`，
   * 实测与 `playbasic/music/v2/audioUrl` 取不到地址严格对应（详见 [KuwoClient.searchSongs]）。
   *
   * ⚠️ **2026-09-19 起不再据此丢歌**，而是逐首下发让客户端置灰。原因见 [SearchResult.dropped]。
   */
  playable?: boolean;
  refrainStartMs?: number;
  refrainEndMs?: number;
};

/**
 * 搜索结果。
 *
 * [dropped] 是**上游返回了、但我们不打算下发给客户端**的条数。
 *
 * ⚠️ **2026-09-19 起这个数恒为 0。** 酷我原本会按 `payInfo.listen_fragment === "1"`
 * 在搜索阶段丢掉拿不到播放地址的歌，于是「搜周杰伦」上游 30 条全部被滤、客户端拿到 0 条。
 * 现在改为**不丢**，把可播性逐首通过 [UpstreamSong.playable] 下发，由客户端置灰。
 *
 * **这么改是因为波点 App 自己不滤。** 实测同一关键词下
 * `search/comprehensive/v2/list` 的 `musicpage` 与 `search/music/list` 的 `resultList`
 * 逐条一致（周杰伦 30 条），App 把放不了的歌也照常列出来；我们滤掉之后列表整体空掉，
 * 用户看到的是「搜不到歌」，与 App 的差异全部来自这一层。
 *
 * 字段**不能删**：装机的旧客户端会读它（见 `server/wiki/03-api-contracts.md`）。
 * 保留它是为了不让旧客户端拿到 `undefined` 去做算术。
 */
export type SearchResult = {
  total: number;
  perPage: number;
  nextPage: number | null;
  list: UpstreamSong[];
  dropped: number;
};
export type UpstreamLink = { url: string; kbps: string; quality: number };

/** 一个音质档位。[size] 为 0 表示这首歌**没有**这一档，请求它必然拿不到可用地址。 */
export type QualityTier = { type: number; size: number; label: string };

/** 歌曲信息与可用音质档位。 */
export type UpstreamSongInfo = {
  songID: number;
  songMID: string;
  title: string;
  singer: string;
  album: string;
  cover: string;
  pay: string;
  interval: number;
  tiers: QualityTier[];
  refrainStartMs?: number;
  refrainEndMs?: number;
  /**
   * 音源内的歌手 / 专辑 ID（酷我的单曲信息接口有，腾讯 / 网易没有 → 缺席）。
   * 播放期回填队列歌曲用，客户端「查看歌手 / 查看专辑」跳转的钥匙。
   */
  artistId?: number;
  albumId?: number;
  /** 榜单内排名（仅巅峰潮流榜下发，1 基）；非榜单场景缺席。 */
  rank?: number;
};

/**
 * 歌词三件套。
 *
 * `lrc` 是行级时间轴的普通 LRC；`yrc` 是逐字时间轴，格式为
 * `[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`，客户端靠它做逐字高亮；
 * `trans` 是翻译，可能为空。
 */
export type RichLyric = { lrc: string; yrc: string; trans: string };

/** 官方接口根地址。搜索走音乐帝(musics.fcg)/ musicu.fcg，歌单详情与 vkey 走 musicu.fcg。 */
const U_BASE = "https://u.y.qq.com/cgi-bin/";
const C_BASE = "https://c.y.qq.com/";

/** App 端默认浏览器 UA，与 QqMusicApiClient 一致。 */
const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
/** 搜索接口用的是 App 内嵌的 QQMusic 移动端 UA。 */
const APP_USER_AGENT = "QQMusic 14090508(android 12)";
/** musicu.fcg 请求固定的 referer。 */
const REFERER = "https://y.qq.com/";
const REQUEST_TIMEOUT_MS = 15_000;
const PROBE_TIMEOUT_MS = 8_000;
/** 与 App 端 MusicAudioProbe 一致：只拉 8KiB 判断直链可播性。 */
const PROBE_RANGE = "bytes=0-8191";

/**
 * 音质降级阶梯 —— 复刻 App 端 qualityCandidates。
 *
 * 官方 vkey 接口按文件名前缀要对应档位，一次请求能把某首歌从高到底的候选全取回来；
 * 从请求档位起步逐个探测，命中谁就按谁的档位上报（再也不用旧 v3 那种逐档盲试）。
 *
 * 映射（values 0–18 是服务端统一档，右侧是对应的 QQ 文件名前缀与媒体格式）：
 * - 臻品母带 / Hi-Res（14/12/11）→ RS01 .flac（App 端自 jymaster 起都归 hires 试 RS01）
 * - SQ 无损（10）→ F000 .flac
 * - 320k HQ（8）→ M800 .mp3
 * - 128k 标准（4）→ M500 .mp3
 * - 试听 AAC（0）→ C400 .m4a
 */
const QQ_QUALITY_LADDER: { prefix: string; ext: string; type: number; label: string; kbps: string }[] = [
  { prefix: "RS01", ext: ".flac", type: 11, label: "Hi-Res", kbps: "1411" },
  { prefix: "F000", ext: ".flac", type: 10, label: "SQ 无损", kbps: "950" },
  { prefix: "M800", ext: ".mp3", type: 8, label: "320k", kbps: "320" },
  { prefix: "M500", ext: ".mp3", type: 4, label: "128k", kbps: "128" },
  { prefix: "C400", ext: ".m4a", type: 0, label: "试听", kbps: "96" },
];

/** 搜索请求固定填充的 openudid/qimei36 等匿名字段（App 端全文搜索的 comm）。 */
const SEARCH_COMM = {
  cv: 0,
  ct: 11,
  v: "14090508",
  tmeAppID: "qqmusic",
  tmeLoginType: 2,
  OpenUDID: "",
  uid: "",
  phonetype: "",
  advertisement: "",
  loginType: 2,
  subBusinessType: "",
  authst: "",
  nettype: 4,
  userStatus: 0,
  guid: "",
};

/** 搜索接口的固定 appkey（App 端 DoSearchForQQMusicMobile 参数）。 */
const SEARCH_APPKEY = "0AND0HD6FE4HY80U";

@Injectable()
export class TencentClient implements MusicSourceClient {
  readonly source: MusicSource = "tencent";
  readonly displayName = "QQ 音乐";
  /** 腾讯音乐允许 mid-only 身份：songID 为 0 的歌只能靠 mid 解析。 */
  readonly numericIdOnly = false;
  /** download/info 接口能给出各档位体积，供控制器判定真实可用档位。 */
  readonly supportsTierProbe = true;

  constructor(private readonly accounts: MusicSourceAccountRepository) {}

  /**
   * 歌曲搜索 —— 复刻 App 端 search()：先走全文搜索(musics.fcg)，
   * 空结果且在首页时退回智能搜索(smartbox)。
   */
  async searchSongs(keyword: string, page: number, limit: number): Promise<SearchResult> {
    const safePage = Math.max(1, Math.floor(page) || 1);
    const safeLimit = Math.max(1, Math.floor(limit) || 30);
    const offset = (safePage - 1) * safeLimit;

    let tracks = await this.fullSearch(keyword, offset, safeLimit);
    let total = tracks.length;
    if (tracks.length === 0 && offset === 0) {
      tracks = await this.smartboxSearch(keyword, safeLimit);
      total = tracks.length;
    }

    return {
      total,
      perPage: safeLimit,
      nextPage: safePage * safeLimit < total ? safePage + 1 : null,
      list: tracks.map((t) => this.toSong(t)),
      // 本音源不做可播性预筛，上游返回什么就下发什么。
      dropped: 0,
    };
  }

  /**
   * 歌曲信息与可用音质档位 —— 走 musicu.fcg 的 `get_song_detail_yqq`。
   * 它按 mid/id 返回歌手、专辑、时长、file（各档位体积），tier 表据此构建。
   */
  async requestSongInfo(key: SongKey): Promise<UpstreamSongInfo> {
    const track = await this.fetchDetail(key);
    if (!track) {
      throw ApiErrors.upstream("歌曲详情不可用");
    }
    return {
      songID: Number(track.id ?? 0),
      songMID: String(track.mid ?? ""),
      title: String(track.title ?? ""),
      singer: String(track.singer ?? ""),
      album: String(track.albumName ?? ""),
      cover: this.coverOf(track),
      pay: track.pay_play ? "付费（VIP 歌曲）" : "",
      interval: Math.round(Number(track.interval ?? 0)),
      tiers: this.tiersOf(track),
    };
  }

  /**
   * 获取播放链接 —— 复刻 App 端 resolvePlayUrl：向 vkey 接口一次性传回全部候选
   * 文件名（本站 mid 与 mediaMid 各配一档阶梯），拿到 purl 后按 sip 域名展开，
   * 依次做 8KiB 探测，命中即返回（按匹配文件名回填档位）。
   *
   * [available] 是 `/song/info` 给出的可用档位集合，传了就只试这些档，
   * 避免把请求打在必定失败的档位上。
   */
  async resolveLink(
    key: SongKey,
    quality: number,
    verify?: (url: string) => Promise<boolean>,
    available?: Set<number>,
  ): Promise<UpstreamLink> {
    const track = await this.fetchDetail(key);
    if (!track) {
      throw ApiErrors.upstream("播放地址不可用");
    }
    const cookie = await this.currentCookie();
    const uin = this.cookieUin(cookie);
    const musicKey = this.cookieMusicKey(cookie);

    const mid = String((track.mid ?? key.mid ?? "").trim());
    if (!mid) {
      throw ApiErrors.upstream("播放地址不可用");
    }

    const candidates = this.ladderFrom(quality, available);
    const guid = String(Math.floor(Math.random() * 90_000_000) + 10_000_000);
    // 候选 mid：文件 real-media（mediaMid）优先，缺了退回歌曲 mid，去重保序。
    const midSet: string[] = [];
    for (const candidate of [String(track.mediaMid ?? "").trim(), mid]) {
      if (candidate && !midSet.includes(candidate)) midSet.push(candidate);
    }
    const filenames: { filename: string; prefix: string; ext: string; type: number; kbps: string }[] = [];
    for (const m of midSet) {
      for (const cand of candidates) {
        filenames.push({ filename: `${cand.prefix}${m}${cand.ext}`, ...cand });
      }
    }
    if (filenames.length === 0) {
      throw ApiErrors.upstream("播放地址不可用");
    }

    const payload = await this.musicuRequest(
      {
        comm: this.commOf(musicKey, uin),
        req_0: {
          module: "vkey.GetVkeyServer",
          method: "CgiGetVkey",
          param: {
            guid,
            songmid: filenames.map(() => mid),
            songtype: filenames.map(() => 0),
            filename: filenames.map((f) => f.filename),
            uin,
            loginflag: 1,
            platform: "20",
          },
        },
      },
      cookie,
    );
    const info = payload?.req_0?.data ?? {};
    const midurlinfo = Array.isArray(info?.midurlinfo) ? info.midurlinfo : [];
    const sip: string[] = this.dedupe((Array.isArray(info?.sip) ? info.sip : []) as string[]);
    const hosts = sip.length > 0 ? sip : ["https://ws.stream.qqmusic.qq.com/"];

    const check = verify ?? ((url: string) => this.probePlayable(url));
    const deadline = Date.now() + 6_200;
    let message = "该歌曲暂无音源";
    for (const item of midurlinfo) {
      if (deadline - Date.now() < 300) break;
      const target = filenames.find((f) => f.filename === item?.filename);
      if (!target) continue;
      for (const host of hosts) {
        if (deadline - Date.now() < 300) break;
        const url = this.joinUrl(host, String(item?.purl ?? ""));
        if (!url || this.looksEncryptedContainer(url)) continue;
        const playable = await check(url).catch(() => false);
        if (playable) {
          return { url, kbps: target.kbps, quality: target.type };
        }
        message = "该歌曲暂无音源";
      }
    }
    if (musicKey && this.feeGreaterThanZero(track)) {
      message = "该歌曲为付费歌曲，请登录 QQ 音乐会员账号";
    } else if (!musicKey && this.feeGreaterThanZero(track)) {
      message = "该歌曲为付费歌曲";
    }
    throw ApiErrors.upstream(message);
  }

  /**
   * 歌词 —— 复刻 App 端 lyric()：musicu.fcg 的 GetPlayLyricInfo 拿 lrc/qrc/trans，
   * qrc 即逐字（作 yrc 下发）；行级 lrc 为空时回退旧版 fcg_query_lyric_new，
   * 先试 nobase64=1（明文），再试 nobase64=0（Base64），都按需 HTML 实体解码。
   */
  async requestLyric(key: SongKey): Promise<RichLyric> {
    this.requireSongIdOrMid(key);
    const cookie = await this.currentCookie();
    const uin = this.cookieUin(cookie);

    // 先用单曲详情把 mid 与数字 ID 备齐（歌词接口两者都能做查询键）。
    const detail = await this.fetchDetail(key).catch(() => undefined);
    const mid = String((detail?.mid ?? key.mid ?? "").trim());
    const songId = Number(detail?.id ?? key.id ?? 0);

    let lrc = "";
    let yrc = "";
    let trans = "";
    if (mid || (Number.isInteger(songId) && songId > 0)) {
      const lyricPayload = await this.musicuRequest(
        {
          comm: { cv: 0, ct: 24 },
          lyric: {
            module: "music.musichallSong.PlayLyricInfo",
            method: "GetPlayLyricInfo",
            param: { songMID: mid, songID: songId },
          },
        },
        cookie,
      );
      const data = lyricPayload?.lyric?.data ?? {};
      lrc = this.decodePossiblyBase64(data?.lyric);
      yrc = this.decodePossiblyBase64(data?.qrc);
      trans = this.decodePossiblyBase64(data?.trans);
    }

    if (!lrc && mid) {
      // 旧歌词接口：musicu 不保证给行级 LRC，回退 fcgi 传统接口补一次。
      let payload = await this.getJson(
        `${C_BASE}lyric/fcgi-bin/fcg_query_lyric_new.fcg`,
        this.legacyLyricParams(mid, uin, true),
        cookie,
      );
      lrc = this.decodePossiblyBase64(payload?.lyric);
      if (!trans) trans = this.decodePossiblyBase64(payload?.trans ?? payload?.tlyric);
      if (!lrc) {
        payload = await this.getJson(
          `${C_BASE}lyric/fcgi-bin/fcg_query_lyric_new.fcg`,
          this.legacyLyricParams(mid, uin, false),
          cookie,
        );
        lrc = this.decodePossiblyBase64(payload?.lyric);
      }
    }

    if (!lrc && !yrc && !trans) {
      throw ApiErrors.upstream("没有歌词");
    }
    return { lrc, yrc: yrc || lrc, trans };
  }

  // —— 内部实现 ——

  /** 全文搜索（musics.fcg DoSearchForQQMusicMobile）。 */
  private async fullSearch(keyword: string, offset: number, limit: number): Promise<any[]> {
    const query: Record<string, unknown> = {
      module: "music.search.SearchCgiService",
      method: "DoSearchForQQMusicMobile",
      param: {
        searchid: `${Date.now()}${Math.floor(Math.random() * 900_000) + 100_000}`,
        search_type: 0,
        query: keyword,
        page_num: Math.floor(offset / limit) + 1,
        num_per_page: limit,
        highlight: 0,
        nqc_flag: 0,
        multi_zhinda: 0,
        cat: 2,
        grp: 1,
        sin: offset,
        sem: 0,
      },
    };
    const body = JSON.stringify({ comm: SEARCH_COMM, req: query });
    const payload = await this.postJson(
      `${U_BASE}musics.fcg?sign=${this.qqSearchSign(body)}`,
      body,
      { "Content-Type": "application/json", "User-Agent": APP_USER_AGENT },
    );
    const data = payload?.req?.data ?? {};
    const list = data?.body?.song ?? data?.body?.item_song ?? data?.song?.list ?? [];
    return Array.isArray(list) ? list : [];
  }

  /** 智能搜索兜底（c.y.qq.com/splcloud）。仅首页且全文搜空时用。 */
  private async smartboxSearch(keyword: string, pageSize: number): Promise<any[]> {
    const payload = await this.getJson(
      `${C_BASE}splcloud/fcgi-bin/smartbox_new.fcg`,
      {
        format: "json",
        key: keyword,
        g_tk: "5381",
        loginUin: "0",
        hostUin: "0",
        inCharset: "utf8",
        outCharset: "utf-8",
        notice: "0",
        platform: "yqq.json",
        needNewCode: "0",
      },
      "",
    );
    const list = payload?.data?.song?.itemlist ?? [];
    const items = Array.isArray(list) ? list : [];
    const take = Math.min(items.length, Math.max(1, Math.min(pageSize, 10)));
    return items.slice(0, take);
  }

  /**
   * 按请求档位生成候选文件名前缀序列。
   * [available] 提供时就只保留其中真实存在的档。
   */
  private ladderFrom(
    quality: number,
    available?: Set<number>,
  ): { prefix: string; ext: string; type: number; label: string; kbps: string }[] {
    let start = 0;
    if (quality >= 12) start = 0; // 母带/杜比全从最高档试
    else if (quality >= 10) start = 1; // 无损
    else if (quality >= 8) start = 2; // 320k
    else if (quality >= 4) start = 3; // 128k
    else start = 4; // 试听
    const ladder = QQ_QUALITY_LADDER.slice(start);
    if (available?.size) return ladder.filter((c) => available.has(c.type));
    return ladder;
  }

  /** musicu.fcg 通用 POST（带 UID/鉴权、g_tk）。 */
  private async musicuRequest(body: unknown, cookie: string): Promise<any> {
    return this.postJson(`${U_BASE}musicu.fcg`, JSON.stringify(body), {
      "Content-Type": "application/json;charset=UTF-8",
      "User-Agent": USER_AGENT,
      Referer: REFERER,
      ...(cookie ? { Cookie: cookie } : {}),
    });
  }

  /** 通用 GET（带 Referer，可选 Cookie）。 */
  private async getJson(url: string, params: Record<string, string>, cookie: string): Promise<any> {
    const query = Object.entries(params)
      .filter(([, value]) => value !== undefined && value !== null && value !== "")
      .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
      .join("&");
    return this.requestText(`${url}?${query}`, {
      Accept: "application/json",
      "User-Agent": USER_AGENT,
      Referer: REFERER,
      ...(cookie ? { Cookie: cookie } : {}),
    });
  }

  private async postJson(url: string, body: string, headers: Record<string, string>): Promise<any> {
    const response = await fetch(url, {
      method: "POST",
      headers,
      body,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!response.ok) {
      throw ApiErrors.upstream(`上游接口错误：${response.status}`);
    }
    return this.parseJson(await response.text());
  }

  private async requestText(url: string, headers: Record<string, string>): Promise<any> {
    const response = await fetch(url, { headers, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
    if (!response.ok) {
      throw ApiErrors.upstream(`上游接口错误：${response.status}`);
    }
    return this.parseJson(await response.text());
  }

  private parseJson(text: string): any {
    let candidate = text;
    // musicu.fcg 偶发 JSONP 包裹（callback(...)），剥壳再解析。
    const jsonp = /^[^(]*\(([\s\S]*)\)\s*;?\s*$/.exec(candidate);
    if (jsonp) candidate = jsonp[1];
    try {
      return JSON.parse(candidate);
    } catch {
      throw ApiErrors.upstream(`上游接口返回异常：${text.slice(0, 100)}`);
    }
  }

  /** App 端 calcGtk：基于 skey / qm_keyst 的 GTK，登录请求携带。 */
  private calcGtk(cookie: string): string {
    const skey = this.cookieValue(cookie, "skey") ?? this.cookieValue(cookie, "p_skey");
    if (!skey) return "5381";
    let hash = 5381;
    for (let i = 0, len = skey.length; i < len; i += 1) {
      hash += (hash << 5) + skey.charCodeAt(i);
    }
    return String(hash & 0x7fffffff);
  }

  private cookieValue(cookie: string, name: string): string | undefined {
    for (const part of cookie.split(";")) {
      const [rawKey, ...rest] = part.trim().split("=");
      if (rawKey === name) return rest.join("=");
    }
    return undefined;
  }

  private cookieUin(cookie: string): string {
    const raw = this.cookieValue(cookie, "uin") ?? this.cookieValue(cookie, "wxuin") ?? "";
    const digits = raw.replace(/\D+/g, "");
    return digits || "0";
  }

  private cookieMusicKey(cookie: string): string {
    return (
      this.cookieValue(cookie, "qm_keyst") ??
      this.cookieValue(cookie, "qqmusic_key") ??
      this.cookieValue(cookie, "music_key") ??
      ""
    );
  }

  private async currentCookie(): Promise<string> {
    const credential = await this.accounts.credentialFor("tencent");
    return credential?.token ?? "";
  }

  private commOf(musicKey: string, uin: string): Record<string, unknown> {
    if (!musicKey) {
      return { cv: 0, ct: 24 };
    }
    return { uin, format: "json", ct: 19, cv: 0, authst: musicKey };
  }

  private feeGreaterThanZero(track: any): boolean {
    const pay = track?.pay ?? track?.payInfo;
    if (!pay) return false;
    return Number(pay?.pay_play ?? 0) > 0;
  }

  private dedupe(items: string[]): string[] {
    return [...new Set(items.map((s) => s.trim()).filter((s) => s.length > 0))];
  }

  /** 把 sip 域名与 purl 拼成完整直链；sip 常是 `http://host/` 或裸 `host`。 */
  private joinUrl(host: string, purl: string): string {
    if (!purl) return "";
    let base = host;
    if (!/^https?:\/\//i.test(base)) base = `https://${base}`;
    if (!base.endsWith("/")) base += "/";
    return `${base}${purl}`.replace(/^http:/, "https:");
  }

  /** 8KiB Range 探测候选直链可播性（等价 App 端 MusicAudioProbe.firstPlayable）。 */
  private async probePlayable(url: string): Promise<boolean> {
    try {
      const response = await fetch(url, {
        headers: { "user-agent": USER_AGENT, referer: REFERER, range: PROBE_RANGE },
        signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
      });
      void response.body?.cancel().catch(() => undefined);
      const contentType = response.headers.get("content-type") ?? "";
      return response.ok && !/text\/html|application\/json/i.test(contentType);
    } catch {
      return false;
    }
  }

  /** App 端 MusicStreamHeaders.looksEncryptedContainer：URL 判定加密容器（QQ 主要是 MFLAC）。 */
  private looksEncryptedContainer(url: string): boolean {
    const path = url.toLowerCase().split("?")[0];
    return path.endsWith(".mflac") || path.includes(".mflac");
  }

  private requireSongIdOrMid(key: SongKey): void {
    const mid = key.mid?.trim();
    if (!mid && (!Number.isInteger(key.id) || key.id! <= 0)) {
      throw ApiErrors.badRequest(4001, "请提供歌曲 ID 或 mid");
    }
  }

  /** 按 mid 或 id 拉单曲详情（musicu get_song_detail_yqq）。 */
  private async fetchDetail(key: SongKey): Promise<any | undefined> {
    const mid = key.mid?.trim();
    if (!mid && (!Number.isInteger(key.id) || key.id! <= 0)) {
      return undefined;
    }
    const cookie = await this.currentCookie();
    const uin = this.cookieUin(cookie);
    const musicKey = this.cookieMusicKey(cookie);
    const payload = await this.musicuRequest(
      {
        comm: this.commOf(musicKey, uin),
        songinfo: {
          module: "music.pf_song_detail_svr",
          method: "get_song_detail_yqq",
          param: mid ? { song_mid: mid } : { song_id: key.id },
        },
      },
      cookie,
    );
    return payload?.songinfo?.data?.track_info;
  }

  /** 把搜索 / 详情条目统一成 v3 的 UpstreamSong 形状。 */
  private toSong(track: any): UpstreamSong {
    const singers = Array.isArray(track?.singer)
      ? track.singer
      : track?.singers
        ? track.singers
        : [];
    const singerNames = singers
      .map((s: any) => String(s?.name ?? "").trim())
      .filter((n: string) => n.length > 0);
    const mid = String((track?.mid ?? track?.songmid ?? track?.songMID ?? "").trim());
    const id = Number((String(track?.id ?? track?.songid ?? track?.songID ?? "")).replace(/\D+/g, "0") || 0);
    return {
      songID: id || 0,
      songMID: mid,
      title: String(track?.name ?? track?.title ?? ""),
      singer: singerNames.length > 0 ? singerNames.join(" / ") : "未知歌手",
      album: String(track?.album?.name ?? track?.albumName ?? ""),
      type: 0,
      pay: this.feeGreaterThanZero(track) ? "付费（VIP 歌曲）" : "",
      interval: Math.round(Number(track?.interval ?? 0)),
      cover: this.coverOf(track),
    };
  }

  private coverOf(track: any): string {
    const albumMid = String(
      track?.album?.mid ?? track?.album?.pmid ?? track?.albumMid ?? "",
    ).trim();
    if (!albumMid) return "";
    return `https://y.qq.com/music/photo_new/T002R300x300M000${albumMid}.jpg?max_age=2592000`;
  }

  private tiersOf(track: any): QualityTier[] {
    const file = track?.file ?? {};
    const has = (keys: string[]): number => {
      for (const keyName of keys) {
        const value = Number(file?.[keyName] ?? 0);
        if (value > 0) return value;
      }
      return 0;
    };
    const TYPES = {
      hires: 11,
      flac: 10,
      mp3_320: 8,
      mp3_128: 4,
      aac: 0,
    } as const;
    return [
      { type: TYPES.hires, size: has(["size_hires"]), label: "Hi-Res" },
      { type: TYPES.flac, size: has(["size_flac"]), label: "SQ 无损" },
      { type: TYPES.mp3_320, size: has(["size_320mp3", "size_320"]), label: "320k" },
      { type: TYPES.mp3_128, size: has(["size_128mp3", "size_128"]), label: "128k" },
      { type: TYPES.aac, size: has(["size_aac", "size_c4"]), label: "试听" },
    ];
  }

  /** App 端 legacyLyricParams：fcg 老歌词接口的固定参数。 */
  private legacyLyricParams(mid: string, uin: string, nobase64: boolean): Record<string, string> {
    return {
      songmid: mid,
      songtype: "0",
      format: "json",
      g_tk: "5381",
      loginUin: uin,
      hostUin: "0",
      inCharset: "utf8",
      outCharset: "utf-8",
      notice: "0",
      platform: "yqq.json",
      needNewCode: "0",
      ...(nobase64 ? { nobase64: "1" } : {}),
    };
  }

  /**
   * App 端 MusicLyricCodec.decodePossiblyBase64：先解 HTML 实体，再判断正文是否 Base64
   * （且解码后像 LRC / 含 CJK 才采信），否则原样返回。
   */
  private decodePossiblyBase64(text: unknown): string {
    let s = this.decodeHtmlEntities(String(text ?? "")).trim();
    if (!s) return "";
    const compact = s.replace(/\s+/g, "");
    if (compact.length >= 8 && /^[A-Za-z0-9+/_-]+={0,2}$/.test(compact) && !s.startsWith("[")) {
      try {
        const b64 = compact.replace(/-/g, "+").replace(/_/g, "/");
        const bytes = Buffer.from(this.padBase64(b64), "base64");
        const decoded = bytes.toString("utf8").replace(/^\ufeff/, "");
        if (decoded.startsWith("[") || decoded.startsWith("(") || this.containsCjk(decoded)) {
          s = decoded;
        }
      } catch {
        // 非法 Base64 保持原文。
      }
    }
    return this.decodeHtmlEntities(s).replace(/\r\n/g, "\n");
  }

  private padBase64(base64: string): string {
    const rem = base64.length % 4;
    return rem === 0 ? base64 : base64 + "=".repeat(4 - rem);
  }

  /** App 端 decodeHtmlEntities：把歌词里的 HTML 实体还原成字面字符。 */
  private decodeHtmlEntities(text: string): string {
    return text
      .replace(/&nbsp;/g, " ")
      .replace(/&amp;/g, "&")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&apos;/g, "'");
  }

  private containsCjk(text: string): boolean {
    for (let i = 0; i < text.length; i += 1) {
      const code = text.codePointAt(i) ?? 0;
      if (code >= 19_968 && code <= 40_869) return true;
    }
    return false;
  }

  /**
   * App 端 qqSearchSign：musics.fcg 签名，SHA-1(body) 取特定下标拼串 + 异或 + Base64 剥壳。
   * 实现为与 App 端完全一致的算法，否则搜索返回空。
   */
  private qqSearchSign(body: string): string {
    const shaHex = createHash("sha1").update(body, "utf8").digest("hex");
    const seg1 = [23, 14, 6, 36, 16, 40, 7, 19].map((i) => shaHex[i] ?? "").join("");
    const seg2 = [16, 1, 32, 12, 19, 27, 8, 5].map((i) => shaHex[i] ?? "").join("");
    const key = [89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179];
    const bytes: number[] = key.map((k, i) => k ^ Number.parseInt(shaHex.slice(i * 2, i * 2 + 2), 16));
    const b64 = Buffer.from(bytes).toString("base64").replace(/[/+=]/g, "");
    return `zzc${seg1}${b64}${seg2}`.toLowerCase();
  }
}