import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

export type AdminUserSummary = {
  id: number;
  username: string;
  nickname: string;
  email: string | null;
  avatarUrl: string | null;
  createdAt: string;
  disabledAt: number | null;
  songCount: number;
  playCount: number;
  completedCount: number;
  totalListenedMs: number;
  lastPlayedAt: number | null;
};

export type AdminPlaybackEntry = {
  source: string;
  songId: string;
  firstPlayedAt: number;
  lastPlayedAt: number;
  playCount: number;
  completedCount: number;
  totalListenedMs: number;
};

export type AdminPlaybackStats = Pick<
  AdminUserSummary,
  "songCount" | "playCount" | "completedCount" | "totalListenedMs" | "lastPlayedAt"
> & {
  firstPlayedAt: number | null;
};

/**
 * 管理端用户只读查询。
 *
 * 统计从 user_song_stats 读取，而不是扫描 playback_sessions；后者是一首歌一次会话的
 * 原始流水，既慢也会让同一数据在后台与客户端的口径不一致。
 */
@Injectable()
export class UserAdminRepository {
  constructor(private readonly database: DatabaseService) {}

  async list(query: string, limit: number, offset: number): Promise<{ items: AdminUserSummary[]; total: number }> {
    const keyword = query.trim();
    const pattern = `%${keyword}%`;
    const where = `
      WHERE $1 = ''
         OR users.username ILIKE $2
         OR COALESCE(users.nickname, '') ILIKE $2
         OR COALESCE(users.email, '') ILIKE $2`;
    const count = await this.database.first<{ total: number }>(
      `SELECT count(*)::integer AS total FROM users ${where}`,
      [keyword, pattern],
    );
    const items = await this.database.all<AdminUserSummary>(
      `SELECT users.id,
              users.username,
              COALESCE(users.nickname, users.username) AS nickname,
              users.email,
              users.avatar_url AS "avatarUrl",
              to_char(users.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS "createdAt",
              users.disabled_at AS "disabledAt",
              COALESCE(stats.song_count, 0)::integer AS "songCount",
              COALESCE(stats.play_count, 0)::integer AS "playCount",
              COALESCE(stats.completed_count, 0)::integer AS "completedCount",
              COALESCE(stats.total_listened_ms, 0)::bigint AS "totalListenedMs",
              stats.last_played_at AS "lastPlayedAt"
       FROM users
       LEFT JOIN LATERAL (
         SELECT count(*)::integer AS song_count,
                COALESCE(sum(play_count), 0)::integer AS play_count,
                COALESCE(sum(completed_count), 0)::integer AS completed_count,
                COALESCE(sum(total_listened_ms), 0)::bigint AS total_listened_ms,
                max(last_played_at) AS last_played_at
         FROM user_song_stats
         WHERE user_id = users.id
       ) stats ON true
       ${where}
       ORDER BY users.id DESC
       LIMIT $3 OFFSET $4`,
      [keyword, pattern, limit, offset],
    );
    return { items, total: count?.total ?? 0 };
  }

  async detail(userId: number, limit: number): Promise<{
    user: Omit<AdminUserSummary, "songCount" | "playCount" | "completedCount" | "totalListenedMs" | "lastPlayedAt">;
    stats: AdminPlaybackStats;
    history: { clearedBefore: number; revision: number; entries: AdminPlaybackEntry[] };
  } | undefined> {
    const user = await this.database.first<{
      id: number;
      username: string;
      nickname: string;
      email: string | null;
      avatarUrl: string | null;
      createdAt: string;
      disabledAt: number | null;
    }>(
      `SELECT id, username, COALESCE(nickname, username) AS nickname, email,
              avatar_url AS "avatarUrl", disabled_at AS "disabledAt",
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS "createdAt"
       FROM users WHERE id = $1`,
      [userId],
    );
    if (!user) return undefined;

    const stats = (
      await this.database.first<AdminPlaybackStats>(
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
    const state = await this.database.first<{ clearedBefore: number; clearedAt: number; revision: number }>(
      `SELECT cleared_before AS "clearedBefore", updated_at AS "clearedAt", revision
       FROM playback_history_state WHERE user_id = $1`,
      [userId],
    );
    const entries = await this.database.all<AdminPlaybackEntry>(
      `SELECT stats.source, stats.song_id AS "songId",
              stats.first_played_at AS "firstPlayedAt", stats.last_history_at AS "lastPlayedAt",
              stats.play_count AS "playCount", stats.completed_count AS "completedCount",
              stats.total_listened_ms AS "totalListenedMs"
       FROM user_song_stats stats
       LEFT JOIN playback_history_state state ON state.user_id = stats.user_id
       WHERE stats.user_id = $1
         AND stats.last_history_at IS NOT NULL
         -- revision 是清空的权威顺序；不能用客户端墙钟与 cleared_before 判断可见性。
         AND stats.last_history_revision >= COALESCE(state.revision, 0)
       ORDER BY stats.last_history_at DESC, stats.source, stats.song_id
       LIMIT $2`,
      [userId, limit],
    );
    return {
      user,
      stats,
      history: { clearedBefore: state?.clearedBefore ?? 0, revision: state?.revision ?? 0, entries },
    };
  }

  /**
   * 禁用时一并撤销该账号全部刷新令牌。访问令牌仍可能在有效期内存在于客户端，
   * 但每次请求都会经过 users.findById 的 disabled_at 判断，所以会立刻收到 401。
   */
  async setDisabled(userId: number, disabled: boolean): Promise<{ disabledAt: number | null } | undefined> {
    return this.database.transaction(async (client) => {
      const disabledAt = disabled ? Date.now() : null;
      const result = await client.query<{ disabledAt: number | null }>(
        `UPDATE users SET disabled_at = $2 WHERE id = $1
         RETURNING disabled_at AS "disabledAt"`,
        [userId, disabledAt],
      );
      const user = result.rows[0];
      if (user && disabled) {
        await client.query(
          "UPDATE refresh_tokens SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL",
          [userId, disabledAt],
        );
      }
      return user;
    });
  }

  /** 永久删除账号；数据库外键级联删除它的令牌、收藏、播放会话与听歌统计。 */
  async delete(userId: number): Promise<boolean> {
    return (await this.database.run("DELETE FROM users WHERE id = $1", [userId])) === 1;
  }
}
