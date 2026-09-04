import { Injectable } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import { ApiErrors } from "../common/api.exception";
import { DatabaseService } from "../database/database.service";
import { isUniqueViolation } from "../database/pg-errors";

/** 用户表对外暴露的字段。密码哈希与盐只在校验时读取，不进入响应。 */
export type UserRecord = { id: number; username: string; created_at: string };
export type UserWithEmail = UserRecord & { email: string | null };
export type UserProfile = UserRecord & { email: string | null; nickname: string; avatarUrl: string | null };
export type UserCredentials = UserRecord & { password_hash: string; password_salt: string };
type ImUidRow = { im_uid: string | null };
export type ImContact = { uid: string; nickname: string };

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
      `SELECT id, username, password_hash, password_salt, ${CREATED_AT}
       FROM users WHERE username = $1 AND disabled_at IS NULL`,
      [username],
    );
  }

  findByEmail(email: string): Promise<UserRecord | undefined> {
    return this.database.first<UserRecord>(`SELECT id, username, ${CREATED_AT} FROM users WHERE email = $1`, [email]);
  }

  findById(id: number): Promise<UserRecord | undefined> {
    return this.database.first<UserRecord>(
      `SELECT id, username, ${CREATED_AT} FROM users WHERE id = $1 AND disabled_at IS NULL`,
      [id],
    );
  }

  findByIdWithEmail(id: number): Promise<UserWithEmail | undefined> {
    return this.database.first<UserWithEmail>(
      `SELECT id, username, email, ${CREATED_AT} FROM users WHERE id = $1 AND disabled_at IS NULL`,
      [id],
    );
  }

  findProfileById(id: number): Promise<UserProfile | undefined> {
    return this.database.first<UserProfile>(
      `SELECT id, username, email, COALESCE(nickname, username) AS nickname, avatar_url AS "avatarUrl", ${CREATED_AT}
       FROM users WHERE id = $1 AND disabled_at IS NULL`,
      [id],
    );
  }

  async updateProfile(userId: number, nickname: string | undefined, avatarUrl: string | null | undefined): Promise<UserProfile | undefined> {
    return this.database.first<UserProfile>(
      `UPDATE users
       SET nickname = CASE WHEN $2::boolean THEN $3 ELSE nickname END,
           avatar_url = CASE WHEN $4::boolean THEN $5 ELSE avatar_url END
       WHERE id = $1
       RETURNING id, username, email, COALESCE(nickname, username) AS nickname, avatar_url AS "avatarUrl", ${CREATED_AT}`,
      [userId, nickname !== undefined, nickname ?? null, avatarUrl !== undefined, avatarUrl ?? null],
    );
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
        `INSERT INTO users (username, email, password_hash, password_salt, im_uid) VALUES ($1, $2, $3, $4, $5)
         RETURNING id, username, ${CREATED_AT}`,
        [username, email, passwordHash, passwordSalt, randomUUID()],
      );
      return created!;
    } catch (error) {
      if (isUniqueViolation(error)) throw ApiErrors.conflict(4090, "用户名已存在");
      throw error;
    }
  }

  /**
   * 取一个不可枚举的悟空 IM UID。
   *
   * 不能用递增的 users.id：它会暴露用户规模，并允许攻击者批量猜测聊天对象。新账号和历史
   * 账号统一使用 UUID；数据库唯一索引与条件 UPDATE 处理极小概率
   * 的碰撞及同一帐号的并发首次请求。
   */
  async ensureImUid(userId: number): Promise<string> {
    const current = await this.database.first<ImUidRow>(`SELECT im_uid FROM users WHERE id = $1`, [userId]);
    if (!current) throw ApiErrors.unauthorized(4010, "请先登录");
    if (current.im_uid) return current.im_uid;

    // 随机碰撞的概率可忽略，但仍依靠数据库唯一约束作最终裁决；发生时重新生成即可。
    for (let attempt = 0; attempt < 3; attempt++) {
      const candidate = randomUUID();
      try {
        const updated = await this.database.first<ImUidRow>(
          `UPDATE users SET im_uid = $2 WHERE id = $1 AND im_uid IS NULL RETURNING im_uid`,
          [userId, candidate],
        );
        if (updated?.im_uid) return updated.im_uid;
      } catch (error) {
        if (!isUniqueViolation(error)) throw error;
      }

      const concurrent = await this.database.first<ImUidRow>(`SELECT im_uid FROM users WHERE id = $1`, [userId]);
      if (concurrent?.im_uid) return concurrent.im_uid;
    }
    throw ApiErrors.upstream("生成聊天账号标识失败，请稍后重试");
  }

  /** 退出聊天只读取已有 UID，不能因为退出操作给未使用 IM 的旧账号分配新标识。 */
  async imUidOf(userId: number): Promise<string | undefined> {
    return (await this.database.first<ImUidRow>(`SELECT im_uid FROM users WHERE id = $1`, [userId]))?.im_uid ?? undefined;
  }

  async imContactsByUid(uids: string[]): Promise<ImContact[]> {
    if (uids.length === 0) return [];
    return this.database.all<ImContact>(
      `SELECT im_uid AS uid, COALESCE(NULLIF(BTRIM(nickname), ''), username) AS nickname
       FROM users WHERE im_uid = ANY($1::text[]) AND disabled_at IS NULL`,
      [uids],
    );
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
