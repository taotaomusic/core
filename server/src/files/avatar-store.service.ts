import { Injectable } from "@nestjs/common";
import { randomBytes } from "node:crypto";
import { DatabaseService } from "../database/database.service";

/** 头像行。`bytes` 是原始图片字节，`content_type` 来自上传时的魔数判定。 */
export type AvatarRow = {
  id: string;
  content_type: string;
  bytes: Buffer;
  byte_size: number;
};

/**
 * 头像二进制的存取。
 *
 * 地址标识是每次上传随机生成的 token（128 位十六进制）：公开下载端点不携带
 * 访问令牌也不可枚举，token 一换旧地址随即失效。
 */
@Injectable()
export class AvatarStoreService {
  constructor(private readonly database: DatabaseService) {}

  /** 覆盖式保存：同一用户只保留最新一张，旧 token 被替换后旧 URL 自然 404。 */
  async save(userId: number, contentType: string, bytes: Buffer): Promise<string> {
    const token = randomBytes(16).toString("hex");
    await this.database.run(
      `INSERT INTO user_avatars (id, user_id, content_type, bytes, byte_size, created_at)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (user_id) DO UPDATE SET
         id = EXCLUDED.id,
         content_type = EXCLUDED.content_type,
         bytes = EXCLUDED.bytes,
         byte_size = EXCLUDED.byte_size,
         created_at = EXCLUDED.created_at`,
      [token, userId, contentType, bytes, bytes.length, Date.now()],
    );
    return token;
  }

  find(token: string): Promise<AvatarRow | undefined> {
    return this.database.first<AvatarRow>(
      `SELECT id, content_type, bytes, byte_size FROM user_avatars WHERE id = $1`,
      [token],
    );
  }

  /** 清除头像时把二进制一并删掉，不让清除操作留下孤儿行。 */
  async removeByUserId(userId: number): Promise<void> {
    await this.database.run(`DELETE FROM user_avatars WHERE user_id = $1`, [userId]);
  }
}
