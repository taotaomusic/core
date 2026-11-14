import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/**
 * 管理端短链行。
 *
 * SQL 里统一 alias 成 camelCase 下发（与 UserAdminRepository 同一套约定），
 * 管理前端直接消费，不再做一层字段名映射。token 是公开短码本身，不算敏感凭据；
 * `userName` 只回用户名，不带邮箱等个人数据面字段。
 */
export type AdminShareLinkRow = {
  token: string;
  userId: number;
  userName: string;
  source: string;
  songId: string;
  title: string;
  artist: string;
  album: string;
  enabled: number;
  accessCount: number;
  visitorCount: number;
  playCount: number;
  lastVisitedAt: number | null;
  lastOpenedAppAt: number | null;
  createdAt: number;
  updatedAt: number;
};

/** 列表页顶部的全站汇总。 */
export type AdminShareLinkSummary = {
  total: number;
  active: number;
  accessCount: number;
  visitorCount: number;
  playCount: number;
};

/**
 * 管理端分享短链查询与维护。
 *
 * 列表统计口径（与 wiki/35 号契约一致）：
 * - `access_count` = 公开元数据被读取的次数（既有字段）；
 * - `visitor_count` = 通过短链进入 APP 的去重访客数（song_share_visitor 按指纹去重）；
 * - `play_count` = 试听播放次数。
 */
@Injectable()
export class ShareAdminRepository {
  constructor(private readonly database: DatabaseService) {}

  /** 关键词匹配：短码 / 歌名 / 歌手 / 分享者用户名。 */
  async list(
    keyword: string,
    limit: number,
    offset: number,
  ): Promise<{ items: AdminShareLinkRow[]; total: number }> {
    const pattern = `%${keyword}%`;
    const where = `
      WHERE $1 = ''
         OR song_share.token ILIKE $2
         OR song_share.title ILIKE $2
         OR song_share.artist ILIKE $2
         OR users.username ILIKE $2`;
    const count = await this.database.first<{ total: number }>(
      `SELECT count(*)::integer AS total
       FROM song_share JOIN users ON users.id = song_share.user_id ${where}`,
      [keyword, pattern],
    );
    const items = await this.database.all<AdminShareLinkRow>(
      `SELECT song_share.token,
              song_share.user_id AS "userId",
              users.username AS "userName",
              song_share.source,
              song_share.song_id AS "songId",
              song_share.title,
              song_share.artist,
              song_share.album,
              song_share.enabled,
              song_share.access_count AS "accessCount",
              song_share.visitor_count AS "visitorCount",
              song_share.play_count AS "playCount",
              song_share.last_visited_at AS "lastVisitedAt",
              song_share.last_opened_app_at AS "lastOpenedAppAt",
              song_share.created_at AS "createdAt",
              song_share.updated_at AS "updatedAt"
       FROM song_share JOIN users ON users.id = song_share.user_id
       ${where}
       ORDER BY song_share.created_at DESC
       LIMIT $3 OFFSET $4`,
      [keyword, pattern, limit, offset],
    );
    return { items, total: count?.total ?? 0 };
  }

  async summary(): Promise<AdminShareLinkSummary> {
    const row = await this.database.first<{
      total: number;
      active: number;
      access_count: number;
      visitor_count: number;
      play_count: number;
    }>(
      `SELECT count(*)::integer AS total,
              count(*) FILTER (WHERE enabled = 1)::integer AS active,
              COALESCE(sum(access_count), 0)::integer AS access_count,
              COALESCE(sum(visitor_count), 0)::integer AS visitor_count,
              COALESCE(sum(play_count), 0)::integer AS play_count
       FROM song_share`,
    );
    return {
      total: row?.total ?? 0,
      active: row?.active ?? 0,
      accessCount: row?.access_count ?? 0,
      visitorCount: row?.visitor_count ?? 0,
      playCount: row?.play_count ?? 0,
    };
  }

  /** 永久删除一条短链；访客去重行随外键级联清理。 */
  async remove(token: string): Promise<boolean> {
    return (await this.database.run("DELETE FROM song_share WHERE token = $1", [token])) === 1;
  }

  /** 隐藏（软删除）一条短链；保留统计数据用于事后核查。 */
  async setEnabled(token: string, enabled: boolean): Promise<boolean> {
    return (
      (await this.database.run(
        "UPDATE song_share SET enabled = $2, updated_at = $3 WHERE token = $1",
        [token, enabled ? 1 : 0, Date.now()],
      )) === 1
    );
  }

  /**
   * 清除「超过阈值天数没有任何访问」的启用短链。
   *
   * 判据是 `last_visited_at`（元数据 / 打点都会刷新它）而不是 `created_at`：
   * 一条分享出去后长期没人点的链接才算「长时间无用」，刚创建但还没被打开的
   * 链接 `last_visited_at` 为 NULL，不会被这次清理误伤。
   * 返回被删除的短码列表，供审计与前端提示。
   */
  async purgeExpired(expireDays: number, now: number): Promise<{ token: string }[]> {
    if (expireDays <= 0) return [];
    const threshold = now - expireDays * 86_400_000;
    return this.database.all<{ token: string }>(
      `DELETE FROM song_share
       WHERE enabled = 1
         AND last_visited_at IS NOT NULL
         AND last_visited_at < $1
       RETURNING token`,
      [threshold],
    );
  }
}
