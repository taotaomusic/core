import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/**
 * 收藏记录。`createdAt` / `firstFavoritedAt` 永远是第一次收藏时间；重新收藏只更新
 * `favoritedAt`。`songId` 在库里是 text，序列化后必须仍是 JSON 字符串。
 */
export type FavoriteRecord = {
  source: string;
  songId: string;
  createdAt: number;
  firstFavoritedAt: number;
  favoritedAt: number;
  updatedAt: number;
  isFavorite: boolean;
  revision: number;
};

/**
 * 别名必须加双引号。
 *
 * PostgreSQL 会把不加引号的标识符折叠成小写，`AS songId` 得到的字段是 `songid`，
 * 客户端读 `songId` 拿到 undefined 后**所有歌都显示未收藏**，而且没有任何报错。
 */
const COLUMNS = `source,
  song_id AS "songId",
  created_at AS "createdAt",
  created_at AS "firstFavoritedAt",
  favorited_at AS "favoritedAt",
  updated_at AS "updatedAt",
  (is_favorite = 1) AS "isFavorite",
  revision`;

@Injectable()
export class FavoritesRepository {
  constructor(private readonly database: DatabaseService) {}

  /**
   * 加收藏。一条语句完成"没有就插入、取消过就恢复、已经收藏就原样取回"。
   *
   * created_at 绝不能出现在更新列表中：它是用户第一次收藏这首歌的时间。重复请求也不能
   * 刷新 favorited_at 或 revision，只有从未收藏状态恢复时才算一次真正状态变化。
   */
  add(userId: number, source: string, songId: string): Promise<FavoriteRecord | undefined> {
    const now = Date.now();
    return this.database.first<FavoriteRecord>(
      `INSERT INTO favorites
         (user_id, source, song_id, created_at, is_favorite, favorited_at, updated_at, revision)
       VALUES ($1, $2, $3, $4, 1, $4, $4, 1)
       ON CONFLICT (user_id, source, song_id) DO UPDATE SET
         is_favorite = 1,
         favorited_at = CASE WHEN favorites.is_favorite = 0 THEN excluded.favorited_at ELSE favorites.favorited_at END,
         updated_at = CASE WHEN favorites.is_favorite = 0 THEN excluded.updated_at ELSE favorites.updated_at END,
         deleted_at = CASE WHEN favorites.is_favorite = 0 THEN NULL ELSE favorites.deleted_at END,
         revision = CASE WHEN favorites.is_favorite = 0 THEN favorites.revision + 1 ELSE favorites.revision END
       RETURNING ${COLUMNS}`,
      [userId, source, songId, now],
    );
  }

  async remove(userId: number, source: string, songId: string): Promise<boolean> {
    const now = Date.now();
    const affected = await this.database.run(
      `UPDATE favorites
       SET is_favorite = 0, deleted_at = $4, updated_at = $4, revision = revision + 1
       WHERE user_id = $1 AND source = $2 AND song_id = $3 AND is_favorite = 1`,
      [userId, source, songId, now],
    );
    return affected > 0;
  }

  list(userId: number): Promise<FavoriteRecord[]> {
    return this.database.all<FavoriteRecord>(
      `SELECT ${COLUMNS}
       FROM favorites
       WHERE user_id = $1 AND is_favorite = 1
       ORDER BY favorited_at DESC`,
      [userId],
    );
  }

  /**
   * 批量判断哪些歌已被收藏，一次查询覆盖整页搜索结果。
   *
   * 建表时的 `UNIQUE (user_id, source, song_id)` 正好能服务 `= ANY(...)`，
   * 60 个 id 也只是一次索引扫描。`song_id` 是 text，调用方传字符串数组。
   *
   * 这替掉了客户端原来的做法：为每首歌拉一次**完整**收藏列表再线性查找。
   */
  async favoritedIds(userId: number, source: string, songIds: string[]): Promise<Set<string>> {
    if (songIds.length === 0) return new Set();
    const rows = await this.database.all<{ song_id: string }>(
      `SELECT song_id
       FROM favorites
       WHERE user_id = $1 AND source = $2 AND song_id = ANY($3::text[]) AND is_favorite = 1`,
      [userId, source, songIds],
    );
    return new Set(rows.map((row) => row.song_id));
  }
}
