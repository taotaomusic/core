import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

export type AdminSession = {
  id: number; admin_id: number; token_hash: string;
  expires_at: number; created_at: number; revoked_at: number | null;
  login_ip: string | null; user_agent: string | null;
};

@Injectable()
export class AdminSessionsRepository {
  constructor(private readonly database: DatabaseService) {}

  async create(adminId: number, tokenHash: string, expiresAt: number, ip: string, userAgent: string): Promise<void> {
    await this.database.run(
      `INSERT INTO admin_sessions (admin_id, token_hash, expires_at, created_at, login_ip, user_agent)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [adminId, tokenHash, expiresAt, Date.now(), ip, userAgent],
    );
  }

  async findActive(tokenHash: string): Promise<AdminSession | undefined> {
    return this.database.first<AdminSession>(
      `SELECT * FROM admin_sessions WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > $2`,
      [tokenHash, Date.now()],
    );
  }

  async revoke(tokenHash: string): Promise<void> {
    await this.database.run(
      `UPDATE admin_sessions SET revoked_at = $1 WHERE token_hash = $2 AND revoked_at IS NULL`,
      [Date.now(), tokenHash],
    );
  }

  async revokeAllForAdmin(adminId: number): Promise<void> {
    await this.database.run(
      `UPDATE admin_sessions SET revoked_at = $1 WHERE admin_id = $2 AND revoked_at IS NULL`,
      [Date.now(), adminId],
    );
  }

  /**
   * 撤销该管理员除 `keepTokenHash` 之外的会话。
   *
   * 改密码后要把其它设备踢下线，但发起改密码的这台不该被一起踢掉。
   */
  async revokeAllExcept(adminId: number, keepTokenHash: string): Promise<void> {
    await this.database.run(
      `UPDATE admin_sessions SET revoked_at = $1
        WHERE admin_id = $2 AND revoked_at IS NULL AND token_hash <> $3`,
      [Date.now(), adminId, keepTokenHash],
    );
  }

  async cleanup(): Promise<void> {
    await this.database.run(
      `DELETE FROM admin_sessions WHERE revoked_at IS NOT NULL OR expires_at < $1`, [Date.now()],
    );
  }
}