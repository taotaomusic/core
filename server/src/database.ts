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
  CREATE TABLE IF NOT EXISTS refresh_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER
  );
  CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash ON refresh_tokens(token_hash);
  CREATE TABLE IF NOT EXISTS favorites (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    song_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(user_id, source, song_id)
  );
  CREATE INDEX IF NOT EXISTS idx_favorites_user ON favorites(user_id, created_at DESC);
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

export function saveRefreshToken(userId: number, tokenHash: string, expiresAt: number) {
  database.prepare("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)").run(userId, tokenHash, expiresAt, Date.now());
}

export function consumeRefreshToken(tokenHash: string) {
  const token = database.prepare("SELECT id, user_id, expires_at FROM refresh_tokens WHERE token_hash = ? AND revoked_at IS NULL").get(tokenHash) as { id: number; user_id: number; expires_at: number } | undefined;
  if (!token || token.expires_at <= Date.now()) return undefined;
  database.prepare("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?").run(Date.now(), token.id);
  return token;
}

export function revokeRefreshToken(tokenHash: string) {
  database.prepare("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL").run(Date.now(), tokenHash);
}

export function addFavorite(userId: number, source: string, songId: string) {
  database.prepare("INSERT OR IGNORE INTO favorites (user_id, source, song_id, created_at) VALUES (?, ?, ?, ?)").run(userId, source, songId, Date.now());
  return database.prepare("SELECT source, song_id AS songId, created_at AS createdAt FROM favorites WHERE user_id = ? AND source = ? AND song_id = ?").get(userId, source, songId);
}

export function removeFavorite(userId: number, source: string, songId: string) {
  const result = database.prepare("DELETE FROM favorites WHERE user_id = ? AND source = ? AND song_id = ?").run(userId, source, songId);
  return result.changes > 0;
}

export function listFavorites(userId: number) {
  return database.prepare("SELECT source, song_id AS songId, created_at AS createdAt FROM favorites WHERE user_id = ? ORDER BY created_at DESC").all(userId);
}
