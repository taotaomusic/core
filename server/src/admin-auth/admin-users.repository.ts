import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";
import { isUniqueViolation } from "../database/pg-errors";
import { ApiErrors } from "../common/api.exception";

export type AdminUserRecord = {
  id: number; username: string; display_name: string; email: string | null;
  role: string; totp_enabled: number; ip_whitelist: string | null;
  last_login_at: number | null; last_login_ip: string | null;
  disabled_at: number | null; must_change_password: number; auth_source: string;
  created_at: string; created_by: number | null;
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
              disabled_at, must_change_password, auth_source,
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at,
              created_by
       FROM admin_users WHERE username = $1 AND disabled_at IS NULL`,
      [username],
    );
  }

  /**
   * 按用户名取凭据，**包含已禁用账号**。
   *
   * LDAP 同步专用：同步前必须能看见「本地已禁用」这个状态，否则被禁用的
   * 目录用户每次登录都会被当成新账号重新建出来，禁用等于没禁。
   */
  findAnyByUsername(username: string): Promise<AdminCredentials | undefined> {
    return this.database.first<AdminCredentials>(
      `SELECT id, username, password_hash, password_salt, display_name, email, role,
              totp_secret, totp_enabled, ip_whitelist, last_login_at, last_login_ip,
              disabled_at, must_change_password, auth_source,
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at,
              created_by
       FROM admin_users WHERE username = $1`,
      [username],
    );
  }

  /**
   * 按 ID 取管理员。
   *
   * **刻意不过滤 `disabled_at`**：管理端需要能列出、编辑和重新启用已禁用的账号，
   * 过滤掉会让「刚禁用的账号在响应里变成 undefined」。登录与鉴权路径各自显式
   * 检查 `disabled_at`（[AdminUsersRepository.findByUsername] 与
   * [AdminAuthService.validateSession]），不依赖这里。
   */
  findById(id: number): Promise<AdminUserRecord | undefined> {
    return this.database.first<AdminUserRecord>(
      `SELECT id, username, display_name, email, role, totp_enabled, ip_whitelist,
              last_login_at, last_login_ip, disabled_at, must_change_password, auth_source,
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at,
              created_by
       FROM admin_users WHERE id = $1`,
      [id],
    );
  }

  /**
   * 按 ID 取含密码哈希与 TOTP 密钥的完整凭据。
   *
   * TOTP 二次验证只拿得到第一步登录返回的 admin_id，需要单独一条能读到
   * `totp_secret` 的查询 —— [findById] 返回的 [AdminUserRecord] 刻意不含密钥，
   * 不能直接复用。
   */
  findCredentialsById(id: number): Promise<AdminCredentials | undefined> {
    return this.database.first<AdminCredentials>(
      `SELECT id, username, password_hash, password_salt, display_name, email, role,
              totp_secret, totp_enabled, ip_whitelist, last_login_at, last_login_ip,
              disabled_at, must_change_password, auth_source,
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at,
              created_by
       FROM admin_users WHERE id = $1`,
      [id],
    );
  }

  /**
   * 新建管理员。
   *
   * `mustChangePassword` 与 `authSource` 都放在参数表**尾部**并带默认值，
   * 这样 LDAP 同步与后台创建管理员的调用点不需要跟着改。
   */
  async create(username: string, passwordHash: string, passwordSalt: string,
               displayName: string, role: string, createdBy: number | null,
               email: string | null = null, mustChangePassword = false,
               authSource = "local"): Promise<AdminUserRecord> {
    try {
      return (await this.database.first<AdminUserRecord>(
        `INSERT INTO admin_users
           (username, password_hash, password_salt, display_name, role, created_by, email,
            must_change_password, auth_source)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
         RETURNING id, username, display_name, email, role, totp_enabled, ip_whitelist,
                   last_login_at, last_login_ip, disabled_at, must_change_password, auth_source,
                   to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at, created_by`,
        [
          username, passwordHash, passwordSalt, displayName, role, createdBy, email,
          mustChangePassword ? 1 : 0, authSource,
        ],
      ))!;
    } catch (error) {
      if (isUniqueViolation(error)) throw ApiErrors.conflict(4090, "管理员用户名已存在");
      throw error;
    }
  }

  /**
   * 标记某个账号已由 LDAP 接管。
   *
   * 只在当前不是 ldap 时才写，避免每次登录都做一次无意义的 UPDATE。
   * 这是存量 LDAP 账号补齐标记的唯一途径。
   */
  async setAuthSource(id: number, authSource: string): Promise<void> {
    await this.database.run(
      `UPDATE admin_users SET auth_source = $2 WHERE id = $1 AND auth_source <> $2`,
      [id, authSource],
    );
  }

  async updateLastLogin(id: number, ip: string): Promise<void> {
    await this.database.run(
      `UPDATE admin_users SET last_login_at = $2, last_login_ip = $3 WHERE id = $1`,
      [id, Date.now(), ip],
    );
  }

  async setRole(id: number, role: string): Promise<boolean> {
    return (await this.database.run(
      `UPDATE admin_users SET role = $2 WHERE id = $1`, [id, role],
    )) === 1;
  }

  /**
   * 更新展示名与邮箱。
   *
   * 只更新显式传入的字段：`display_name` 缺失表示不改，`email` 传 `null`
   * 表示清空。因此不能写成 `COALESCE($n, 原值)` —— 那样就分不清
   * 「不改」和「清空」了。
   */
  async updateProfile(
    id: number,
    fields: { displayName?: string; email?: string | null },
  ): Promise<boolean> {
    const assignments: string[] = [];
    const params: unknown[] = [id];
    if (fields.displayName !== undefined) {
      params.push(fields.displayName);
      assignments.push(`display_name = $${params.length}`);
    }
    if (fields.email !== undefined) {
      params.push(fields.email);
      assignments.push(`email = $${params.length}`);
    }
    if (assignments.length === 0) return false;
    return (await this.database.run(
      `UPDATE admin_users SET ${assignments.join(", ")} WHERE id = $1`, params,
    )) === 1;
  }

  /**
   * 统计仍然可用的超级管理员数量。
   *
   * 用于「不能把最后一个 super_admin 降级/禁用/删除」的保护：一旦归零，
   * 后台就再没人能创建管理员了，只能改库救场。
   */
  async countActiveSuperAdmins(): Promise<number> {
    return (await this.database.first<{ count: number }>(
      `SELECT COUNT(*)::integer AS count FROM admin_users WHERE role = 'super_admin' AND disabled_at IS NULL`,
    ))?.count ?? 0;
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

  /**
   * 改密。
   *
   * 顺手把 `must_change_password` 清零，且和口令写在**同一条** UPDATE 里：
   * 拆成两步的话，中间失败会留下「口令已换、却仍被要求改密」的账号，
   * 管理员会以为自己改了个假密码。这是清标记的唯一入口。
   */
  async updatePassword(id: number, passwordHash: string, passwordSalt: string): Promise<void> {
    await this.database.run(
      `UPDATE admin_users
         SET password_hash = $2, password_salt = $3, must_change_password = 0
       WHERE id = $1`,
      [id, passwordHash, passwordSalt],
    );
  }

  async setIpWhitelist(id: number, ips: string | null): Promise<void> {
    await this.database.run(`UPDATE admin_users SET ip_whitelist = $2 WHERE id = $1`, [id, ips]);
  }

  async list(): Promise<AdminUserRecord[]> {
    return this.database.all<AdminUserRecord>(
      `SELECT id, username, display_name, email, role, totp_enabled, ip_whitelist,
              last_login_at, last_login_ip, disabled_at, must_change_password, auth_source,
              to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at, created_by
       FROM admin_users ORDER BY id`,
    );
  }

  async delete(id: number): Promise<boolean> {
    return (await this.database.run(`DELETE FROM admin_users WHERE id = $1`, [id])) === 1;
  }
}