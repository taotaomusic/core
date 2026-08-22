import { upstreamBaseUrl } from "../config.js";

/** 请求腾讯音乐上游接口。 */
export async function requestJson(path: string): Promise<any> {
  const response = await fetch(`${upstreamBaseUrl}${path}`, { headers: { accept: "application/json" } });
  if (!response.ok) throw new Error(`上游接口错误：${response.status}`);
  return response.json();
}

/**
 * 歌词三件套。
 *
 * `lrc` 是行级时间轴的普通 LRC；`yrc` 是逐字时间轴，格式为
 * `[行起始ms,行时长ms]文本(字起始ms,字时长ms)文本(字起始ms,字时长ms)…`，
 * 客户端靠它做逐字高亮；`trans` 是翻译，可能为空。
 */
export type RichLyric = { lrc: string; yrc: string; trans: string };

export async function requestLyric(id: number): Promise<RichLyric> {
  const result = await requestJson(`/lyric?id=${id}`);
  const data = result.data ?? {};
  const lrc = String(data.lrc ?? data.lyric ?? "").trim();
  const yrc = String(data.yrc ?? "").trim();
  // 没有 lrc 但有 yrc 的情况也算有歌词，客户端能从 yrc 还原出行文本。
  if (!lrc && !yrc) throw new Error("没有歌词");
  return { lrc, yrc, trans: String(data.trans ?? "").trim() };
}
