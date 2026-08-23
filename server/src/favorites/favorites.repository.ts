import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/** 收藏记录。`songId` 在库里是 TEXT，序列化后必须仍是 JSON 字符串 —— 客户端按字符串比较。 */
export type FavoriteRecord = { source: string; songId: string; createdAt: number };

@Injectable()
export class FavoritesRepository {
  constructor(private readonly database: DatabaseService) {}

  add(userId: number, source: string, songId: string): FavoriteRecord | undefined {
    this.database.connection
      .prepare("INSERT OR IGNORE INTO favorites (user_id, source, song_id, created_at) VALUES (?, ?, ?, ?)")
      .run(userId, source, songId, Date.now());
    return this.database.connection
      .prepare(
        "SELECT source, song_id AS songId, created_at AS createdAt FROM favorites WHERE user_id = ? AND source = ? AND song_id = ?",
      )
      .get(userId, source, songId) as FavoriteRecord | undefined;
  }

  remove(userId: number, source: string, songId: string): boolean {
    const result = this.database.connection
      .prepare("DELETE FROM favorites WHERE user_id = ? AND source = ? AND song_id = ?")
      .run(userId, source, songId);
    return result.changes > 0;
  }

  list(userId: number): FavoriteRecord[] {
    return this.database.connection
      .prepare(
        "SELECT source, song_id AS songId, created_at AS createdAt FROM favorites WHERE user_id = ? ORDER BY created_at DESC",
      )
      .all(userId) as FavoriteRecord[];
  }
}
