import { Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import type { MusicSource, MusicSourceClient, SongKey } from "./music-source.client";
import type { RichLyric, SearchResult, UpstreamLink, UpstreamSong, UpstreamSongInfo } from "./tencent.client";

/** 网易云点歌接口适配。它的搜索和单曲直链共用同一个 v2 接口。 */
@Injectable()
export class NeteaseClient implements MusicSourceClient {
  readonly source: MusicSource = "netease";
  readonly displayName = "网易云";
  readonly numericIdOnly = true;
  /** 上游不分音质档位，多取一次单曲信息只是白白多一个请求。 */
  readonly supportsTierProbe = false;

  private readonly baseUrl = "https://api.vkeys.cn/v2/music/netease";

  /** 搜索只返回元数据；播放地址按需在 [resolveLink] 中获取。 */
  async searchSongs(keyword: string, page: number, limit: number): Promise<SearchResult> {
    const data = this.unwrap(
      await this.requestJson(`${this.baseUrl}?word=${encodeURIComponent(keyword)}&page=${page}&num=${limit}`),
      "搜索失败",
    );
    const list = (Array.isArray(data) ? data : []).map((item) => this.toSong(item));
    return {
      total: list.length,
      perPage: limit,
      nextPage: list.length === limit ? page + 1 : null,
      list,
      // 本音源不做可播性预筛，上游返回什么就下发什么。
      dropped: 0,
    };
  }

  /** 文档未提供音质列表接口；用最高请求档取回歌曲资料，供历史记录补全。 */
  async requestSongInfo(key: SongKey): Promise<UpstreamSongInfo> {
    const data = await this.requestSong(key.id, 18);
    return {
      songID: Number(data.id ?? 0),
      songMID: "",
      title: String(data.song ?? ""),
      singer: String(data.singer ?? ""),
      album: String(data.album ?? ""),
      cover: this.httpsUrl(data.cover),
      pay: "",
      interval: this.durationOf(data.interval),
      tiers: [{ type: 18, size: this.sizeOf(data.size), label: String(data.quality ?? "网易云最高可用音质") }],
    };
  }

  async resolveLink(key: SongKey, quality: number): Promise<UpstreamLink> {
    const data = await this.requestSong(key.id, quality);
    const url = this.httpsUrl(data.url);
    if (!url) throw ApiErrors.upstream("播放地址不可用");
    return { url, kbps: String(data.kbps ?? ""), quality };
  }

  async requestLyric(key: SongKey): Promise<RichLyric> {
    const id = key.id;
    if (!id || !Number.isInteger(id) || id <= 0) {
      throw ApiErrors.badRequest(4001, "网易云歌曲必须提供正整数 ID");
    }
    const data = this.unwrap(
      await this.requestJson(`${this.baseUrl}/lyric?id=${id}`),
      "没有歌词",
    );
    const lrc = String(data.lrc ?? "").trim();
    const yrc = String(data.yrc ?? "").trim();
    if (!lrc && !yrc) throw ApiErrors.upstream("没有歌词");
    return { lrc, yrc, trans: String(data.trans ?? "").trim() };
  }

  private async requestSong(id: number | undefined, quality: number): Promise<any> {
    if (!id || id <= 0) throw ApiErrors.badRequest(4001, "请提供歌曲 ID");
    const data = this.unwrap(
      await this.requestJson(`${this.baseUrl}?id=${id}&quality=${this.neteaseQuality(quality)}`),
      "歌曲不可用",
    );
    return Array.isArray(data) ? data[0] ?? {} : data;
  }

  private toSong(item: any): UpstreamSong {
    return {
      songID: Number(item?.id ?? 0),
      title: String(item?.song ?? ""),
      singer: String(item?.singer ?? ""),
      album: String(item?.album ?? ""),
      time: String(item?.time ?? ""),
      cover: this.httpsUrl(item?.cover),
    };
  }

  /** 将客户端统一的 0–18 档位映射到网易云接口的 1–9 档位。 */
  private neteaseQuality(quality: number): number {
    // Android 旧版本仍会把 AudioQuality.MASTER(14) 原样发送；它与桌面归一后的
    // 18 都表示“网易云最高可用音质”，不能让 14 落到上游的第 7 档。
    const normalized = quality === 14 ? 18 : quality;
    return Math.min(9, Math.max(1, Math.ceil(Math.min(18, Math.max(0, normalized)) / 2)));
  }

  private durationOf(value: unknown): number {
    const matched = String(value ?? "").match(/(\d+)分(?:钟)?\s*(\d+)?/);
    return matched ? Number(matched[1]) * 60 + Number(matched[2] ?? 0) : 0;
  }

  private sizeOf(value: unknown): number {
    const matched = String(value ?? "").match(/([\d.]+)\s*(KB|MB|GB)/i);
    if (!matched) return 0;
    const unit = matched[2].toUpperCase();
    const factor = unit === "GB" ? 1024 ** 3 : unit === "MB" ? 1024 ** 2 : 1024;
    return Math.round(Number(matched[1]) * factor);
  }

  private httpsUrl(value: unknown): string {
    return String(value ?? "").replace(/^http:/, "https:");
  }

  private unwrap(payload: any, fallback: string): any {
    if (Number(payload?.code) !== 200) throw ApiErrors.upstream(String(payload?.message ?? fallback));
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
