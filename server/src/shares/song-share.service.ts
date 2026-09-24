import { Injectable } from "@nestjs/common";
import { randomBytes } from "node:crypto";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { StreamService } from "../music/stream.service";
import { ReleaseRepository } from "../release/release.repository";
import type { MusicSource, SongKey } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";
import { SongShareRepository, type SongShareRecord, type SongShareSnapshot } from "./song-share.repository";

export type CreateSongShareInput = {
  /** 分享歌曲的音源。**必须与 remoteId 成对**：数字 ID 只在所属音源内有意义。 */
  source: MusicSource;
  remoteId?: number;
  mid?: string;
  type?: number;
};

/**
 * 分享页最多允许试听多少秒。
 *
 * 这个上限**只用来告知客户端**（`previewDurationSeconds`），服务端不做任何裁剪：
 * 音乐不进我们的磁盘，超时由分享页自己守（`webApp` 的 `WebAudioController`）。
 */
const PREVIEW_SECONDS = 60;

/**
 * 取试听地址用的音质档位 = **标准音质**。
 *
 * 实测《晴天》的 `qualityInfo`：档位 0「音乐试听」本身就是上游裁好的 60 秒片段（约 960 KB），
 * 档位 3 / 4 才是完整的「标准音质」（m4a，约 6.5 MB）。**用 0 就是在要 60 秒音乐**，
 * 所以这里要 4；档位不存在时 `resolveLink` 会自己沿阶梯降级。
 *
 * 注意上游偶发 `110001` 风控时，`resolveLink` 会回退到 v2 `geturl` 的低码率试听链
 * （那确实是个 60 秒文件）。这是**全站播放路径共用的既有兜底**，不是本接口特有的行为 ——
 * 与其在这里加一套重试，不如让 `/songs/:id/play` 一起受益。
 */
const PREVIEW_QUALITY = 4;

@Injectable()
export class SongShareService {
  constructor(
    private readonly config: AppConfigService,
    private readonly repository: SongShareRepository,
    private readonly releases: ReleaseRepository,
    private readonly registry: MusicSourceRegistry,
    private readonly stream: StreamService,
  ) {}

  async create(userId: number, input: CreateSongShareInput, request: Request) {
    const key = this.keyOf(input);
    const info = await this.registry.of(input.source).requestSongInfo(key);
    const remoteId = Number(info.songID) > 0 ? String(info.songID) : input.remoteId ? String(input.remoteId) : null;
    const mid = String(info.songMID || input.mid || "").trim() || null;
    const stableId = remoteId || mid;
    if (!stableId) throw ApiErrors.badRequest(4001, "歌曲缺少可分享的稳定身份");
    const snapshot: SongShareSnapshot = {
      source: input.source,
      song_id: stableId,
      remote_id: remoteId,
      mid,
      song_type: input.type ?? null,
      title: info.title.trim() || "未知歌曲",
      artist: info.singer.trim() || "未知歌手",
      album: info.album.trim(),
      cover_url: info.cover.trim() || null,
      duration_seconds: Math.max(0, Math.trunc(info.interval)),
      vip: info.pay.includes("付费") ? 1 : 0,
    };
    const share = await this.repository.upsert(userId, this.newToken(), snapshot);
    return {
      token: share.token,
      url: `${this.publicBaseOf(request)}/s/${share.token}`,
    };
  }

  async metadata(token: string, request: Request) {
    const share = await this.requiredShare(token);
    void this.repository.noteAccess(token).catch(() => undefined);
    const base = this.publicBaseOf(request);
    const latestVersion = await this.releases.latestFullyRolledOut(this.config.defaultChannel);
    return {
      title: share.title,
      artist: share.artist,
      album: share.album,
      coverUrl: share.cover_url,
      duration: this.durationLabel(share.duration_seconds),
      songId: share.remote_id,
      mid: share.mid,
      type: share.song_type,
      source: share.source,
      vip: share.vip === 1,
      previewDurationSeconds: Math.min(PREVIEW_SECONDS, share.duration_seconds || PREVIEW_SECONDS),
      previewUrl: `${base}/api/v1/public/shares/${share.token}/preview`,
      appDownloadUrl: latestVersion
        ? `${base}/api/v1/app/apk/${latestVersion}`
        : base,
    };
  }

  /**
   * 公开试听流。
   *
   * **服务端不裁剪、不落盘**：这里只按 Range 转发上游的完整标准音频，「最多 60 秒」
   * 由分享页自己守。所以响应体里就是完整音频 —— 这没关系，上游直链本来就有时效，
   * 而把音乐缓存到服务器磁盘（哪怕只有 60 秒）是明确不做的。
   *
   * 失败一律归 502：**绝不能返回 401**，那会让客户端把上游故障当成自己的令牌失效
   * 去续期，二次失败后把用户踢回登录页。
   */
  async streamPreview(token: string, request: Request, response: Response): Promise<void> {
    const share = await this.requiredShare(token);
    const target = await this.stream.resolvePlayUrl(this.playKeyOf(share), PREVIEW_QUALITY, share.source);
    await this.stream.proxy(request, response, target);
  }

  private async requiredShare(token: string): Promise<SongShareRecord> {
    if (!/^[A-Za-z0-9_-]{8,24}$/.test(token)) throw ApiErrors.notFound(4045, "分享链接不存在或已失效");
    const share = await this.repository.find(token);
    if (!share) throw ApiErrors.notFound(4045, "分享链接不存在或已失效");
    return share;
  }

  /**
   * 从落库的快照还原歌曲身份。
   *
   * 快照里 `remote_id` / `mid` / `song_type` 都是可空的，缺哪个就不带哪个 ——
   * 单曲接口自己会按音源能力标记判断这份身份够不够用，这里不做音源分支。
   */
  private playKeyOf(share: SongShareRecord): SongKey {
    const id = Number(share.remote_id ?? 0);
    return {
      ...(Number.isSafeInteger(id) && id > 0 ? { id } : {}),
      ...(share.mid ? { mid: share.mid } : {}),
      ...(share.song_type === null ? {} : { type: share.song_type }),
    };
  }

  /**
   * 组装歌曲身份。
   *
   * 哪些音源允许 mid-only 由适配器的 `numericIdOnly` 决定，这里不写按音源的分支。
   * `remoteId` 单独拿出来没有意义 —— 它必须和 `input.source` 一起才能定位一首歌。
   */
  private keyOf(input: CreateSongShareInput): SongKey {
    const client = this.registry.of(input.source);
    const id = Number.isSafeInteger(input.remoteId) && Number(input.remoteId) > 0
      ? input.remoteId
      : undefined;
    if (client.numericIdOnly) {
      if (!id) throw ApiErrors.badRequest(4001, `${client.displayName}歌曲必须提供正整数 ID`);
      return { id };
    }
    const mid = input.mid?.trim();
    if (!id && !mid) throw ApiErrors.badRequest(4001, "请提供歌曲 ID 或 mid");
    return { ...(id ? { id } : {}), ...(mid ? { mid } : {}) };
  }

  private newToken(): string {
    return randomBytes(9).toString("base64url");
  }

  private publicBaseOf(request: Request): string {
    if (this.config.publicBaseUrl) return this.config.publicBaseUrl;
    const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
    const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
    return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}`;
  }

  private durationLabel(seconds: number): string {
    const safe = Math.max(0, Math.trunc(seconds));
    return `${Math.floor(safe / 60).toString().padStart(2, "0")}:${(safe % 60).toString().padStart(2, "0")}`;
  }
}
