import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

type RefreshTokenRecord = { id: number; user_id: number; expires_at: number };

@Injectable()
export class RefreshTokensRepository {
  constructor(private readonly database: DatabaseService) {}

  async save(userId: number, tokenHash: string, expiresAt: number): Promise<void> {
    await this.database.run(
      "INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES ($1, $2, $3, $4)",
      [userId, tokenHash, expiresAt, Date.now()],
    );
  }

  /**
   * 消费一个刷新令牌：命中且未过期就立即标记吊销并返回。
   *
   * 刷新令牌是一次性的 —— 客户端拿到新令牌对后会覆盖本地存储，
   * 若不吊销旧令牌，并发刷新会让后到的请求拿着已被轮换掉的令牌，反而把会话弄丢。
   *
   * 这里刻意是**一条**语句而不是先 SELECT 再 UPDATE：连接池下两个并发刷新会插进
   * 两条语句中间，双双拿到同一条未吊销记录，一次性就失效了。过期令牌不匹配 WHERE，
   * 因此也不会被吊销，与拆成两步时的可观察行为一致。
   */
  consume(tokenHash: string): Promise<RefreshTokenRecord | undefined> {
    const now = Date.now();
    return this.database.first<RefreshTokenRecord>(
      `UPDATE refresh_tokens SET revoked_at = $1
       WHERE token_hash = $2 AND revoked_at IS NULL AND expires_at > $1
       RETURNING id, user_id, expires_at`,
      [now, tokenHash],
    );
  }

  async revoke(tokenHash: string): Promise<void> {
    await this.database.run(
      "UPDATE refresh_tokens SET revoked_at = $1 WHERE token_hash = $2 AND revoked_at IS NULL",
      [Date.now(), tokenHash],
    );
  }
}
