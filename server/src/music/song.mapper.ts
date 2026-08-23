import { Injectable } from "@nestjs/common";
import type { UpstreamSong } from "../upstream/tencent.client";

/**
 * 移动端统一使用的歌曲模型。
 *
 * 字段名与类型都是历史契约，客户端逐个字段读取：
 * - `id` 必须是 JSON number，字符串化会让客户端算出 remoteId=null，
 *   该歌随即不可播、不可收藏、无歌词
 * - `duration` 是已格式化的 `mm:ss` 字符串，不是秒数
 * - `lyricUrl` 是相对路径（带前导斜杠），客户端会自己补上基地址
 * - `coverUrl` 必须是免鉴权的绝对 https 地址，客户端直接交给图片库加载
 */
export type Song = {
  id: number;
  title: string;
  artist: string;
  album: string;
  subtitle: string;
  time: string;
  duration: string;
  audioUrl?: string;
  coverUrl?: string;
  lyricUrl?: string;
};

@Injectable()
export class SongMapper {
  /** 收敛音质参数到 0–16，缺省 10。 */
  qualityOf(value: string | undefined): number {
    const quality = Number(value ?? 10);
    return Number.isInteger(quality) ? Math.min(16, Math.max(0, quality)) : 10;
  }

  /**
   * 把 v3 搜索结果映射成客户端模型。
   *
   * 元信息全部来自搜索结果：v3 的播放链接接口实测只返回 songID / songMID / kbps /
   * link / url 五个字段，没有文档里写的歌名、歌手、封面和时长。
   */
  toSong(item: UpstreamSong): Song {
    const id = Number(item.songID);
    return {
      id,
      title: item.title ?? "未知歌曲",
      artist: item.singer ?? "未知歌手",
      album: item.album ?? "未知专辑",
      subtitle: item.subtitle ?? "",
      time: item.time ?? "",
      duration: this.formatDuration(item.interval),
      coverUrl: item.cover ?? item.albumImage,
      // 歌词一律给出自家地址：客户端拉取时才知道有没有，没有就显示「暂无歌词」。
      // 早先在搜索里逐首探测歌词是否存在，每首多一次上游请求却没有任何界面用到。
      lyricUrl: id > 0 ? `/api/v1/songs/${id}/lyrics` : undefined,
    };
  }

  /** 秒数转 mm:ss。v3 搜索返回的 interval 是整数秒，客户端要的是可直接显示的字符串。 */
  private formatDuration(seconds: unknown): string {
    const total = Number(seconds);
    if (!Number.isFinite(total) || total <= 0) return "网络歌曲";
    const minutes = Math.floor(total / 60);
    return `${String(minutes).padStart(2, "0")}:${String(Math.floor(total % 60)).padStart(2, "0")}`;
  }
}
