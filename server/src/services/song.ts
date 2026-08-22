import type { Song } from "../types.js";
import { requestLyric, resolveLink, searchSongs, type UpstreamSong } from "../upstream/tencent.js";

export function qualityOf(value: string | null): number {
  const quality = Number(value ?? 10);
  return Number.isInteger(quality) ? Math.min(16, Math.max(0, quality)) : 10;
}

/** 秒数转 mm:ss。v3 搜索返回的 interval 是整数秒，客户端要的是可直接显示的字符串。 */
function formatDuration(seconds: unknown): string {
  const total = Number(seconds);
  if (!Number.isFinite(total) || total <= 0) return "网络歌曲";
  const minutes = Math.floor(total / 60);
  return `${String(minutes).padStart(2, "0")}:${String(Math.floor(total % 60)).padStart(2, "0")}`;
}

/**
 * 把 v3 搜索结果映射成客户端模型。
 *
 * 元信息全部来自搜索结果：v3 的播放链接接口实测只返回 songID / songMID / kbps /
 * link / url 五个字段，没有文档里写的歌名、歌手、封面和时长，不能再像 v2 那样
 * 靠合并 geturl 的返回来补全。
 */
export function normalize(item: UpstreamSong): Song {
  const id = Number(item.songID);
  return {
    id,
    title: item.title ?? "未知歌曲",
    artist: item.singer ?? "未知歌手",
    album: item.album ?? "未知专辑",
    subtitle: item.subtitle ?? "",
    time: item.time ?? "",
    duration: formatDuration(item.interval),
    coverUrl: item.cover ?? item.albumImage,
    // 歌词一律给出自家地址：客户端拉取时才知道有没有，没有就显示「暂无歌词」。
    // 早先在搜索里逐首探测歌词是否存在，每首多一次上游请求却没有任何界面用到。
    lyricUrl: id > 0 ? `/api/v1/songs/${id}/lyrics` : undefined,
  };
}

/** 搜索：只打一次上游，不再逐首解析播放地址。 */
export async function search(keyword: string, page: number, limit: number) {
  return searchSongs(keyword, page, limit);
}

async function validAudio(url: string): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8_000);
  try {
    const response = await fetch(url, {
      headers: { range: "bytes=0-1", "user-agent": "TaotaoMusic/1.0" },
      redirect: "follow",
      signal: controller.signal,
    });
    await response.body?.cancel();
    return response.status >= 200 && response.status < 300;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

/** 补齐一首歌的播放地址；每一档音质都会探测首字节，全部不可用才抛错。 */
export async function resolveSong(item: UpstreamSong, quality = 10): Promise<Song> {
  const song = normalize(item);
  const link = await resolveLink(
    { id: Number(item.songID), mid: item.songMID, type: item.type },
    quality,
    validAudio,
  );
  return { ...song, audioUrl: link.url };
}

/** 仅取播放地址，用于 /play 转发。跳过可用性探测，让实际转发去暴露问题。 */
export async function resolvePlayUrl(id: number, quality: number, type?: number): Promise<string> {
  const link = await resolveLink({ id, type }, quality);
  return link.url;
}

export { requestLyric };
