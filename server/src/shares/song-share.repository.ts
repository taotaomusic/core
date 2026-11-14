import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";
import type { MusicSource } from "../upstream/music-source.client";

export type SongShareRecord = {
  token: string;
  user_id: number;
  source: MusicSource;
  song_id: string;
  remote_id: string | null;
  mid: string | null;
  song_type: number | null;
  title: string;
  artist: string;
  album: string;
  cover_url: string | null;
  duration_seconds: number;
  refrain_start_ms: number | null;
  refrain_end_ms: number | null;
  vip: number;
  enabled: number;
  access_count: number;
  /** 通过短链进入 APP 的去重访客数（song_share_visitor 按指纹去重后累加）。 */
  visitor_count: number;
  /** 试听播放次数（分享页每次开始播放计一次）。 */
  play_count: number;
  /** 最近一次公开元数据访问时间；从未被打开过为 NULL。 */
  last_visited_at: number | null;
  /** 最近一次「进入 APP」打点时间；从未打过为 NULL。 */
  last_opened_app_at: number | null;
  created_at: number;
  updated_at: number;
};

export type SongShareSnapshot = Pick<
  SongShareRecord,
  "source" | "song_id" | "remote_id" | "mid" | "song_type" | "title" | "artist" | "album" | "cover_url" | "duration_seconds" | "refrain_start_ms" | "refrain_end_ms" | "vip"
>;

@Injectable()
export class SongShareRepository {
  constructor(private readonly database: DatabaseService) {}

  upsert(userId: number, token: string, song: SongShareSnapshot): Promise<SongShareRecord> {
    const now = Date.now();
    return this.database.transaction(async (client) => {
      const result = await client.query<SongShareRecord>(
        `INSERT INTO song_share
           (token, user_id, source, song_id, remote_id, mid, song_type, title, artist, album,
            cover_url, duration_seconds, refrain_start_ms, refrain_end_ms, vip, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $16)
         ON CONFLICT (user_id, source, song_id) DO UPDATE SET
           remote_id = excluded.remote_id,
           mid = excluded.mid,
           song_type = excluded.song_type,
           title = excluded.title,
           artist = excluded.artist,
           album = excluded.album,
           cover_url = excluded.cover_url,
           duration_seconds = excluded.duration_seconds,
           refrain_start_ms = excluded.refrain_start_ms,
           refrain_end_ms = excluded.refrain_end_ms,
           vip = excluded.vip,
           enabled = 1,
           updated_at = excluded.updated_at
         RETURNING *`,
        [
          token,
          userId,
          song.source,
          song.song_id,
          song.remote_id,
          song.mid,
          song.song_type,
          song.title,
          song.artist,
          song.album,
          song.cover_url,
          song.duration_seconds,
          song.refrain_start_ms,
          song.refrain_end_ms,
          song.vip,
          now,
        ],
      );
      const record = result.rows[0];
      if (!record) throw new Error("歌曲分享记录写入失败");
      return record;
    });
  }

  /**
   * 按 token 取分享记录。
   *
   * `options` 透传给 DatabaseService：分享页是匿名消费、没有用户上下文，
   * 写后立读窗口覆盖不到「刚创建就打开」的场景，服务层在副本未命中时会用
   * `{ primary: true }` 回主库再确认一次（见 SongShareService.requiredShare）。
   */
  find(token: string, options: { primary?: boolean } = {}): Promise<SongShareRecord | undefined> {
    return this.database.first<SongShareRecord>(
      "SELECT * FROM song_share WHERE token = $1 AND enabled = 1",
      [token],
      options,
    );
  }

  async noteAccess(token: string): Promise<void> {
    await this.database.run(
      "UPDATE song_share SET access_count = access_count + 1, last_visited_at = $2 WHERE token = $1 AND enabled = 1",
      [token, Date.now()],
      // 访问计数是展示用途，但语句本身是写 —— 读上下文里必须显式 pin 主库。
      { primary: true },
    );
  }

  /**
   * 记一次「通过短链进入 APP」。
   *
   * 访客去重落在 song_share_visitor：同一 token + 指纹哈希只算一个人。
   * ON CONFLICT DO NOTHING 的返回行数为 0 表示是老访客，两步合成一条 SQL
   * 避免「先查再插」的并发双计窗口；语句是写，读上下文里必须显式 pin 主库。
   * 失败由调用方吞掉（打点是锦上添花，不能让分享页报错）。
   */
  async noteAppOpen(token: string, visitorHash: string): Promise<void> {
    await this.database.run(
      `WITH inserted AS (
         INSERT INTO song_share_visitor (token, visitor_hash, first_seen_at)
         VALUES ($1, $2, $3)
         ON CONFLICT (token, visitor_hash) DO NOTHING
         RETURNING token
       )
       UPDATE song_share
       SET visitor_count = visitor_count + 1,
           last_opened_app_at = $3,
           last_visited_at = $3
       WHERE token = $1
         AND enabled = 1
         AND EXISTS (SELECT 1 FROM inserted)`,
      [token, visitorHash, Date.now()],
      { primary: true },
    );
  }

  /** 记一次试听播放。计数是展示用途，但语句本身是写 —— 读上下文里必须显式 pin 主库。 */
  async notePlay(token: string): Promise<void> {
    await this.database.run(
      `UPDATE song_share
       SET play_count = play_count + 1, last_visited_at = $2
       WHERE token = $1 AND enabled = 1`,
      [token, Date.now()],
      { primary: true },
    );
  }

  /** 指纹哈希是否已是该短链记录过的访客（去重判定用，幂等打点时不再重复计数）。 */
  async hasVisitor(token: string, visitorHash: string): Promise<boolean> {
    const row = await this.database.first<{ seen: number }>(
      "SELECT 1 AS seen FROM song_share_visitor WHERE token = $1 AND visitor_hash = $2",
      [token, visitorHash],
    );
    return row !== undefined;
  }
}
