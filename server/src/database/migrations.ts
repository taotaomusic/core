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

      -- 旧账号没有邮箱也仍可登录；新注册账号必须在插入时写入已验证邮箱。
      ALTER TABLE users ADD COLUMN IF NOT EXISTS email text;
      CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_unique
        ON users (email) WHERE email IS NOT NULL;
      ALTER TABLE users ADD COLUMN IF NOT EXISTS nickname text;
      ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url text;

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

      CREATE TABLE IF NOT EXISTS api_key (
        id      integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        channel text NOT NULL,
        key     text NOT NULL CHECK (btrim(key) <> ''),
        quota   integer NOT NULL DEFAULT 0 CHECK (quota >= 0),
        UNIQUE (channel, key)
      );
      CREATE INDEX IF NOT EXISTS idx_api_key_available ON api_key (channel, quota DESC);

      CREATE TABLE IF NOT EXISTS image_generation_task (
        task_id        text PRIMARY KEY,
        prompt         text NOT NULL,
        consumed_quota integer NOT NULL CHECK (consumed_quota > 0),
        channel        text NOT NULL,
        state          text NOT NULL DEFAULT 'IN_PROGRESS'
                       CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
        completed      smallint NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
        image_url      text,
        api_key_id     integer NOT NULL REFERENCES api_key(id) ON DELETE RESTRICT
      );
      CREATE INDEX IF NOT EXISTS idx_image_generation_task_key ON image_generation_task (api_key_id);

      -- 代码热修复补丁。与 app_release 并列而不是复用它：
      -- 补丁只对**某一个** versionCode 的宿主有效（方法签名是按那份代码生成的），
      -- 而发布记录是"版本号高于你就能装"，两者的匹配语义正好相反。
      CREATE TABLE IF NOT EXISTS app_patch (
        id                  integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        channel             text NOT NULL DEFAULT 'release',
        target_version_code integer NOT NULL,
        patch_version       integer NOT NULL,
        patch_file          text NOT NULL,
        patch_size          integer NOT NULL,
        patch_sha256        text NOT NULL,
        note                text NOT NULL DEFAULT '',
        rollout_percent     integer NOT NULL DEFAULT 0,
        enabled             smallint NOT NULL DEFAULT 1,
        published_at        bigint NOT NULL,
        UNIQUE (channel, target_version_code, patch_version)
      );
      CREATE INDEX IF NOT EXISTS idx_app_patch_lookup
        ON app_patch (channel, target_version_code, enabled, patch_version DESC);
    `);
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}
