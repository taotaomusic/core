import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

type RefreshTokenRecord = { id: number; user_id: number; expires_at: number };

@Injectable()
export class RefreshTokensRepository {
  constructor(private readonly database: DatabaseService) {}

  save(userId: number, tokenHash: string, expiresAt: number): void {
    this.database.connection
      .prepare("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)")
      .run(userId, tokenHash, expiresAt, Date.now());
  }

  /**
   * 消费一个刷新令牌：命中且未过期就立即标记吊销并返回。
   *
   * 刷新令牌是一次性的 —— 客户端拿到新令牌对后会覆盖本地存储，
   * 若这里不吊销旧令牌，并发刷新会让后到的请求拿着已被轮换掉的令牌，反而把会话弄丢。
   */
  consume(tokenHash: string): RefreshTokenRecord | undefined {
    const token = this.database.connection
      .prepare("SELECT id, user_id, expires_at FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NULL")
      .get(tokenHash) as RefreshTokenRecord | undefined;
    if (!token || token.expires_at <= Date.now()) return undefined;
    this.database.connection
      .prepare("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?")
      .run(Date.now(), token.id);
    return token;
  }

  revoke(tokenHash: string): void {
    this.database.connection
      .prepare("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")
      .run(Date.now(), tokenHash);
  }
}
