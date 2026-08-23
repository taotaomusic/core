import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/** 收藏记录。`songId` 在库里是 text，序列化后必须仍是 JSON 字符串 —— 客户端按字符串比较。 */
export type FavoriteRecord = { source: string; songId: string; createdAt: number };

/**
 * 别名必须加双引号。
 *
 * PostgreSQL 会把不加引号的标识符折叠成小写，`AS songId` 得到的字段是 `songid`，
 * 客户端读 `songId` 拿到 undefined 后**所有歌都显示未收藏**，而且没有任何报错。
 */
const COLUMNS = `source, song_id AS "songId", created_at AS "createdAt"`;

@Injectable()
export class FavoritesRepository {
  constructor(private readonly database: DatabaseService) {}

  /**
   * 加收藏。一条语句完成"没有就插入、已有就取回"。
   *
   * `ON CONFLICT DO NOTHING` 不会触发 RETURNING，而客户端要拿到已存在的那条记录，
   * 所以用一次空更新（把 source 写回它自己）让 RETURNING 照样出行。
   */
  add(userId: number, source: string, songId: string): Promise<FavoriteRecord | undefined> {
    return this.database.first<FavoriteRecord>(
      `INSERT INTO favorites (user_id, source, song_id, created_at) VALUES ($1, $2, $3, $4)
       ON CONFLICT (user_id, source, song_id) DO UPDATE SET source = excluded.source
       RETURNING ${COLUMNS}`,
      [userId, source, songId, Date.now()],
    );
  }

  async remove(userId: number, source: string, songId: string): Promise<boolean> {
    const affected = await this.database.run(
      "DELETE FROM favorites WHERE user_id = $1 AND source = $2 AND song_id = $3",
      [userId, source, songId],
    );
    return affected > 0;
  }

  list(userId: number): Promise<FavoriteRecord[]> {
    return this.database.all<FavoriteRecord>(
      `SELECT ${COLUMNS} FROM favorites WHERE user_id = $1 ORDER BY created_at DESC`,
      [userId],
    );
  }
}
