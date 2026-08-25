import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

const HISTORY_THRESHOLD_MS = 3_000;
const DEFAULT_QUALIFIED_THRESHOLD_MS = 30_000;

export type PlaybackSessionInput = {
  sessionId: string;
  deviceId: string;
  source: string;
  songId: string;
  startedAt: number;
  lastPlayedAt: number;
  listenedMs: number;
  completed: boolean;
  durationSeconds: number | null;
};

export type PlaybackSessionRecord = {
  sessionId: string;
  source: string;
  songId: string;
  startedAt: number;
  lastPlayedAt: number;
  listenedMs: number;
  qualified: boolean;
  completed: boolean;
  updatedAt: number;
};

export type RecentPlaybackRecord = {
  source: string;
  songId: string;
  firstPlayedAt: number;
  lastPlayedAt: number;
  playCount: number;
  completedCount: number;
  totalListenedMs: number;
};

export type RecentPlaybackPage = {
  entries: RecentPlaybackRecord[];
  clearedBefore: number;
  revision: number;
};

type StoredSession = {
  device_id: string;
  source: string;
  song_id: string;
  started_at: number;
  last_played_at: number;
  listened_ms: number;
  qualified: number;
  completed: number;
  duration_seconds: number | null;
};

/** 同一个会话 ID 只能描述一台设备上的同一首歌。 */
export class PlaybackSessionIdentityError extends Error {}

@Injectable()
export class PlaybackRepository {
  constructor(private readonly database: DatabaseService) {}

  /**
   * 幂等接收播放会话累计快照，并在同一事务里更新按歌汇总。
   *
   * listenedMs 只能向前增长；重复或乱序快照产生的增量为 0。qualified/completed 也只允许
   * 从 0 变 1，因此网络重试不会把一次播放计算多次。
   */
  report(userId: number, input: PlaybackSessionInput): Promise<PlaybackSessionRecord> {
    return this.database.transaction(async (client) => {
      // 两个实例可能同时收到同一离线会话的重试。先按用户与会话 ID 取事务锁，避免双方都
      // 读到“尚不存在”后各自把完整 listenedMs 累加进汇总。
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
        `${userId}:${input.sessionId}`,
      ]);
      const existing = (
        await client.query<StoredSession>(
          `SELECT device_id, source, song_id, started_at, last_played_at, listened_ms,
                  qualified, completed, duration_seconds
           FROM playback_sessions
           WHERE user_id = $1 AND session_id = $2
           FOR UPDATE`,
          [userId, input.sessionId],
        )
      ).rows[0];

      if (
        existing &&
        (existing.device_id !== input.deviceId ||
          existing.source !== input.source ||
          existing.song_id !== input.songId ||
          existing.started_at !== input.startedAt)
      ) {
        throw new PlaybackSessionIdentityError("同一个 sessionId 不能用于不同歌曲或设备");
      }

      const oldListenedMs = existing?.listened_ms ?? 0;
      const listenedMs = Math.max(oldListenedMs, input.listenedMs);
      const durationSeconds = input.durationSeconds ?? existing?.duration_seconds ?? null;
      const qualifiedThresholdMs = durationSeconds
        ? Math.min(DEFAULT_QUALIFIED_THRESHOLD_MS, Math.ceil((durationSeconds * 1_000) / 2))
        : DEFAULT_QUALIFIED_THRESHOLD_MS;
      const qualified = (existing?.qualified ?? 0) === 1 || listenedMs >= qualifiedThresholdMs;
      const completed = (existing?.completed ?? 0) === 1 || input.completed;
      const lastPlayedAt = Math.max(existing?.last_played_at ?? 0, input.lastPlayedAt);
      const listenedDelta = listenedMs - oldListenedMs;
      const playCountDelta = qualified && (existing?.qualified ?? 0) === 0 ? 1 : 0;
      const completedCountDelta = completed && (existing?.completed ?? 0) === 0 ? 1 : 0;
      const now = Date.now();

      const session = (
        await client.query<PlaybackSessionRecord>(
          `INSERT INTO playback_sessions
             (user_id, session_id, device_id, source, song_id, started_at, last_played_at,
              listened_ms, qualified, completed, duration_seconds, created_at, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $12)
           ON CONFLICT (user_id, session_id) DO UPDATE SET
             last_played_at = excluded.last_played_at,
             listened_ms = excluded.listened_ms,
             qualified = excluded.qualified,
             completed = excluded.completed,
             duration_seconds = COALESCE(excluded.duration_seconds, playback_sessions.duration_seconds),
             updated_at = excluded.updated_at
           RETURNING session_id AS "sessionId", source, song_id AS "songId",
                     started_at AS "startedAt", last_played_at AS "lastPlayedAt",
                     listened_ms AS "listenedMs", (qualified = 1) AS qualified,
                     (completed = 1) AS completed, updated_at AS "updatedAt"`,
          [
            userId,
            input.sessionId,
            input.deviceId,
            input.source,
            input.songId,
            input.startedAt,
            lastPlayedAt,
            listenedMs,
            qualified ? 1 : 0,
            completed ? 1 : 0,
            durationSeconds,
            now,
          ],
        )
      ).rows[0];

      const lastHistoryAt = listenedMs >= HISTORY_THRESHOLD_MS ? lastPlayedAt : null;
      await client.query(
         `INSERT INTO user_song_stats
           (user_id, source, song_id, first_played_at, last_played_at, last_history_at,
            play_count, completed_count, total_listened_ms, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
         ON CONFLICT (user_id, source, song_id) DO UPDATE SET
           first_played_at = LEAST(user_song_stats.first_played_at, excluded.first_played_at),
           last_played_at = GREATEST(user_song_stats.last_played_at, excluded.last_played_at),
           last_history_at = CASE
             WHEN excluded.last_history_at IS NULL THEN user_song_stats.last_history_at
             WHEN user_song_stats.last_history_at IS NULL THEN excluded.last_history_at
             ELSE GREATEST(user_song_stats.last_history_at, excluded.last_history_at)
           END,
           play_count = user_song_stats.play_count + excluded.play_count,
           completed_count = user_song_stats.completed_count + excluded.completed_count,
           total_listened_ms = user_song_stats.total_listened_ms + excluded.total_listened_ms,
           updated_at = excluded.updated_at`,
        [
          userId,
          input.source,
          input.songId,
          input.startedAt,
          lastPlayedAt,
          lastHistoryAt,
          playCountDelta,
          completedCountDelta,
          listenedDelta,
          now,
        ],
      );

      return session;
    });
  }

  async listRecent(userId: number, limit: number): Promise<RecentPlaybackRecord[]> {
    const entries = await this.database.all<RecentPlaybackRecord>(
      `SELECT stats.source, stats.song_id AS "songId",
              stats.first_played_at AS "firstPlayedAt",
              stats.last_history_at AS "lastPlayedAt",
              stats.play_count AS "playCount",
              stats.completed_count AS "completedCount",
              stats.total_listened_ms AS "totalListenedMs"
       FROM user_song_stats stats
       LEFT JOIN playback_history_state state ON state.user_id = stats.user_id
       WHERE stats.user_id = $1
         AND stats.last_history_at IS NOT NULL
         AND stats.last_history_at > COALESCE(state.cleared_before, 0)
       ORDER BY stats.last_history_at DESC, stats.source, stats.song_id
       LIMIT $2`,
      [userId, limit],
    );
    return entries;
  }

  async historyState(userId: number): Promise<Omit<RecentPlaybackPage, "entries">> {
    const state = await this.database.first<{ clearedBefore: number; revision: number }>(
      `SELECT cleared_before AS "clearedBefore", revision
       FROM playback_history_state WHERE user_id = $1`,
      [userId],
    );
    return { clearedBefore: state?.clearedBefore ?? 0, revision: state?.revision ?? 0 };
  }

  async overallStats(userId: number) {
    return (
      await this.database.first<{
        songCount: number;
        playCount: number;
        completedCount: number;
        totalListenedMs: number;
        firstPlayedAt: number | null;
        lastPlayedAt: number | null;
      }>(
        `SELECT count(*)::integer AS "songCount",
                COALESCE(sum(play_count), 0)::integer AS "playCount",
                COALESCE(sum(completed_count), 0)::integer AS "completedCount",
                COALESCE(sum(total_listened_ms), 0)::bigint AS "totalListenedMs",
                min(first_played_at) AS "firstPlayedAt",
                max(last_played_at) AS "lastPlayedAt"
         FROM user_song_stats WHERE user_id = $1`,
        [userId],
      )
    )!;
  }

  async clearRecent(userId: number): Promise<{ clearedBefore: number; revision: number }> {
    const now = Date.now();
    return (
      await this.database.first<{ clearedBefore: number; revision: number }>(
        `INSERT INTO playback_history_state (user_id, cleared_before, revision, updated_at)
         VALUES ($1, $2, 1, $2)
         ON CONFLICT (user_id) DO UPDATE SET
           cleared_before = GREATEST(playback_history_state.cleared_before, excluded.cleared_before),
           revision = playback_history_state.revision + 1,
           updated_at = excluded.updated_at
         RETURNING cleared_before AS "clearedBefore", revision`,
        [userId, now],
      )
    )!;
  }
}
