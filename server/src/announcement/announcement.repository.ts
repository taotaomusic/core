import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/** 公告对外字段。`enabled` 保持 0/1，与发布管理的数据形状一致。 */
export type Announcement = {
  id: number;
  title: string;
  content: string;
  enabled: number;
  pinned: number;
  published_at: number;
  updated_at: number;
};

@Injectable()
export class AnnouncementRepository {
  constructor(private readonly database: DatabaseService) {}

  listVisible(limit = 20): Promise<Announcement[]> {
    return this.database.all<Announcement>(
      `SELECT id, title, content, enabled, pinned, published_at, updated_at
       FROM app_announcement WHERE enabled = 1 ORDER BY pinned DESC, published_at DESC LIMIT $1`,
      [limit],
    );
  }

  listAll(): Promise<Announcement[]> {
    return this.database.all<Announcement>(
      "SELECT id, title, content, enabled, pinned, published_at, updated_at FROM app_announcement ORDER BY pinned DESC, published_at DESC",
    );
  }

  async create(title: string, content: string): Promise<Announcement> {
    const now = Date.now();
    return (await this.database.first<Announcement>(
      `INSERT INTO app_announcement (title, content, published_at, updated_at)
       VALUES ($1, $2, $3, $3)
       RETURNING id, title, content, enabled, pinned, published_at, updated_at`,
      [title, content, now],
    ))!;
  }

  update(id: number, title: string | undefined, content: string | undefined): Promise<Announcement | undefined> {
    return this.database.first<Announcement>(
      `UPDATE app_announcement
       SET title = CASE WHEN $2::boolean THEN $3 ELSE title END,
           content = CASE WHEN $4::boolean THEN $5 ELSE content END,
           updated_at = $6
       WHERE id = $1
       RETURNING id, title, content, enabled, pinned, published_at, updated_at`,
      [id, title !== undefined, title ?? null, content !== undefined, content ?? null, Date.now()],
    );
  }

  async setEnabled(id: number, enabled: boolean): Promise<Announcement | undefined> {
    return this.database.first<Announcement>(
      `UPDATE app_announcement SET enabled = $2, updated_at = $3 WHERE id = $1
       RETURNING id, title, content, enabled, pinned, published_at, updated_at`,
      [id, enabled ? 1 : 0, Date.now()],
    );
  }

  /** 置顶切换在一个事务里完成，保证同一时刻至多一条公告处于置顶状态。 */
  async setPinned(id: number, pinned: boolean): Promise<Announcement | undefined> {
    return this.database.transaction(async (client) => {
      // 事务级顾问锁把并发点击不同公告的置顶操作串行化，避免最终出现两个置顶。
      await client.query("SELECT pg_advisory_xact_lock($1)", [913_720_002]);
      const exists = await client.query("SELECT 1 FROM app_announcement WHERE id = $1", [id]);
      if (exists.rowCount !== 1) return undefined;
      if (pinned) await client.query("UPDATE app_announcement SET pinned = 0 WHERE pinned = 1");
      const result = await client.query<Announcement>(
        `UPDATE app_announcement SET pinned = $2, updated_at = $3 WHERE id = $1
         RETURNING id, title, content, enabled, pinned, published_at, updated_at`,
        [id, pinned ? 1 : 0, Date.now()],
      );
      return result.rows[0];
    });
  }

  async remove(id: number): Promise<boolean> {
    return (await this.database.run("DELETE FROM app_announcement WHERE id = $1", [id])) === 1;
  }
}
