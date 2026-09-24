import { Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import type { MusicSource, MusicSourceClient, SongKey } from "./music-source.client";

/** v3 搜索结果里的一首歌。字段名以实际响应为准，文档里的 albumImage 实际是 cover。 */
export type UpstreamSong = {
  songID: number;
  songMID?: string;
  title?: string;
  singer?: string;
  album?: string;
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
};

/**
 * 歌词三件套。
 *
 * `lrc` 是行级时间轴的普通 LRC；`yrc` 是逐字时间轴，格式为
 * `[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`，客户端靠它做逐字高亮；
 * `trans` 是翻译，可能为空。
 */
export type RichLyric = { lrc: string; yrc: string; trans: string };

/**
 * 音质降级阶梯。
 *
 * v3 的播放链接接口拿不到所选音质时**不会自动降级**，所以必须自己逐级往下试。
 *
 * 这是**兜底路径**：优先用 `/song/info` 的 `qualityInfo` 直接挑一个真实存在的档位
 * （实测 `size == 0` 与「拿不到可用地址」严格对应），只有 info 拿不到时才走阶梯。
 *
 * 另外纠正一处旧注释里的误判：付费歌曲并非一律在 quality=14 失败 ——
 * 实测付费歌的 14 档同样能拿到地址，报 code=110000 的真正原因是该档 size 为 0。
 */
const QUALITY_LADDER = [14, 11, 10, 8, 4, 0];

/** 最多尝试几档。每档都是一次网络请求，档数太多会把搜索拖慢。 */
const MAX_QUALITY_ATTEMPTS = 4;

/**
 * v3 播放链接接口的风控码：cookie 异常导致整首歌都拿不到地址。
 *
 * 这不是「某一档不存在」（那是 size 为 0、报 110000 的情形），而是上游账号整体被风控，
 * 继续在 v3 上逐档重试没有意义 —— 同一首歌走旧版 v2 的 geturl 往往仍能给出可播直链，
 * 所以命中这个码时优先回退 v2，v2 也拿不到才继续沿阶梯降级。
 */
const CODE_LINK_RISK_CONTROL = 110001;

@Injectable()
export class TencentClient implements MusicSourceClient {
  readonly source: MusicSource = "tencent";
  readonly displayName = "QQ 音乐";
  /** 腾讯音乐允许 mid-only 身份：songID 为 0 的歌只能靠 mid 解析。 */
  readonly numericIdOnly = false;
  /** 单曲信息接口的 qualityInfo 能直接给出真实可用档位。 */
  readonly supportsTierProbe = true;

  constructor(private readonly config: AppConfigService) {}

  /** 歌曲搜索（v3）。分页参数在 v3 里叫 limit，不叫 num。 */
  async searchSongs(keyword: string, page: number, limit: number): Promise<SearchResult> {
    const query = `?keyword=${encodeURIComponent(keyword)}&page=${page}&limit=${limit}`;
    const data = this.unwrap(await this.requestJson(`${this.config.upstreamV3BaseUrl}/search/song${query}`), "搜索失败");
    return {
      total: Number(data.meta?.total ?? 0),
      perPage: Number(data.meta?.perPage ?? limit),
      nextPage: data.meta?.nextPage ?? null,
      list: Array.isArray(data.list) ? data.list : [],
      // 本音源不做可播性预筛，上游返回什么就下发什么。
      dropped: 0,
    };
  }

  /**
   * 歌曲信息与可用音质档位（v3 `/song/info`）。
   *
   * 这是唯一能按 id / mid 单曲查询的接口 —— 搜索只能按关键词。它比搜索结果多出来的
   * 只有 `qualityInfo`，但那正是关键：它列出这首歌**真实存在**的档位与各档字节数。
   */
  async requestSongInfo(key: SongKey): Promise<UpstreamSongInfo> {
    const data = this.unwrap(
      await this.requestJson(`${this.config.upstreamV3BaseUrl}/song/info?${this.identityOf(key)}`),
      "歌曲信息不可用",
    );
    const tiers: QualityTier[] = (Array.isArray(data.qualityInfo) ? data.qualityInfo : [])
      .map((item: any) => ({
        type: Number(item?.type),
        size: Number(item?.size ?? 0),
        label: String(item?.quality ?? ""),
      }))
      .filter((tier: QualityTier) => Number.isInteger(tier.type));
    return {
      songID: Number(data.songID ?? 0),
      songMID: String(data.songMID ?? ""),
      title: String(data.title ?? ""),
      singer: String(data.singer ?? ""),
      album: String(data.album ?? ""),
      cover: String(data.cover ?? ""),
      pay: String(data.pay ?? ""),
      interval: Number(data.interval ?? 0),
      tiers,
    };
  }

  /**
   * 获取播放链接（v3），按音质阶梯逐级降级。
   *
   * 上游偶尔返回 code=0 但 url 为空、或 kbps 为 0kbps 的死链，所以不能只看返回码。
   * [verify] 用于在阶梯内部就把死链筛掉：探测放在循环里而不是循环外，
   * 某一档给出死链时还能继续往下试，否则会白白放弃后面本来可用的低音质档位。
   *
   * [available] 是 `/song/info` 给出的可用档位集合，传了就只试这些档 ——
   * 能把最坏情况从 4 次请求降到 1 次，也不会再把请求打在必定失败的档位上。
   *
   * 命中风控码 110001 时 v3 整体不可用：回退 v2 geturl 拿一次地址，
   * 拿不到再继续走阶梯（后续档大概率同样风控，但保持行为一致、错误信息也完整）。
   */
  async resolveLink(
    key: SongKey,
    quality: number,
    verify?: (url: string) => Promise<boolean>,
    available?: Set<number>,
  ): Promise<UpstreamLink> {
    const identity = this.identityOf(key);
    const typeParam = key.type === undefined ? "" : `&type=${key.type}`;
    let lastError = "播放地址不可用";
    for (const attempt of this.qualityLadderFrom(quality, available)) {
      try {
        const payload = await this.requestJson(
          `${this.config.upstreamV3BaseUrl}/song/link?${identity}&quality=${attempt}${typeParam}`,
        );
        if (Number(payload?.code) === CODE_LINK_RISK_CONTROL && key.id && key.id > 0) {
          const rescued = await this.resolveLinkViaV2(key.id);
          if (rescued) return rescued;
        }
        const data = this.unwrap(payload, "播放地址不可用");
        const url = String(data.url ?? "").replace(/^http:/, "https:");
        if (!url) {
          lastError = `音质 ${attempt} 无可用地址`;
          continue;
        }
        if (verify && !(await verify(url))) {
          lastError = `音质 ${attempt} 的地址拉不动`;
          continue;
        }
        return { url, kbps: String(data.kbps ?? ""), quality: attempt };
      } catch (error) {
        lastError = error instanceof Error ? error.message : String(error);
      }
    }
    throw ApiErrors.upstream(lastError);
  }

  /**
   * 从 v2 的 geturl 接口兜底拿播放地址。
   *
   * v3 的 /song/link 偶发 cookie 风控（code=110001），同一首歌走旧版 geturl
   * 往往仍能给出可播直链。v2 不分音质档，给的多是低码率试听链，
   * 所以 quality 按最低档 0 上报，避免客户端把未知档位记成高音质。
   * 拿不到（风控、无 url、网络错误）时返回 undefined，让调用方继续沿阶梯降级。
   */
  private async resolveLinkViaV2(id: number): Promise<UpstreamLink | undefined> {
    try {
      const data = this.unwrap(
        await this.requestJson(`${this.config.upstreamV2BaseUrl}/geturl?id=${id}`),
        "播放地址不可用",
      );
      const url = String(data.url ?? "").replace(/^http:/, "https:");
      if (!url) return undefined;
      return { url, kbps: String(data.kbps ?? ""), quality: 0 };
    } catch {
      return undefined;
    }
  }

  /**
   * 歌词（v2）。v3 没有等价接口，且只有 v2 同时给出逐字时间轴与翻译。
   *
   * v2 只接受数字 ID；搜索结果为 mid-only 时，先用 v3 单曲信息把 mid 解析成正 ID。
   */
  async requestLyric(key: SongKey): Promise<RichLyric> {
    this.identityOf(key);
    let id = Number(key.id);
    if (!Number.isInteger(id) || id <= 0) {
      const info = await this.requestSongInfo({ mid: key.mid?.trim() });
      id = Number(info.songID);
      if (!Number.isInteger(id) || id <= 0) throw ApiErrors.upstream("歌词歌曲信息不可用");
    }
    const data = this.unwrap(await this.requestJson(`${this.config.upstreamV2BaseUrl}/lyric?id=${id}`), "没有歌词");
    const lrc = String(data.lrc ?? data.lyric ?? "").trim();
    const yrc = String(data.yrc ?? "").trim();
    // 没有 lrc 但有 yrc 的情况也算有歌词，客户端能从 yrc 还原出行文本。
    if (!lrc && !yrc) throw ApiErrors.upstream("没有歌词");
    return { lrc, yrc, trans: String(data.trans ?? "").trim() };
  }

  /** 身份参数：优先用数字 id，没有正整数 id 时退回 mid。 */
  private identityOf(key: SongKey): string {
    if (key.id && Number.isInteger(key.id) && key.id > 0) return `id=${key.id}`;
    const mid = key.mid?.trim();
    if (!mid) throw ApiErrors.badRequest(4001, "请提供歌曲 ID 或 mid");
    return `mid=${encodeURIComponent(mid)}`;
  }

  /**
   * 要尝试的档位序列。
   *
   * 传了可用档位集合时只保留其中存在的档：请求档位本身可用就直接命中，
   * 否则取比它低的最高可用档 —— 一次请求就够，不用把阶梯走完。
   */
  private qualityLadderFrom(quality: number, available?: Set<number>): number[] {
    if (available?.size) {
      if (available.has(quality)) return [quality];
      const lower = [...available].filter((value) => value < quality).sort((left, right) => right - left);
      return lower.slice(0, MAX_QUALITY_ATTEMPTS);
    }
    const lower = QUALITY_LADDER.filter((value) => value < quality);
    return [quality, ...lower].slice(0, MAX_QUALITY_ATTEMPTS);
  }

  /**
   * 上游返回的成功码不统一：v3 用 0，v2 歌词用 200，两个都要认。
   * 非成功码时把上游的 message 带出去，便于定位是音质不可用还是歌曲不存在。
   */
  private unwrap(payload: any, fallback: string): any {
    const code = Number(payload?.code);
    if (code !== 0 && code !== 200) throw ApiErrors.upstream(String(payload?.message ?? fallback));
    return payload?.data ?? {};
  }

  private async requestJson(url: string): Promise<any> {
    const response = await fetch(url, {
      headers: { accept: "application/json", "user-agent": "TaotaoMusic/1.0" },
    });
    if (!response.ok) throw ApiErrors.upstream(`上游接口错误：${response.status}`);
    return response.json();
  }

}
