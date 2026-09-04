import { Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { DatabaseService } from "../database/database.service";
import { LatestVersionCache } from "./latest-version.cache";

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

/** 一条代码热修复补丁。 */
export type PatchRecord = {
  id: number;
  channel: string;
  target_version_code: number;
  patch_version: number;
  patch_file: string;
  patch_size: number;
  patch_sha256: string;
  note: string;
  rollout_percent: number;
  enabled: number;
  published_at: number;
};

@Injectable()
export class ReleaseRepository {
  constructor(
    private readonly database: DatabaseService,
    private readonly latestVersion: LatestVersionCache,
  ) {}

  /**
   * 当前**全量可用**的最高版本号,供响应头下发。
   *
   * 必须带 `rollout_percent >= 100` 这个条件,不能只取 `enabled = 1` 的最大值:
   * 那样会广告一个灰度版本,而不在灰度名单里的客户端据此提示更新后,
   * 调 `/app/bootstrap` 只会拿到 `available:false` —— 提示永远消不掉。
   * 灰度版本仍然由 bootstrap 正常发现,只是不进这个头。
   *
   * 走 `idx_app_release_lookup (channel, enabled, version_code DESC)`,是一次索引取值;
   * 结果缓存在内存里,由本类的写入方法失效。
   */
  async latestFullyRolledOut(channel: string): Promise<number | null> {
    const cached = this.latestVersion.get();
    if (cached !== undefined) return cached;
    const row = await this.database.first<{ value: number | null }>(
      `SELECT MAX(version_code) AS value FROM app_release
       WHERE channel = $1 AND enabled = 1 AND rollout_percent >= 100`,
      [channel],
    );
    const value = row?.value ?? null;
    this.latestVersion.set(value);
    return value;
  }

  /**
   * 登记或更新一个版本。
   *
   * `DO UPDATE SET` 里**刻意没有 `rollout_percent`**：重新上传同一个版本号时
   * 要保留当前的放量比例，否则一次覆盖发布会把已经放到 100% 的版本打回 0。
   */
  async upsertRelease(release: Omit<ReleaseRecord, "id" | "published_at">): Promise<void> {
    await this.database.run(
      `INSERT INTO app_release (channel, version_code, version_name, apk_file, apk_size, apk_sha256, release_note, rollout_percent, min_sdk, enabled, published_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
       ON CONFLICT (channel, version_code) DO UPDATE SET
         version_name = excluded.version_name, apk_file = excluded.apk_file, apk_size = excluded.apk_size,
         apk_sha256 = excluded.apk_sha256, release_note = excluded.release_note, min_sdk = excluded.min_sdk,
         enabled = excluded.enabled, published_at = excluded.published_at`,
      [
        release.channel,
        release.version_code,
        release.version_name,
        release.apk_file,
        release.apk_size,
        release.apk_sha256,
        release.release_note,
        release.rollout_percent,
        release.min_sdk,
        release.enabled,
        Date.now(),
      ],
    );
    this.latestVersion.invalidate();
  }

  findRelease(channel: string, versionCode: number): Promise<ReleaseRecord | undefined> {
    return this.database.first<ReleaseRecord>(
      "SELECT * FROM app_release WHERE channel = $1 AND version_code = $2",
      [channel, versionCode],
    );
  }

  listReleases(channel: string): Promise<ReleaseRecord[]> {
    return this.database.all<ReleaseRecord>(
      "SELECT * FROM app_release WHERE channel = $1 ORDER BY version_code DESC",
      [channel],
    );
  }

  /** 候选发布：同渠道、已启用、SDK 兼容且版本号高于客户端，按版本号降序。 */
  listUpgradeCandidates(channel: string, versionCode: number, sdk: number): Promise<ReleaseRecord[]> {
    return this.database.all<ReleaseRecord>(
      `SELECT * FROM app_release
       WHERE channel = $1 AND enabled = 1 AND min_sdk <= $2 AND version_code > $3
       ORDER BY version_code DESC`,
      [channel, sdk, versionCode],
    );
  }

  async updateRollout(channel: string, versionCode: number, percent: number): Promise<boolean> {
    const affected = await this.database.run(
      "UPDATE app_release SET rollout_percent = $1 WHERE channel = $2 AND version_code = $3",
      [percent, channel, versionCode],
    );
    this.latestVersion.invalidate();
    return affected > 0;
  }

  async setReleaseEnabled(channel: string, versionCode: number, enabled: boolean): Promise<void> {
    await this.database.run("UPDATE app_release SET enabled = $1 WHERE channel = $2 AND version_code = $3", [
      enabled ? 1 : 0,
      channel,
      versionCode,
    ]);
    this.latestVersion.invalidate();
  }

  async minSupportedVersionCode(channel: string): Promise<number> {
    const row = await this.database.first<{ value: number }>(
      "SELECT min_supported_version_code AS value FROM app_channel WHERE channel = $1",
      [channel],
    );
    return row?.value ?? 0;
  }

  async setMinSupportedVersionCode(channel: string, versionCode: number): Promise<void> {
    await this.database.run(
      `INSERT INTO app_channel (channel, min_supported_version_code, updated_at) VALUES ($1, $2, $3)
       ON CONFLICT (channel) DO UPDATE SET
         min_supported_version_code = excluded.min_supported_version_code, updated_at = excluded.updated_at`,
      [channel, versionCode, Date.now()],
    );
  }

  /** 取对当前客户端版本生效的配置项。上下界比较用的是同一个参数。 */
  listConfig(versionCode: number): Promise<ConfigEntry[]> {
    return this.database.all<ConfigEntry>(
      `SELECT key, value FROM app_config
       WHERE (min_version_code IS NULL OR min_version_code <= $1) AND (max_version_code IS NULL OR max_version_code >= $1)
       ORDER BY key`,
      [versionCode],
    );
  }

  /** 配置版本号：取最大更新时间，供客户端跳过重复落盘。 */
  async configVersion(): Promise<number> {
    const row = await this.database.first<{ value: number }>(
      "SELECT COALESCE(MAX(updated_at), 0) AS value FROM app_config",
    );
    return row?.value ?? 0;
  }

  async upsertConfig(
    key: string,
    value: string,
    minVersionCode: number | null,
    maxVersionCode: number | null,
  ): Promise<void> {
    await this.database.run(
      `INSERT INTO app_config (key, value, min_version_code, max_version_code, updated_at) VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (key) DO UPDATE SET value = excluded.value, min_version_code = excluded.min_version_code,
         max_version_code = excluded.max_version_code, updated_at = excluded.updated_at`,
      [key, value, minVersionCode, maxVersionCode, Date.now()],
    );
  }

  async deleteConfig(key: string): Promise<boolean> {
    return (await this.database.run("DELETE FROM app_config WHERE key = $1", [key])) > 0;
  }

  /**
   * 灰度分桶：对「发布 ID + 主体标识」做哈希后取模。
   *
   * 混入发布 ID 是为了让每个版本得到互相独立的分桶，否则永远是同一批用户当小白鼠；
   * 用哈希而非随机数是为了对同一用户稳定，否则每次检查结果都会翻转，更新提示时有时无。
   *
   * **必须保持同步。** `ReleaseService.resolveUpdate` 在 `Array.prototype.find` 的回调里
   * 调它，一旦返回 Promise，回调恒为真值，`.find()` 会命中第一个候选版本，
   * 灰度直接失效变成全量下发。
   */
  bucketOf(releaseId: number, subject: string): number {
    return createHash("sha1").update(`${releaseId}:${subject}`).digest().readUInt32BE(0) % 100;
  }

  /**
   * 给这个宿主版本可下发的补丁，按补丁版本降序。
   *
   * 匹配条件是 `target_version_code` **精确相等** —— 补丁里的方法签名是按那一份代码
   * 生成的，装到别的版本上会因为找不到方法而失效，甚至挂掉。
   */
  listPatchCandidates(channel: string, versionCode: number): Promise<PatchRecord[]> {
    return this.database.all<PatchRecord>(
      `SELECT * FROM app_patch
       WHERE channel = $1 AND target_version_code = $2 AND enabled = 1
       ORDER BY patch_version DESC`,
      [channel, versionCode],
    );
  }

  /** 响应头用：只提示所有用户都可拿到的最高补丁，灰度仍以 bootstrap 的分桶为准。 */
  async latestFullyRolledOutPatch(channel: string, versionCode: number): Promise<number | null> {
    const cached = this.latestVersion.patchFor(versionCode);
    if (cached !== undefined) return cached;
    const row = await this.database.first<{ value: number | null }>(
      `SELECT MAX(patch_version) AS value FROM app_patch
       WHERE channel = $1 AND target_version_code = $2 AND enabled = 1 AND rollout_percent >= 100`,
      [channel, versionCode],
    );
    const value = row?.value ?? null;
    this.latestVersion.setPatch(versionCode, value);
    return value;
  }

  findPatch(channel: string, targetVersionCode: number, patchVersion: number): Promise<PatchRecord | undefined> {
    return this.database.first<PatchRecord>(
      "SELECT * FROM app_patch WHERE channel = $1 AND target_version_code = $2 AND patch_version = $3",
      [channel, targetVersionCode, patchVersion],
    );
  }

  listPatches(channel: string): Promise<PatchRecord[]> {
    return this.database.all<PatchRecord>(
      "SELECT * FROM app_patch WHERE channel = $1 ORDER BY target_version_code DESC, patch_version DESC",
      [channel],
    );
  }

  /** 登记或覆盖一个补丁。与 upsertRelease 一样刻意不覆盖 rollout_percent。 */
  async upsertPatch(patch: Omit<PatchRecord, "id" | "published_at">): Promise<void> {
    await this.database.run(
      `INSERT INTO app_patch (channel, target_version_code, patch_version, patch_file, patch_size, patch_sha256, note, rollout_percent, enabled, published_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
       ON CONFLICT (channel, target_version_code, patch_version) DO UPDATE SET
         patch_file = excluded.patch_file, patch_size = excluded.patch_size,
         patch_sha256 = excluded.patch_sha256, note = excluded.note,
         enabled = excluded.enabled, published_at = excluded.published_at`,
      [
        patch.channel,
        patch.target_version_code,
        patch.patch_version,
        patch.patch_file,
        patch.patch_size,
        patch.patch_sha256,
        patch.note,
        patch.rollout_percent,
        patch.enabled,
        Date.now(),
      ],
    );
    this.latestVersion.invalidate();
  }

  async updatePatchRollout(
    channel: string,
    targetVersionCode: number,
    patchVersion: number,
    percent: number,
    enabled?: boolean,
  ): Promise<boolean> {
    const affected = await this.database.run(
      `UPDATE app_patch SET rollout_percent = $1, enabled = COALESCE($2, enabled)
       WHERE channel = $3 AND target_version_code = $4 AND patch_version = $5`,
      [percent, enabled === undefined ? null : enabled ? 1 : 0, channel, targetVersionCode, patchVersion],
    );
    this.latestVersion.invalidate();
    return affected > 0;
  }
}
