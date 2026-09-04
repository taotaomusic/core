import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

export type ImDeviceSession = {
  user_id: number;
  device_flag: number;
  device_id_hash: string;
  token_hash: string;
  expires_at: number;
  revoked_at: number | null;
};

/**
 * IM 连接凭据只保留哈希，和刷新令牌一样不能把可用 Token 落到 PostgreSQL 明文列。
 *
 * 悟空 IM 的 Token 管理接口以 UID 与设备类别为边界，因此这里也以 `(user_id, device_flag)`
 * 为主键；避免数据库声称多台 Android 可同时使用、而悟空 IM 实际只认最后一次签发的错觉。
 */
@Injectable()
export class ImRepository {
  constructor(private readonly database: DatabaseService) {}

  async saveSession(input: {
    userId: number;
    deviceFlag: number;
    deviceIdHash: string;
    tokenHash: string;
    expiresAt: number;
    now: number;
  }): Promise<void> {
    await this.database.run(
      `INSERT INTO im_device_session
         (user_id, device_flag, device_id_hash, token_hash, expires_at, revoked_at, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, NULL, $6, $6)
       ON CONFLICT (user_id, device_flag) DO UPDATE SET
         device_id_hash = EXCLUDED.device_id_hash,
         token_hash = EXCLUDED.token_hash,
         expires_at = EXCLUDED.expires_at,
         revoked_at = NULL,
         updated_at = EXCLUDED.updated_at`,
      [input.userId, input.deviceFlag, input.deviceIdHash, input.tokenHash, input.expiresAt, input.now],
    );
  }

  async revokeSession(userId: number, deviceFlag: number, now: number): Promise<void> {
    await this.database.run(
      `UPDATE im_device_session
       SET revoked_at = COALESCE(revoked_at, $3), updated_at = $3
       WHERE user_id = $1 AND device_flag = $2`,
      [userId, deviceFlag, now],
    );
  }

  async sessionOf(userId: number, deviceFlag: number): Promise<ImDeviceSession | undefined> {
    return this.database.first<ImDeviceSession>(
      `SELECT user_id, device_flag, device_id_hash, token_hash, expires_at, revoked_at
       FROM im_device_session WHERE user_id = $1 AND device_flag = $2`,
      [userId, deviceFlag],
    );
  }
}
