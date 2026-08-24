import { Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { DatabaseService } from "../database/database.service";
import { isUniqueViolation } from "../database/pg-errors";

/** 用户表对外暴露的字段。密码哈希与盐只在校验时读取，不进入响应。 */
export type UserRecord = { id: number; username: string; created_at: string };
export type UserWithEmail = UserRecord & { email: string | null };
export type UserCredentials = UserRecord & { password_hash: string; password_salt: string };

/**
 * `created_at` 在库里是 timestamptz，但响应里必须仍是 SQLite 那种
 * `2026-08-23 08:05:51` 形状的字符串 —— 直接回传会变成驱动解析出的 Date 对象，
 * 序列化后是 ISO-8601，与已发布的响应形状不一致。
 */
const CREATED_AT = `to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') AS created_at`;

@Injectable()
export class UsersRepository {
  constructor(private readonly database: DatabaseService) {}

  findByUsername(username: string): Promise<UserCredentials | undefined> {
    return this.database.first<UserCredentials>(
      `SELECT id, username, password_hash, password_salt, ${CREATED_AT} FROM users WHERE username = $1`,
      [username],
    );
  }

  findByEmail(email: string): Promise<UserRecord | undefined> {
    return this.database.first<UserRecord>(`SELECT id, username, ${CREATED_AT} FROM users WHERE email = $1`, [email]);
  }

  findById(id: number): Promise<UserRecord | undefined> {
    return this.database.first<UserRecord>(`SELECT id, username, ${CREATED_AT} FROM users WHERE id = $1`, [id]);
  }

  findByIdWithEmail(id: number): Promise<UserWithEmail | undefined> {
    return this.database.first<UserWithEmail>(`SELECT id, username, email, ${CREATED_AT} FROM users WHERE id = $1`, [id]);
  }

  async bindEmail(userId: number, email: string): Promise<boolean> {
    return this.updateEmail(`UPDATE users SET email = $1 WHERE id = $2 AND email IS NULL`, [email, userId]);
  }

  async changeEmail(userId: number, email: string): Promise<boolean> {
    return this.updateEmail(`UPDATE users SET email = $1 WHERE id = $2 AND email IS NOT NULL`, [email, userId]);
  }

  /**
   * 建用户。用 RETURNING 一次往返拿到新行，取代 lastInsertRowid 再查一次。
   *
   * 这里必须接住唯一约束冲突：调用方是先查重、再哈希密码（scrypt N=65536，约 100ms）、
   * 最后插入，连接池下两个同名注册会双双通过查重，第二条 INSERT 撞约束。
   * 不翻译的话会被全局过滤器归成 502，而契约要求 409/4090。
   */
  async create(username: string, email: string, passwordHash: string, passwordSalt: string): Promise<UserRecord> {
    try {
      const created = await this.database.first<UserRecord>(
        `INSERT INTO users (username, email, password_hash, password_salt) VALUES ($1, $2, $3, $4)
         RETURNING id, username, ${CREATED_AT}`,
        [username, email, passwordHash, passwordSalt],
      );
      return created!;
    } catch (error) {
      if (isUniqueViolation(error)) throw ApiErrors.conflict(4090, "用户名已存在");
      throw error;
    }
  }

  private async updateEmail(sql: string, params: unknown[]): Promise<boolean> {
    try {
      return (await this.database.run(sql, params)) === 1;
    } catch (error) {
      if (isUniqueViolation(error)) throw ApiErrors.conflict(4092, "邮箱已注册");
      throw error;
    }
  }
}
