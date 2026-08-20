import type { Song } from "../types.js";
import { requestJson, requestLyric } from "../upstream/tencent.js";

export function qualityOf(value: string | null): number { const quality = Number(value ?? 10); return Number.isInteger(quality) ? Math.min(16, Math.max(0, quality)) : 10; }

function normalize(item: any): Song { return { id: Number(item.id), title: item.song ?? "未知歌曲", artist: item.singer ?? "未知歌手", album: item.album ?? "未知专辑", subtitle: item.subtitle ?? "", time: item.time ?? "", duration: item.interval ?? "网络歌曲", audioUrl: String(item.url ?? "").replace(/^http:/, "https:") || undefined, coverUrl: item.cover ?? item.pic ?? item.album_pic, lyricUrl: item.lyric_url ?? item.lyric }; }

async function validAudio(url: string): Promise<boolean> {
  const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), 8_000);
  try { const response = await fetch(url, { headers: { range: "bytes=0-1", "user-agent": "TaotaoMusic/1.0" }, redirect: "follow", signal: controller.signal }); await response.body?.cancel(); return response.status >= 200 && response.status < 300; } catch { return false; } finally { clearTimeout(timer); }
}

/** 获取并验证指定品质的播放地址。 */
export async function resolveSong(item: any, quality = 10): Promise<Song> {
  const id = Number(item.id); const mid = String(item.mid ?? ""); const key = id > 0 ? `id=${id}` : `mid=${encodeURIComponent(mid)}`;
  const result = await requestJson(`/geturl?${key}&quality=${quality}`); const song = normalize({ ...item, ...(result.data ?? {}) });
  if (!song.audioUrl || !(await validAudio(song.audioUrl))) throw new Error("播放地址不可用");
  const lyric = await requestLyric(id).catch(() => ""); return { ...song, lyricUrl: lyric ? `/api/v1/songs/${id}/lyrics` : undefined };
}

export { requestLyric };
