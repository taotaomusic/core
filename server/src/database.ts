import Database from "better-sqlite3";
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

const databasePath = resolve(process.env.DATABASE_PATH ?? "./data/music.sqlite");
mkdirSync(dirname(databasePath), { recursive: true });

export const database = new Database(databasePath);
database.pragma("journal_mode = WAL");
database.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
  );
`);

export type UserRecord = { id: number; username: string; created_at: string };

export function findUserByUsername(username: string) {
  return database.prepare("SELECT id, username, password_hash, password_salt, created_at FROM users WHERE username = ?").get(username) as (UserRecord & { password_hash: string; password_salt: string }) | undefined;
}

export function findUserById(id: number) {
  return database.prepare("SELECT id, username, created_at FROM users WHERE id = ?").get(id) as UserRecord | undefined;
}

export function createUser(username: string, passwordHash: string, passwordSalt: string) {
  const result = database.prepare("INSERT INTO users (username, password_hash, password_salt) VALUES (?, ?, ?)").run(username, passwordHash, passwordSalt);
  return findUserById(Number(result.lastInsertRowid))!;
}
