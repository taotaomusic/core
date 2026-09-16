import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";
import { isUniqueViolation } from "../database/pg-errors";
import { ApiErrors } from "../common/api.exception";

export type AdminUserRecord = {
  id: number; username: string; display_name: string; email: string | null;
  role: string; totp_enabled: number; ip_whitelist: string | null;
  last_login_at: number | null; last_login_ip: string | null;
  disabled_at: number | null; created_at: string; created_by: number | null;
};

export type AdminCredentials = AdminUserRecord & {
  password_hash: string; password_salt: string; totp_secret: string | null;
};

@Injectable()
export class AdminUsersRepository {
  constructor(private readonly database: DatabaseService) {}

  findByUsername(username: string): Promise<AdminCredentials | undefined> {
    return this.database.first<AdminCredentials>(
      `SELECT id, username, password_hash, password_salt, display_name, email, role,
              totp_secret, totp_enabled, ip_whitelist, last_login_at, last_login_ip,
              disabled_at, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at,
              created_by
       FROM admin_users WHERE username = $1 AND disabled_at IS NULL`,
      [username],
    );
  }

  findById(id: number): Promise<AdminUserRecord | undefined> {
    return this.database.first<AdminUserRecord>(
      `SELECT id, username, display_name, email, role, totp_enabled, ip_whitelist,
              last_login_at, last_login_ip, disabled_at,
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at,
              created_by
       FROM admin_users WHERE id = $1 AND disabled_at IS NULL`,
      [id],
    );
  }

  async create(username: string, passwordHash: string, passwordSalt: string,
               displayName: string, role: string, createdBy: number | null): Promise<AdminUserRecord> {
    try {
      return (await this.database.first<AdminUserRecord>(
        `INSERT INTO admin_users (username, password_hash, password_salt, display_name, role, created_by)
         VALUES ($1, $2, $3, $4, $5, $6)
         RETURNING id, username, display_name, email, role, totp_enabled, ip_whitelist,
                   last_login_at, last_login_ip, disabled_at,
                   to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at, created_by`,
        [username, passwordHash, passwordSalt, displayName, role, createdBy],
      ))!;
    } catch (error) {
      if (isUniqueViolation(error)) throw ApiErrors.conflict(4090, "管理员用户名已存在");
      throw error;
    }
  }

  async updateLastLogin(id: number, ip: string): Promise<void> {
    await this.database.run(
      `UPDATE admin_users SET last_login_at = $2, last_login_ip = $3 WHERE id = $1`,
      [id, Date.now(), ip],
    );
  }

  async setRole(id: number, role: string): Promise<boolean> {
    return (await this.database.run(
      `UPDATE admin_users SET role = $2 WHERE id = $1 AND disabled_at IS NULL`, [id, role],
    )) === 1;
  }

  async setDisabled(id: number, disabled: boolean): Promise<boolean> {
    return (await this.database.run(
      `UPDATE admin_users SET disabled_at = $2 WHERE id = $1`, [id, disabled ? Date.now() : null],
    )) === 1;
  }

  async setTotpSecret(id: number, secret: string | null, enabled: boolean): Promise<void> {
    await this.database.run(
      `UPDATE admin_users SET totp_secret = $2, totp_enabled = $3 WHERE id = $1`,
      [id, secret, enabled ? 1 : 0],
    );
  }

  async updatePassword(id: number, passwordHash: string, passwordSalt: string): Promise<void> {
    await this.database.run(
      `UPDATE admin_users SET password_hash = $2, password_salt = $3 WHERE id = $1`,
      [id, passwordHash, passwordSalt],
    );
  }

  async setIpWhitelist(id: number, ips: string | null): Promise<void> {
    await this.database.run(`UPDATE admin_users SET ip_whitelist = $2 WHERE id = $1`, [id, ips]);
  }

  async list(): Promise<AdminUserRecord[]> {
    return this.database.all<AdminUserRecord>(
      `SELECT id, username, display_name, email, role, totp_enabled, ip_whitelist,
              last_login_at, last_login_ip, disabled_at,
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at, created_by
       FROM admin_users ORDER BY id`,
    );
  }

  async delete(id: number): Promise<boolean> {
    return (await this.database.run(`DELETE FROM admin_users WHERE id = $1`, [id])) === 1;
  }
}