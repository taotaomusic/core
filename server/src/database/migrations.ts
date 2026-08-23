import type { Database } from "better-sqlite3";

/**
 * 全部建表语句。
 *
 * 迁移前这些 SQL 分散在 `database.ts` 和 `app-release.ts` 两处，
 * 且都是**模块导入时的副作用**。Nest 的依赖注入不再保证导入时机，
 * 所以集中到这里，由 [DatabaseService] 在 onModuleInit 显式调用。
 */
export function runMigrations(connection: Database): void {
  connection.exec(`
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

    CREATE TABLE IF NOT EXISTS app_release (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      channel TEXT NOT NULL DEFAULT 'release',
      version_code INTEGER NOT NULL,
      version_name TEXT NOT NULL,
      apk_file TEXT NOT NULL,
      apk_size INTEGER NOT NULL,
      apk_sha256 TEXT NOT NULL,
      release_note TEXT NOT NULL DEFAULT '',
      rollout_percent INTEGER NOT NULL DEFAULT 0,
      min_sdk INTEGER NOT NULL DEFAULT 24,
      enabled INTEGER NOT NULL DEFAULT 1,
      published_at INTEGER NOT NULL,
      UNIQUE(channel, version_code)
    );
    CREATE INDEX IF NOT EXISTS idx_app_release_lookup ON app_release(channel, enabled, version_code DESC);

    CREATE TABLE IF NOT EXISTS app_channel (
      channel TEXT PRIMARY KEY,
      min_supported_version_code INTEGER NOT NULL DEFAULT 0,
      updated_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS app_config (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL,
      min_version_code INTEGER,
      max_version_code INTEGER,
      updated_at INTEGER NOT NULL
    );
  `);
}
