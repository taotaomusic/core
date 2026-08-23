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
  /** 上游的 songMID。id 为 0 的歌只能靠它解析播放地址，必须下发给客户端。 */
  mid?: string;
  /** 上游的歌曲类型，解析播放地址时要原样带回去。 */
  type?: number;
  /** 是否为付费/VIP 歌曲。搜索结果里本来就有 `pay` 字段，之前一直没读。 */
  vip: boolean;
  /** 当前用户是否已收藏。由 [SearchService] 批量查询后填入。 */
  favorited: boolean;
};

/** 音质档位上限。实测上游 `qualityInfo` 到 18（NAC），README 里写的 16 是旧的。 */
const MAX_QUALITY = 18;
const DEFAULT_QUALITY = 10;

@Injectable()
export class SongMapper {
  /**
   * 收敛音质参数到 0–18，缺省 10。
   *
   * 空串要落到默认值而不是 0：`Number("")` 是 0 且 `Number.isInteger(0)` 为真，
   * 直接判断会让 `?quality=` 静默变成最低档「音乐试听」。
   */
  qualityOf(value: string | undefined): number {
    if (value === undefined || value.trim() === "") return DEFAULT_QUALITY;
    const quality = Number(value);
    return Number.isInteger(quality) ? Math.min(MAX_QUALITY, Math.max(0, quality)) : DEFAULT_QUALITY;
  }

  /**
   * 把 v3 搜索结果映射成客户端模型。
   *
   * 元信息全部来自搜索结果：v3 的播放链接接口实测只返回 songID / songMID / kbps /
   * link / url 五个字段，没有文档里写的歌名、歌手、封面和时长。
   *
   * [playBase] 用于拼装 `audioUrl`。这里刻意给**自家的代理地址**而不是上游直链：
   * 搜索不再逐首解析，直链无从得知；而装机的旧客户端会直接播这个字段，
   * 给它一个自家地址正是它们今天实际在播的东西。新客户端会忽略它，
   * 自己走 `/songs/{id}/link` 拿直链。
   */
  toSong(item: UpstreamSong, playBase: string, quality: number, favorited = false): Song {
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
      audioUrl: id > 0 ? `${playBase}/api/v1/songs/${id}/play?quality=${quality}` : undefined,
      mid: item.songMID,
      type: item.type,
      vip: (item.pay ?? "").includes("付费"),
      favorited,
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
