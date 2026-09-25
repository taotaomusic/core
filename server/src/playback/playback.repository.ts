import { Injectable } from "@nestjs/common";
import type { PoolClient } from "pg";
import { DatabaseService } from "../database/database.service";

const HISTORY_THRESHOLD_MS = 3_000;
const DEFAULT_QUALIFIED_THRESHOLD_MS = 30_000;

export type PlaybackSessionInput = {
  sessionId: string;
  deviceId: string;
  source: string;
  songId: string;
  /** 客户端创建会话时已知的最近播放清空版本；旧客户端缺失时为 0。 */
  historyRevision: number;
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
  /** 此会话首次落库时固化的可见历史版本，不能被后续重试抬高。 */
  historyRevision: number;
  /** 服务端当前清空版本，客户端可据此在下一会话前刷新本地状态。 */
  currentHistoryRevision: number;
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

/** 单曲倒带日记里的一条历史播放会话。 */
export type SongDiaryRecord = {
  startedAt: number;
  lastPlayedAt: number;
  listenedMs: number;
  completed: boolean;
};

/** 单曲倒带日记：针对当前用户某一首歌的完整播放画像。 */
export type SongDiary = {
  source: string;
  songId: string;
  /** 首次邂逅；从未播放过为 null。 */
  firstPlayedAt: number | null;
  /** 上次收听（全量，不受清空最近播放影响）；从未播放过为 null。 */
  lastPlayedAt: number | null;
  /** 全时段合格播放次数。 */
  playCount: number;
  completedCount: number;
  totalListenedMs: number;
  /** 近一年（365 天）合格播放次数。 */
  playsLastYear: number;
  /** 近半年（180 天）合格播放次数。 */
  playsLastHalfYear: number;
  /** 狂热循环：单日播放最多的那一天；从未播放过为 null。 */
  peakDay: { atMillis: number; count: number } | null;
  /** 近 6 个自然年（含今年）的逐年合格播放次数，用于折线趋势。 */
  yearly: { year: number; count: number }[];
  /** 最近 180 天的逐日合格播放次数，从最早到今天，用于点阵热力图。 */
  dailyCounts: number[];
  /** 最近的播放记录明细（倒序）。 */
  records: SongDiaryRecord[];
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
  history_revision: number;
};

export type PlaybackHistoryState = {
  /** 兼容旧客户端的时间字段；新同步逻辑不得再以它决定可见性。 */
  clearedBefore: number;
  /** 本次清空由服务端接收请求时写入的时间，仅用于诊断与展示。 */
  clearedAt: number;
  /** 最近播放的权威代际。会话必须属于当前或更高代际才可见。 */
  revision: number;
  /** 最近一次清空请求的幂等标识；客户端可据此确认重试没有重复推进版本。 */
  marker: string | null;
};

/** 同一个会话 ID 只能描述一台设备上的同一首歌。 */
export class PlaybackSessionIdentityError extends Error {}

/** 客户端声称的版本比服务端已知版本还新，必须先重新拉取状态。 */
export class PlaybackHistoryRevisionError extends Error {}

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
      // 清空与会话上报必须按同一个账号的历史锁串行。否则清空刚推进 revision 时，另一个
      // 事务仍可能把会话按旧 revision 写成可见，造成跨设备清空后历史复活。
      await this.lockHistoryState(client, userId);
      const historyState = await this.historyStateForUpdate(client, userId);
      if (input.historyRevision > historyState.revision) {
        throw new PlaybackHistoryRevisionError("最近播放版本超前，请先同步状态");
      }

      // 两个实例可能同时收到同一离线会话的重试。先按用户与会话 ID 取事务锁，避免双方都
      // 读到“尚不存在”后各自把完整 listenedMs 累加进汇总。
      await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
        `${userId}:${input.sessionId}`,
      ]);
      const existing = (
        await client.query<StoredSession>(
          `SELECT device_id, source, song_id, started_at, last_played_at, listened_ms,
                  qualified, completed, duration_seconds, history_revision
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
      // 同一会话的历史代际只能在第一次写入时决定。若旧设备在另一台设备清空后补传，
      // 它仍要累计听歌统计，但绝不能因为之后拉到了新 revision 就把旧会话复活到列表。
      const historyRevision = existing?.history_revision ?? input.historyRevision;
      const now = Date.now();

      const session = (
        await client.query<PlaybackSessionRecord>(
          `INSERT INTO playback_sessions
           (user_id, session_id, device_id, source, song_id, started_at, last_played_at,
              listened_ms, qualified, completed, duration_seconds, history_revision, created_at, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $13)
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
                     (completed = 1) AS completed, history_revision AS "historyRevision",
                     updated_at AS "updatedAt"`,
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
            historyRevision,
            now,
          ],
        )
      ).rows[0];

      const lastHistoryAt = listenedMs >= HISTORY_THRESHOLD_MS ? lastPlayedAt : null;
      await client.query(
         `INSERT INTO user_song_stats
           (user_id, source, song_id, first_played_at, last_played_at, last_history_at,
            last_history_revision, play_count, completed_count, total_listened_ms, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
         ON CONFLICT (user_id, source, song_id) DO UPDATE SET
           first_played_at = LEAST(user_song_stats.first_played_at, excluded.first_played_at),
           last_played_at = GREATEST(user_song_stats.last_played_at, excluded.last_played_at),
           last_history_at = CASE
             WHEN excluded.last_history_at IS NULL THEN user_song_stats.last_history_at
             WHEN excluded.last_history_revision > user_song_stats.last_history_revision
               THEN excluded.last_history_at
             WHEN excluded.last_history_revision = user_song_stats.last_history_revision
               THEN CASE
                 WHEN user_song_stats.last_history_at IS NULL THEN excluded.last_history_at
                 ELSE GREATEST(user_song_stats.last_history_at, excluded.last_history_at)
               END
             ELSE user_song_stats.last_history_at
           END,
           last_history_revision = CASE
             WHEN excluded.last_history_at IS NULL THEN user_song_stats.last_history_revision
             ELSE GREATEST(user_song_stats.last_history_revision, excluded.last_history_revision)
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
          historyRevision,
          playCountDelta,
          completedCountDelta,
          listenedDelta,
          now,
        ],
      );

      return { ...session, currentHistoryRevision: historyState.revision };
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
         AND stats.last_history_revision >= COALESCE(state.revision, 0)
       ORDER BY stats.last_history_at DESC, stats.source, stats.song_id
       LIMIT $2`,
      [userId, limit],
    );
    return entries;
  }

  async historyState(userId: number): Promise<PlaybackHistoryState> {
    const state = await this.database.first<PlaybackHistoryState>(
      `SELECT cleared_before AS "clearedBefore", updated_at AS "clearedAt", revision,
              clear_marker AS marker
       FROM playback_history_state WHERE user_id = $1`,
      [userId],
    );
    return {
      clearedBefore: state?.clearedBefore ?? 0,
      clearedAt: state?.clearedAt ?? 0,
      revision: state?.revision ?? 0,
      marker: state?.marker ?? null,
    };
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

  /**
   * 单曲倒带日记：聚合基础统计来自 user_song_stats，时间窗口计数与单日峰值现算自
   * playback_sessions 会话流水，播放记录取最近若干条会话明细。
   */
  async diary(userId: number, source: string, songId: string): Promise<SongDiary> {
    const DAY_MS = 86_400_000;
    const now = Date.now();
    const yearCutoff = now - 365 * DAY_MS;
    const halfYearCutoff = now - 180 * DAY_MS;

    const stats = await this.database.first<{
      firstPlayedAt: number;
      lastPlayedAt: number;
      playCount: number;
      completedCount: number;
      totalListenedMs: number;
    }>(
      `SELECT first_played_at AS "firstPlayedAt",
              last_played_at AS "lastPlayedAt",
              play_count AS "playCount",
              completed_count AS "completedCount",
              total_listened_ms AS "totalListenedMs"
       FROM user_song_stats
       WHERE user_id = $1 AND source = $2 AND song_id = $3`,
      [userId, source, songId],
    );

    // 时间窗口计数只统计合格会话，与 user_song_stats.play_count 的口径一致。
    const windows = (await this.database.first<{
      playsLastYear: number;
      playsLastHalfYear: number;
    }>(
      `SELECT COALESCE(sum(CASE WHEN started_at >= $4 THEN 1 ELSE 0 END), 0)::integer AS "playsLastYear",
              COALESCE(sum(CASE WHEN started_at >= $5 THEN 1 ELSE 0 END), 0)::integer AS "playsLastHalfYear"
       FROM playback_sessions
       WHERE user_id = $1 AND source = $2 AND song_id = $3 AND qualified = 1`,
      [userId, source, songId, yearCutoff, halfYearCutoff],
    ))!;

    // 狂热循环：按 UTC 天分桶取合格播放最多的那一天。
    const peak = await this.database.first<{ dayIndex: number; plays: number }>(
      `SELECT floor(started_at / 86400000)::bigint AS "dayIndex", count(*)::integer AS "plays"
       FROM playback_sessions
       WHERE user_id = $1 AND source = $2 AND song_id = $3 AND qualified = 1
       GROUP BY floor(started_at / 86400000)
       ORDER BY "plays" DESC, "dayIndex" DESC
       LIMIT 1`,
      [userId, source, songId],
    );

    const records = await this.database.all<SongDiaryRecord>(
      `SELECT started_at AS "startedAt", last_played_at AS "lastPlayedAt",
              listened_ms AS "listenedMs", (completed = 1) AS "completed"
       FROM playback_sessions
       WHERE user_id = $1 AND source = $2 AND song_id = $3
       ORDER BY started_at DESC
       LIMIT 50`,
      [userId, source, songId],
    );

    // 近年折线：按自然年分桶合格播放，随后在 JS 侧补齐最近 6 个年份的零值。
    const yearlyRows = await this.database.all<{ year: number; count: number }>(
      `SELECT date_part('year', to_timestamp(started_at / 1000.0))::integer AS "year",
              count(*)::integer AS "count"
       FROM playback_sessions
       WHERE user_id = $1 AND source = $2 AND song_id = $3 AND qualified = 1
       GROUP BY 1`,
      [userId, source, songId],
    );
    const yearlyMap = new Map(yearlyRows.map((row) => [row.year, row.count]));
    const currentYear = new Date(now).getFullYear();
    const yearly = Array.from({ length: 6 }, (_, index) => {
      const year = currentYear - 5 + index;
      return { year, count: yearlyMap.get(year) ?? 0 };
    });

    // 近半年点阵：按天分桶合格播放，补齐最近 180 天（从最早到今天）的零值。
    const HEATMAP_DAYS = 180;
    const todayIndex = Math.floor(now / DAY_MS);
    const heatmapStart = (todayIndex - (HEATMAP_DAYS - 1)) * DAY_MS;
    const dailyRows = await this.database.all<{ dayIndex: number; count: number }>(
      `SELECT floor(started_at / 86400000)::bigint AS "dayIndex", count(*)::integer AS "count"
       FROM playback_sessions
       WHERE user_id = $1 AND source = $2 AND song_id = $3 AND qualified = 1
         AND started_at >= $4
       GROUP BY 1`,
      [userId, source, songId, heatmapStart],
    );
    const dailyMap = new Map(dailyRows.map((row) => [Number(row.dayIndex), row.count]));
    const firstDayIndex = todayIndex - (HEATMAP_DAYS - 1);
    const dailyCounts = Array.from({ length: HEATMAP_DAYS }, (_, index) =>
      dailyMap.get(firstDayIndex + index) ?? 0,
    );

    return {
      source,
      songId,
      firstPlayedAt: stats?.firstPlayedAt ?? null,
      lastPlayedAt: stats?.lastPlayedAt ?? null,
      playCount: stats?.playCount ?? 0,
      completedCount: stats?.completedCount ?? 0,
      totalListenedMs: stats?.totalListenedMs ?? 0,
      playsLastYear: windows.playsLastYear,
      playsLastHalfYear: windows.playsLastHalfYear,
      peakDay: peak ? { atMillis: Number(peak.dayIndex) * DAY_MS, count: peak.plays } : null,
      yearly,
      dailyCounts,
      records,
    };
  }

  /**
   * 清空最近播放。
   *
   * marker 由客户端在发起清空时生成并持久化。网络在服务端提交后、响应到达前中断时，
   * 重试携带同一个 marker 会直接返回原状态，避免清空版本被意外推进两次。
   */
  async clearRecent(userId: number, marker?: string): Promise<PlaybackHistoryState> {
    return this.database.transaction(async (client) => {
      // 与 report 共用历史锁：revision 是清空的唯一权威顺序，不允许由客户端时间决定。
      await this.lockHistoryState(client, userId);
      if (marker) {
        // 不能只比较 history_state 里“最近一次” marker：另一台设备若已经再次清空，
        // 原设备的响应丢失重试仍必须返回最初那次清空，而不是推进第三个 revision。
        const previous = (
          await client.query<PlaybackHistoryState>(
            `SELECT cleared_before AS "clearedBefore", cleared_at AS "clearedAt", revision, marker
             FROM playback_history_clear_operation
             WHERE user_id = $1 AND marker = $2`,
            [userId, marker],
          )
        ).rows[0];
        if (previous) return previous;
      }
      const now = Date.now();
      const state = (
        await client.query<PlaybackHistoryState>(
          `INSERT INTO playback_history_state (user_id, cleared_before, revision, updated_at, clear_marker)
           VALUES ($1, $2, 1, $2, $3)
           ON CONFLICT (user_id) DO UPDATE SET
             -- 保留时间字段只是给旧客户端和后台展示；recent 查询不再使用它解决冲突。
             cleared_before = GREATEST(playback_history_state.cleared_before, excluded.cleared_before),
             revision = playback_history_state.revision + 1,
             updated_at = excluded.updated_at,
             clear_marker = excluded.clear_marker
           RETURNING cleared_before AS "clearedBefore", updated_at AS "clearedAt", revision,
                     clear_marker AS marker`,
          [userId, now, marker ?? null],
        )
      ).rows[0];
      if (!marker) return state;

      await client.query(
        `INSERT INTO playback_history_clear_operation
           (user_id, marker, revision, cleared_before, cleared_at)
         VALUES ($1, $2, $3, $4, $5)`,
        [userId, marker, state.revision, state.clearedBefore, state.clearedAt],
      );
      return state;
    });
  }

  private async lockHistoryState(client: PoolClient, userId: number) {
    await client.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [
      `${userId}:playback-history`,
    ]);
  }

  private async historyStateForUpdate(
    client: PoolClient,
    userId: number,
  ): Promise<PlaybackHistoryState> {
    const state = (
      await client.query<PlaybackHistoryState>(
        `SELECT cleared_before AS "clearedBefore", updated_at AS "clearedAt", revision,
                clear_marker AS marker
         FROM playback_history_state
         WHERE user_id = $1
         FOR UPDATE`,
        [userId],
      )
    ).rows[0];
    return {
      clearedBefore: state?.clearedBefore ?? 0,
      clearedAt: state?.clearedAt ?? 0,
      revision: state?.revision ?? 0,
      marker: state?.marker ?? null,
    };
  }
}
