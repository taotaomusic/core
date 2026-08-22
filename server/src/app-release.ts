import { createHash } from "node:crypto";
import { database } from "./database.js";

/** 一条可下发的发布记录。 */
export type ReleaseRecord = {
  id: number;
  channel: string;
  version_code: number;
  version_name: string;
  apk_file: string;
  apk_size: number;
  apk_sha256: string;
  release_note: string;
  rollout_percent: number;
  min_sdk: number;
  enabled: number;
  published_at: number;
};

database.exec(`
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

export function insertRelease(release: Omit<ReleaseRecord, "id" | "published_at">) {
  const result = database.prepare(`
    INSERT INTO app_release (channel, version_code, version_name, apk_file, apk_size, apk_sha256, release_note, rollout_percent, min_sdk, enabled, published_at)
    VALUES (@channel, @version_code, @version_name, @apk_file, @apk_size, @apk_sha256, @release_note, @rollout_percent, @min_sdk, @enabled, @published_at)
    ON CONFLICT(channel, version_code) DO UPDATE SET
      version_name = excluded.version_name, apk_file = excluded.apk_file, apk_size = excluded.apk_size,
      apk_sha256 = excluded.apk_sha256, release_note = excluded.release_note, min_sdk = excluded.min_sdk,
      enabled = excluded.enabled, published_at = excluded.published_at
  `).run({ ...release, published_at: Date.now() });
  return result.changes > 0;
}

export function findRelease(channel: string, versionCode: number) {
  return database.prepare("SELECT * FROM app_release WHERE channel = ? AND version_code = ?").get(channel, versionCode) as ReleaseRecord | undefined;
}

export function listReleases(channel: string) {
  return database.prepare("SELECT * FROM app_release WHERE channel = ? ORDER BY version_code DESC").all(channel) as ReleaseRecord[];
}

/** 候选发布：同渠道、已启用、SDK 兼容且版本号高于客户端，按版本号降序。 */
export function listUpgradeCandidates(channel: string, versionCode: number, sdk: number) {
  return database.prepare(`
    SELECT * FROM app_release
    WHERE channel = ? AND enabled = 1 AND min_sdk <= ? AND version_code > ?
    ORDER BY version_code DESC
  `).all(channel, sdk, versionCode) as ReleaseRecord[];
}

export function updateRollout(channel: string, versionCode: number, percent: number) {
  return database.prepare("UPDATE app_release SET rollout_percent = ? WHERE channel = ? AND version_code = ?").run(percent, channel, versionCode).changes > 0;
}

export function setReleaseEnabled(channel: string, versionCode: number, enabled: boolean) {
  return database.prepare("UPDATE app_release SET enabled = ? WHERE channel = ? AND version_code = ?").run(enabled ? 1 : 0, channel, versionCode).changes > 0;
}

export function minSupportedVersionCode(channel: string) {
  const row = database.prepare("SELECT min_supported_version_code AS value FROM app_channel WHERE channel = ?").get(channel) as { value: number } | undefined;
  return row?.value ?? 0;
}

export function setMinSupportedVersionCode(channel: string, versionCode: number) {
  database.prepare(`
    INSERT INTO app_channel (channel, min_supported_version_code, updated_at) VALUES (?, ?, ?)
    ON CONFLICT(channel) DO UPDATE SET min_supported_version_code = excluded.min_supported_version_code, updated_at = excluded.updated_at
  `).run(channel, versionCode, Date.now());
}

/** 取对当前客户端版本生效的配置项。 */
export function listConfig(versionCode: number) {
  return database.prepare(`
    SELECT key, value FROM app_config
    WHERE (min_version_code IS NULL OR min_version_code <= ?) AND (max_version_code IS NULL OR max_version_code >= ?)
    ORDER BY key
  `).all(versionCode, versionCode) as { key: string; value: string }[];
}

/** 配置版本号：取最大更新时间，供客户端跳过重复落盘。 */
export function configVersion() {
  const row = database.prepare("SELECT COALESCE(MAX(updated_at), 0) AS value FROM app_config").get() as { value: number };
  return row.value;
}

export function upsertConfig(key: string, value: string, minVersionCode: number | null, maxVersionCode: number | null) {
  database.prepare(`
    INSERT INTO app_config (key, value, min_version_code, max_version_code, updated_at) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(key) DO UPDATE SET value = excluded.value, min_version_code = excluded.min_version_code,
      max_version_code = excluded.max_version_code, updated_at = excluded.updated_at
  `).run(key, value, minVersionCode, maxVersionCode, Date.now());
}

export function deleteConfig(key: string) {
  return database.prepare("DELETE FROM app_config WHERE key = ?").run(key).changes > 0;
}

/**
 * 灰度分桶：对「发布 ID + 主体标识」做哈希后取模。
 *
 * 混入发布 ID 是为了让每个版本得到互相独立的分桶，否则永远是同一批用户当小白鼠；
 * 用哈希而非随机数是为了对同一用户稳定，否则每次检查结果都会翻转，更新提示时有时无。
 */
export function bucketOf(releaseId: number, subject: string) {
  return createHash("sha1").update(`${releaseId}:${subject}`).digest().readUInt32BE(0) % 100;
}
