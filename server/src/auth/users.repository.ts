import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/** 用户表对外暴露的字段。密码哈希与盐只在校验时读取，不进入响应。 */
export type UserRecord = { id: number; username: string; created_at: string };
export type UserCredentials = UserRecord & { password_hash: string; password_salt: string };

@Injectable()
export class UsersRepository {
  constructor(private readonly database: DatabaseService) {}

  findByUsername(username: string): UserCredentials | undefined {
    return this.database.connection
      .prepare("SELECT id, username, password_hash, password_salt, created_at FROM users WHERE username = ?")
      .get(username) as UserCredentials | undefined;
  }

  findById(id: number): UserRecord | undefined {
    return this.database.connection
      .prepare("SELECT id, username, created_at FROM users WHERE id = ?")
      .get(id) as UserRecord | undefined;
  }

  create(username: string, passwordHash: string, passwordSalt: string): UserRecord {
    const result = this.database.connection
      .prepare("INSERT INTO users (username, password_hash, password_salt) VALUES (?, ?, ?)")
      .run(username, passwordHash, passwordSalt);
    return this.findById(Number(result.lastInsertRowid))!;
  }
}
