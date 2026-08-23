import type { Pool } from "pg";

/**
 * 顾问锁的键，任意常量，只要全库唯一即可。
 * 多个实例同时启动时用它把建表串行化。
 */
const MIGRATION_LOCK_KEY = 913_720_001;

/**
 * 全部建表语句。
 *
 * 由 [DatabaseService] 在 onModuleInit 显式调用，不做成模块导入时的副作用 ——
 * Nest 的依赖注入不保证导入时机。
 *
 * 类型选择上有三条规则，每条都对应一处会出错的代码：
 *
 * - 存 `Date.now()` 的列一律 `bigint`：毫秒时间戳约 1.7e12，超出 int4 的 21 亿上限
 * - 版本号、大小、百分比一律 `integer`：int8 会被 pg 解析成字符串（见 database.service.ts
 *   里的类型解析器注册），`apk_size` 变成字符串会让客户端的下载进度和 416 判断出错
 * - `enabled` 是 `smallint` 0/1 而**不是 boolean**：`release.service.ts` 里写的是
 *   `item.enabled === 1`（严格等于数字），`release.controller.ts` 里是真值判断，
 *   改成 boolean 会让前者恒为 false，抬高最低可用版本的守卫就永远返回 409
 */
export async function runMigrations(pool: Pool): Promise<void> {
  const client = await pool.connect();
  try {
    // PostgreSQL 的 DDL 是事务性的，整组建表要么全成要么全不成。
    // 先取顾问锁：并发执行 CREATE TABLE IF NOT EXISTS 有已知的 pg_type 唯一键冲突。
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock($1)", [MIGRATION_LOCK_KEY]);
    await client.query(`
      CREATE TABLE IF NOT EXISTS users (
        id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        username      text NOT NULL UNIQUE,
        password_hash text NOT NULL,
        password_salt text NOT NULL,
        created_at    timestamptz NOT NULL DEFAULT now()
      );

      CREATE TABLE IF NOT EXISTS refresh_tokens (
        id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        user_id    integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_hash text NOT NULL UNIQUE,
        expires_at bigint NOT NULL,
        created_at bigint NOT NULL,
        revoked_at bigint
      );

      CREATE TABLE IF NOT EXISTS favorites (
        id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        user_id    integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        source     text NOT NULL,
        song_id    text NOT NULL,
        created_at bigint NOT NULL,
        UNIQUE (user_id, source, song_id)
      );
      CREATE INDEX IF NOT EXISTS idx_favorites_user ON favorites (user_id, created_at DESC);

      CREATE TABLE IF NOT EXISTS app_release (
        id              integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        channel         text NOT NULL DEFAULT 'release',
        version_code    integer NOT NULL,
        version_name    text NOT NULL,
        apk_file        text NOT NULL,
        apk_size        integer NOT NULL,
        apk_sha256      text NOT NULL,
        release_note    text NOT NULL DEFAULT '',
        rollout_percent integer NOT NULL DEFAULT 0,
        min_sdk         integer NOT NULL DEFAULT 24,
        enabled         smallint NOT NULL DEFAULT 1,
        published_at    bigint NOT NULL,
        UNIQUE (channel, version_code)
      );
      CREATE INDEX IF NOT EXISTS idx_app_release_lookup
        ON app_release (channel, enabled, version_code DESC);

      CREATE TABLE IF NOT EXISTS app_channel (
        channel                    text PRIMARY KEY,
        min_supported_version_code integer NOT NULL DEFAULT 0,
        updated_at                 bigint NOT NULL
      );

      CREATE TABLE IF NOT EXISTS app_config (
        key              text PRIMARY KEY,
        value            text NOT NULL,
        min_version_code integer,
        max_version_code integer,
        updated_at       bigint NOT NULL
      );
    `);
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}
