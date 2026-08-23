import { Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";

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
};

export type SearchResult = { total: number; perPage: number; nextPage: number | null; list: UpstreamSong[] };
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

@Injectable()
export class TencentClient {
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
    };
  }

  /**
   * 歌曲信息与可用音质档位（v3 `/song/info`）。
   *
   * 这是唯一能按 id / mid 单曲查询的接口 —— 搜索只能按关键词。它比搜索结果多出来的
   * 只有 `qualityInfo`，但那正是关键：它列出这首歌**真实存在**的档位与各档字节数。
   */
  async requestSongInfo(key: { id?: number; mid?: string }): Promise<UpstreamSongInfo> {
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
   */
  async resolveLink(
    key: { id?: number; mid?: string; type?: number },
    quality: number,
    verify?: (url: string) => Promise<boolean>,
    available?: Set<number>,
  ): Promise<UpstreamLink> {
    const identity = this.identityOf(key);
    const typeParam = key.type === undefined ? "" : `&type=${key.type}`;
    let lastError = "播放地址不可用";
    for (const attempt of this.qualityLadderFrom(quality, available)) {
      try {
        const data = this.unwrap(
          await this.requestJson(`${this.config.upstreamV3BaseUrl}/song/link?${identity}&quality=${attempt}${typeParam}`),
          "播放地址不可用",
        );
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

  /** 歌词（v2）。v3 没有等价接口，且只有 v2 同时给出逐字时间轴与翻译。 */
  async requestLyric(id: number): Promise<RichLyric> {
    const data = this.unwrap(await this.requestJson(`${this.config.upstreamV2BaseUrl}/lyric?id=${id}`), "没有歌词");
    const lrc = String(data.lrc ?? data.lyric ?? "").trim();
    const yrc = String(data.yrc ?? "").trim();
    // 没有 lrc 但有 yrc 的情况也算有歌词，客户端能从 yrc 还原出行文本。
    if (!lrc && !yrc) throw ApiErrors.upstream("没有歌词");
    return { lrc, yrc, trans: String(data.trans ?? "").trim() };
  }

  /** 身份参数：优先用数字 id，没有正整数 id 时退回 mid。 */
  private identityOf(key: { id?: number; mid?: string }): string {
    return key.id && key.id > 0 ? `id=${key.id}` : `mid=${encodeURIComponent(key.mid ?? "")}`;
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
