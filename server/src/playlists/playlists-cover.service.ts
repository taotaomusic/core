import { Injectable, Logger } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";
import { isMusicSource, type SongKey } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";
// 注意：PlaylistsRepository 必须是值导入而非 import type —— 构造器注入依赖
// design:paramtypes 元数据，type-only 导入会被擦除导致 Nest 解析不到依赖。
import { PlaylistsRepository } from "./playlists.repository";
import type {
  PlaylistCoverCandidate,
  PlaylistDetail,
  PlaylistRecord,
  PlaylistSongRecord,
} from "./playlists.repository";

/**
 * 歌单封面惰性回源：读到缺封面的歌曲快照时，向上游取真实封面并写回库。
 *
 * 背景：`cover_url` 为空的歌单在列表里靠「取歌单内第一张非空歌曲封面」兜底
 * （见 playlists.repository.ts 的 PLAYLIST_COLUMNS），但早期加入的快照本身没存
 * 封面，兜底无从取图。与其要求人工跑回填工具，这里在**读取路径**上自愈：
 * 列表接口给「整单无封面」的歌单回源首曲封面（歌单卡片只需要这一张），
 * 详情接口给缺封面的曲目逐首回源。自愈完成后快照非空，后续读取零开销。
 *
 * 对上游保持克制：同进程内一次只有一个探测在途（串行管道）、调用间 250ms
 * 限流、单次 3s 超时、同步部分受时间预算约束（超出转后台，不打慢响应）。
 * 任何失败静默留空等下次读取再试，绝不影响接口本身的响应。
 */
const UPSTREAM_THROTTLE_MS = 250;
const UPSTREAM_TIMEOUT_MS = 3_000;
/** 列表接口同步回填预算：通常只有一两个空歌单，超预算的转后台。 */
const LIST_SYNC_BUDGET_MS = 1_200;
/** 详情接口同步回填预算：预算内回填完直接随响应可见，其余后台补。 */
const DETAIL_SYNC_BUDGET_MS = 1_500;

/** 待回源的歌曲身份（含所属歌单，回写时按 playlist_id + source + song_id 定位）。 */
type CoverCandidate = {
  playlistId: number;
  source: string;
  songId: string;
  mid: string | null;
  type: number | null;
};

@Injectable()
export class PlaylistCoverService {
  private readonly logger = new Logger("PlaylistCoverBackfill");
  /** 串行管道：让并发请求的探测请求排队执行，天然限流且不会并发打同一首。 */
  private chain: Promise<unknown> = Promise.resolve();

  constructor(
    private readonly database: DatabaseService,
    private readonly registry: MusicSourceRegistry,
    private readonly repository: PlaylistsRepository,
  ) {}

  /**
   * 列表兜底：给「整单无封面」的歌单回源首曲封面。同步部分受预算约束，
   * 回源成功后直接改写本次响应的记录（不必重查库），失败保持空待下次。
   */
  async fillForList(userId: number, records: PlaylistRecord[]): Promise<void> {
    try {
      const emptyIds = new Set(records.filter((r) => !r.coverUrl).map((r) => r.id));
      if (emptyIds.size === 0) return;
      const candidates = (await this.repository.firstSongOfPlaylistsWithoutCover(userId)).filter(
        (c) => emptyIds.has(c.playlistId),
      );
      if (candidates.length === 0) return;
      const deadline = Date.now() + LIST_SYNC_BUDGET_MS;
      const deferred: CoverCandidate[] = [];
      for (const candidate of candidates) {
        if (Date.now() >= deadline) {
          deferred.push(candidate);
          continue;
        }
        const cover = await this.fetchAndStore(candidate);
        if (cover) {
          const record = records.find((r) => r.id === candidate.playlistId);
          if (record && !record.coverUrl) record.coverUrl = cover;
        }
      }
      if (deferred.length > 0) void this.fillInBackground(deferred);
    } catch (error) {
      // 兜底逻辑绝不能拖垮列表接口：任何异常都只留一条日志
      this.logger.warn(`列表封面回源中断：${messageOf(error)}`);
    }
  }

  /**
   * 详情兜底：给缺封面的曲目快照回源。预算内回填直接写进本次响应体，
   * 超预算的转后台补（只写库），用户下次打开或刷新即可见。
   */
  async fillForDetail(detail: PlaylistDetail): Promise<void> {
    try {
      const missing = detail.songs.filter((s) => !s.coverUrl);
      if (missing.length === 0) return;
      const deadline = Date.now() + DETAIL_SYNC_BUDGET_MS;
      const deferred: CoverCandidate[] = [];
      for (const song of missing) {
        const candidate = toCandidate(detail.id, song);
        if (Date.now() >= deadline) {
          deferred.push(candidate);
          continue;
        }
        const cover = await this.fetchAndStore(candidate);
        if (cover) song.coverUrl = cover;
      }
      if (deferred.length > 0) void this.fillInBackground(deferred);
    } catch (error) {
      this.logger.warn(`详情封面回源中断：${messageOf(error)}`);
    }
  }

  /** 后台补齐：不占响应时间，逐首排队回源，失败静默。 */
  private async fillInBackground(candidates: CoverCandidate[]): Promise<void> {
    for (const candidate of candidates) {
      await this.fetchAndStore(candidate);
    }
  }

  /**
   * 取一首歌的封面并回写快照（只写仍为空的行，天然幂等）。
   * 走串行管道保证限流；任何失败返回 null，不向调用方抛出。
   */
  private fetchAndStore(candidate: CoverCandidate): Promise<string | null> {
    const run = this.chain.then(() => this.doFetchAndStore(candidate));
    // 无论成败都垫一个限流间隔，链不会因单次失败而中断
    this.chain = run.then(
      () => sleep(UPSTREAM_THROTTLE_MS),
      () => sleep(UPSTREAM_THROTTLE_MS),
    );
    return run;
  }

  private async doFetchAndStore(candidate: CoverCandidate): Promise<string | null> {
    if (!isMusicSource(candidate.source)) return null;
    const key = songKeyOf(candidate);
    if (!key) return null;
    try {
      const info = await withTimeout(
        this.registry.of(candidate.source).requestSongInfo(key),
        UPSTREAM_TIMEOUT_MS,
      );
      const cover = (info.cover ?? "").trim();
      if (!cover) return null;
      await this.database.run(
        `UPDATE playlist_songs SET cover_url = $1
         WHERE playlist_id = $2 AND source = $3 AND song_id = $4 AND (cover_url IS NULL OR cover_url = '')`,
        [cover, candidate.playlistId, candidate.source, candidate.songId],
      );
      return cover;
    } catch (error) {
      // 上游波动/超时：留空等下次读取再试；warn 一条便于发现上游限流
      this.logger.warn(
        `封面回源失败：歌单 ${candidate.playlistId} · ${candidate.source}/${candidate.songId} —— ${messageOf(error)}`,
      );
      return null;
    }
  }
}

/** 由快照行构造上游歌曲身份，与回填工具 backfill-playlist-covers.ts 同口径。 */
function songKeyOf(row: Pick<CoverCandidate, "songId" | "mid" | "type">): SongKey | undefined {
  const key: SongKey = {};
  const numericId = Number(row.songId);
  if (Number.isInteger(numericId) && numericId > 0) key.id = numericId;
  // 数字 ID 直接用；mid-only 歌曲（酷我）song_id 本身就是 mid
  const mid = row.mid?.trim() || (Number.isInteger(numericId) && numericId > 0 ? "" : row.songId.trim());
  if (mid) key.mid = mid;
  if (row.type !== null && row.type !== undefined) key.type = row.type;
  if (!key.id && !key.mid) return undefined;
  return key;
}

function toCandidate(playlistId: number, song: PlaylistSongRecord): CoverCandidate {
  return { playlistId, source: song.source, songId: song.songId, mid: song.mid, type: song.type };
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 给上游探测加超时：同步路径不能被挂死的上游拖住响应。 */
function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`上游探测超过 ${ms}ms`)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
