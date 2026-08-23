import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

export type ReservedApiKey = { id: number; key: string };

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
}
