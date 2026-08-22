import { upstreamV2BaseUrl, upstreamV3BaseUrl } from "../config.js";

/**
 * 上游返回的成功码不统一：v3 用 0，v2 歌词用 200，两个都要认。
 * 非成功码时把上游的 message 带出去，便于定位是音质不可用还是歌曲不存在。
 */
function unwrap(payload: any, fallback: string): any {
  const code = Number(payload?.code);
  if (code !== 0 && code !== 200) throw new Error(String(payload?.message ?? fallback));
  return payload?.data ?? {};
}

async function requestJson(url: string): Promise<any> {
  const response = await fetch(url, { headers: { accept: "application/json", "user-agent": "TaotaoMusic/1.0" } });
  if (!response.ok) throw new Error(`上游接口错误：${response.status}`);
  return response.json();
}

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

/** 歌曲搜索（v3）。分页参数在 v3 里叫 limit，不叫 num。 */
export async function searchSongs(keyword: string, page: number, limit: number): Promise<SearchResult> {
  const query = `?keyword=${encodeURIComponent(keyword)}&page=${page}&limit=${limit}`;
  const data = unwrap(await requestJson(`${upstreamV3BaseUrl}/search/song${query}`), "搜索失败");
  return {
    total: Number(data.meta?.total ?? 0),
    perPage: Number(data.meta?.perPage ?? limit),
    nextPage: data.meta?.nextPage ?? null,
    list: Array.isArray(data.list) ? data.list : [],
  };
}

/**
 * 音质降级阶梯。
 *
 * v3 的播放链接接口与 v2 不同：所选音质拿不到时**不会自动降级**，而是直接报错
 * （实测付费歌曲请求 quality=14 返回 code=110000）。所以必须自己逐级往下试，
 * 否则大量歌曲会因为「无损拿不到」而被当成不可播放直接丢弃。
 */
const QUALITY_LADDER = [14, 11, 10, 8, 4, 0];

/** 最多尝试几档。每档都是一次网络请求，档数太多会把搜索拖慢。 */
const MAX_QUALITY_ATTEMPTS = 4;

function qualityLadderFrom(quality: number): number[] {
  const lower = QUALITY_LADDER.filter((value) => value < quality);
  return [quality, ...lower].slice(0, MAX_QUALITY_ATTEMPTS);
}

export type UpstreamLink = { url: string; kbps: string; quality: number };

/**
 * 获取播放链接（v3），按音质阶梯逐级降级。
 *
 * 上游偶尔返回 code=0 但 url 为空、或 kbps 为 0kbps 的死链，
 * 所以不能只看返回码。[verify] 用于在阶梯内部就把死链筛掉：
 * 探测放在循环里而不是循环外，某一档给出死链时还能继续往下试，
 * 否则会白白放弃后面本来可用的低音质档位。
 */
export async function resolveLink(
  key: { id?: number; mid?: string; type?: number },
  quality: number,
  verify?: (url: string) => Promise<boolean>,
): Promise<UpstreamLink> {
  const identity = key.id && key.id > 0 ? `id=${key.id}` : `mid=${encodeURIComponent(key.mid ?? "")}`;
  const typeParam = key.type === undefined ? "" : `&type=${key.type}`;
  let lastError = "播放地址不可用";
  for (const attempt of qualityLadderFrom(quality)) {
    try {
      const data = unwrap(
        await requestJson(`${upstreamV3BaseUrl}/song/link?${identity}&quality=${attempt}${typeParam}`),
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
  throw new Error(lastError);
}

/**
 * 歌词三件套（v2）。
 *
 * `lrc` 是行级时间轴的普通 LRC；`yrc` 是逐字时间轴，格式为
 * `[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`，客户端靠它做逐字高亮；
 * `trans` 是翻译，可能为空。
 */
export type RichLyric = { lrc: string; yrc: string; trans: string };

export async function requestLyric(id: number): Promise<RichLyric> {
  const data = unwrap(await requestJson(`${upstreamV2BaseUrl}/lyric?id=${id}`), "没有歌词");
  const lrc = String(data.lrc ?? data.lyric ?? "").trim();
  const yrc = String(data.yrc ?? "").trim();
  // 没有 lrc 但有 yrc 的情况也算有歌词，客户端能从 yrc 还原出行文本。
  if (!lrc && !yrc) throw new Error("没有歌词");
  return { lrc, yrc, trans: String(data.trans ?? "").trim() };
}
