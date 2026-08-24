import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

export type ReservedApiKey = { id: number; key: string };

/** 管理端展示用的 Key 概览；绝不向浏览器返回明文 Key。 */
export type ApiKeySummary = { id: number; channel: string; maskedKey: string; quota: number };

/**
 * 第三方 API Key 数据访问。
 *
 * 创建任务先用一条 UPDATE 原子占用额度，不跨上游网络请求持有数据库连接。
 * 查询任务不检查额度，保证额度刚好耗尽时，最后一个任务仍能轮询到完成。
 */
@Injectable()
export class ApiKeyRepository {
  constructor(private readonly database: DatabaseService) {}

  reserve(channel: string, quota: number): Promise<ReservedApiKey | undefined> {
    return this.database.first<ReservedApiKey>(
      `WITH candidate AS (
         SELECT id FROM api_key
         WHERE channel = $1 AND quota >= $2
         ORDER BY quota DESC, id
         FOR UPDATE
         LIMIT 1
       )
       UPDATE api_key AS target
       SET quota = target.quota - $2
       FROM candidate
       WHERE target.id = candidate.id
       RETURNING target.id, target.key`,
      [channel, quota],
    );
  }

  /** 上游没有成功创建任务时按主键归还刚占用的额度。 */
  async refund(id: number, quota: number): Promise<void> {
    await this.database.run("UPDATE api_key SET quota = quota + $1 WHERE id = $2", [quota, id]);
  }

  async importKey(channel: string, key: string, quota: number): Promise<ApiKeySummary> {
    const saved = await this.database.first<{ id: number; channel: string; key: string; quota: number }>(
      `INSERT INTO api_key (channel, key, quota)
       VALUES ($1, $2, $3)
       ON CONFLICT (channel, key) DO UPDATE SET quota = EXCLUDED.quota
       RETURNING id, channel, key, quota`,
      [channel, key, quota],
    );
    if (!saved) throw new Error("导入图片 Key 失败");
    return this.summaryOf(saved);
  }

  async list(channel: string): Promise<ApiKeySummary[]> {
    const rows = await this.database.all<{ id: number; channel: string; key: string; quota: number }>(
      "SELECT id, channel, key, quota FROM api_key WHERE channel = $1 ORDER BY quota DESC, id DESC",
      [channel],
    );
    return rows.map((row) => this.summaryOf(row));
  }

  remove(id: number, channel: string): Promise<number> {
    return this.database.run("DELETE FROM api_key WHERE id = $1 AND channel = $2", [id, channel]);
  }

  private summaryOf(row: { id: number; channel: string; key: string; quota: number }): ApiKeySummary {
    const visibleTail = row.key.slice(-4);
    return { id: row.id, channel: row.channel, maskedKey: `••••••••${visibleTail}`, quota: row.quota };
  }
}
