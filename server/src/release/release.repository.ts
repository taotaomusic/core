import { Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { DatabaseService } from "../database/database.service";

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

export type ConfigEntry = { key: string; value: string };

@Injectable()
export class ReleaseRepository {
  constructor(private readonly database: DatabaseService) {}

  upsertRelease(release: Omit<ReleaseRecord, "id" | "published_at">): void {
    this.database.connection
      .prepare(
        `INSERT INTO app_release (channel, version_code, version_name, apk_file, apk_size, apk_sha256, release_note, rollout_percent, min_sdk, enabled, published_at)
         VALUES (@channel, @version_code, @version_name, @apk_file, @apk_size, @apk_sha256, @release_note, @rollout_percent, @min_sdk, @enabled, @published_at)
         ON CONFLICT(channel, version_code) DO UPDATE SET
           version_name = excluded.version_name, apk_file = excluded.apk_file, apk_size = excluded.apk_size,
           apk_sha256 = excluded.apk_sha256, release_note = excluded.release_note, min_sdk = excluded.min_sdk,
           enabled = excluded.enabled, published_at = excluded.published_at`,
      )
      .run({ ...release, published_at: Date.now() });
  }

  findRelease(channel: string, versionCode: number): ReleaseRecord | undefined {
    return this.database.connection
      .prepare("SELECT * FROM app_release WHERE channel = ? AND version_code = ?")
      .get(channel, versionCode) as ReleaseRecord | undefined;
  }

  listReleases(channel: string): ReleaseRecord[] {
    return this.database.connection
      .prepare("SELECT * FROM app_release WHERE channel = ? ORDER BY version_code DESC")
      .all(channel) as ReleaseRecord[];
  }

  /** 候选发布：同渠道、已启用、SDK 兼容且版本号高于客户端，按版本号降序。 */
  listUpgradeCandidates(channel: string, versionCode: number, sdk: number): ReleaseRecord[] {
    return this.database.connection
      .prepare(
        `SELECT * FROM app_release
         WHERE channel = ? AND enabled = 1 AND min_sdk <= ? AND version_code > ?
         ORDER BY version_code DESC`,
      )
      .all(channel, sdk, versionCode) as ReleaseRecord[];
  }

  updateRollout(channel: string, versionCode: number, percent: number): boolean {
    return (
      this.database.connection
        .prepare("UPDATE app_release SET rollout_percent = ? WHERE channel = ? AND version_code = ?")
        .run(percent, channel, versionCode).changes > 0
    );
  }

  setReleaseEnabled(channel: string, versionCode: number, enabled: boolean): void {
    this.database.connection
      .prepare("UPDATE app_release SET enabled = ? WHERE channel = ? AND version_code = ?")
      .run(enabled ? 1 : 0, channel, versionCode);
  }

  minSupportedVersionCode(channel: string): number {
    const row = this.database.connection
      .prepare("SELECT min_supported_version_code AS value FROM app_channel WHERE channel = ?")
      .get(channel) as { value: number } | undefined;
    return row?.value ?? 0;
  }

  setMinSupportedVersionCode(channel: string, versionCode: number): void {
    this.database.connection
      .prepare(
        `INSERT INTO app_channel (channel, min_supported_version_code, updated_at) VALUES (?, ?, ?)
         ON CONFLICT(channel) DO UPDATE SET
           min_supported_version_code = excluded.min_supported_version_code, updated_at = excluded.updated_at`,
      )
      .run(channel, versionCode, Date.now());
  }

  /** 取对当前客户端版本生效的配置项。 */
  listConfig(versionCode: number): ConfigEntry[] {
    return this.database.connection
      .prepare(
        `SELECT key, value FROM app_config
         WHERE (min_version_code IS NULL OR min_version_code <= ?) AND (max_version_code IS NULL OR max_version_code >= ?)
         ORDER BY key`,
      )
      .all(versionCode, versionCode) as ConfigEntry[];
  }

  /** 配置版本号：取最大更新时间，供客户端跳过重复落盘。 */
  configVersion(): number {
    const row = this.database.connection
      .prepare("SELECT COALESCE(MAX(updated_at), 0) AS value FROM app_config")
      .get() as { value: number };
    return row.value;
  }

  upsertConfig(key: string, value: string, minVersionCode: number | null, maxVersionCode: number | null): void {
    this.database.connection
      .prepare(
        `INSERT INTO app_config (key, value, min_version_code, max_version_code, updated_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value, min_version_code = excluded.min_version_code,
           max_version_code = excluded.max_version_code, updated_at = excluded.updated_at`,
      )
      .run(key, value, minVersionCode, maxVersionCode, Date.now());
  }

  deleteConfig(key: string): boolean {
    return this.database.connection.prepare("DELETE FROM app_config WHERE key = ?").run(key).changes > 0;
  }

  /**
   * 灰度分桶：对「发布 ID + 主体标识」做哈希后取模。
   *
   * 混入发布 ID 是为了让每个版本得到互相独立的分桶，否则永远是同一批用户当小白鼠；
   * 用哈希而非随机数是为了对同一用户稳定，否则每次检查结果都会翻转，更新提示时有时无。
   */
  bucketOf(releaseId: number, subject: string): number {
    return createHash("sha1").update(`${releaseId}:${subject}`).digest().readUInt32BE(0) % 100;
  }
}
