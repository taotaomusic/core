import { upstreamBaseUrl } from "../config.js";

/** 请求腾讯音乐上游接口。 */
export async function requestJson(path: string): Promise<any> {
  const response = await fetch(`${upstreamBaseUrl}${path}`, { headers: { accept: "application/json" } });
  if (!response.ok) throw new Error(`上游接口错误：${response.status}`);
  return response.json();
}

export async function requestLyric(id: number): Promise<string> {
  const result = await requestJson(`/lyric?id=${id}`);
  const lyric = String(result.data?.lrc ?? result.data?.lyric ?? "").trim();
  if (!lyric) throw new Error("没有歌词");
  return lyric;
}
