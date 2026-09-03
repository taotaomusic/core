import { Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { DatabaseService } from "../database/database.service";

/** 一条 Windows 桌面版发布记录。 */
export type DesktopReleaseRecord = {
  id: number;
  channel: string;
  architecture: string;
  version_code: number;
  version_name: string;
  entrypoint: string;
  release_note: string;
  rollout_percent: number;
  enabled: number;
  published_at: number;
};

/** 发布清单中的一个可独立替换模块。 */
export type DesktopJarRecord = {
  id: number;
  release_id: number;
  path: string;
  category: string;
  artifact_file: string;
  size: number;
  sha256: string;
  published_at: number;
};

/** 从旧模块到目标模块的预计算二进制补丁。 */
export type DesktopPatchRecord = {
  id: number;
  release_id: number;
  from_version_code: number;
  path: string;
  from_sha256: string;
  to_sha256: string;
  algorithm: string;
  patch_file: string;
  size: number;
  sha256: string;
  enabled: number;
  published_at: number;
};

export type DesktopConfigEntry = { key: string; value: string };

export type PreviousDesktopJar = DesktopJarRecord & { version_code: number };

@Injectable()
export class DesktopReleaseRepository {
  constructor(private readonly database: DatabaseService) {}

  /** 重复登记同一版本时保留当前放量比例，避免覆盖元数据把已发布版本打回 0%。 */
  async upsertRelease(release: Omit<DesktopReleaseRecord, "id" | "published_at">): Promise<void> {
    await this.database.run(
      `INSERT INTO desktop_release
         (channel, architecture, version_code, version_name, entrypoint, release_note, rollout_percent, enabled, published_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
       ON CONFLICT (channel, architecture, version_code) DO UPDATE SET
         version_name = excluded.version_name, entrypoint = excluded.entrypoint,
         release_note = excluded.release_note, enabled = excluded.enabled,
         published_at = excluded.published_at`,
      [
        release.channel,
        release.architecture,
        release.version_code,
        release.version_name,
        release.entrypoint,
        release.release_note,
        release.rollout_percent,
        release.enabled,
        Date.now(),
      ],
    );
  }

  findRelease(
    channel: string,
    architecture: string,
    versionCode: number,
  ): Promise<DesktopReleaseRecord | undefined> {
    return this.database.first<DesktopReleaseRecord>(
      `SELECT * FROM desktop_release
       WHERE channel = $1 AND architecture = $2 AND version_code = $3`,
      [channel, architecture, versionCode],
    );
  }

  listReleases(channel: string, architecture: string): Promise<DesktopReleaseRecord[]> {
    return this.database.all<DesktopReleaseRecord>(
      `SELECT * FROM desktop_release
       WHERE channel = $1 AND architecture = $2
       ORDER BY version_code DESC`,
      [channel, architecture],
    );
  }

  listUpgradeCandidates(
    channel: string,
    architecture: string,
    versionCode: number,
  ): Promise<DesktopReleaseRecord[]> {
    return this.database.all<DesktopReleaseRecord>(
      `SELECT * FROM desktop_release
       WHERE channel = $1 AND architecture = $2 AND enabled = 1 AND version_code > $3
       ORDER BY version_code DESC`,
      [channel, architecture, versionCode],
    );
  }

  async updateRollout(
    channel: string,
    architecture: string,
    versionCode: number,
    percent: number,
    enabled?: boolean,
  ): Promise<boolean> {
    const affected = await this.database.run(
      `UPDATE desktop_release
       SET rollout_percent = $1, enabled = COALESCE($2, enabled)
       WHERE channel = $3 AND architecture = $4 AND version_code = $5`,
      [percent, enabled === undefined ? null : enabled ? 1 : 0, channel, architecture, versionCode],
    );
    return affected > 0;
  }

  async minSupportedVersionCode(channel: string): Promise<number> {
    const row = await this.database.first<{ value: number }>(
      `SELECT desktop_min_supported_version_code AS value
       FROM app_channel WHERE channel = $1`,
      [channel],
    );
    return row?.value ?? 0;
  }

  async setMinSupportedVersionCode(channel: string, versionCode: number): Promise<void> {
    await this.database.run(
      `INSERT INTO app_channel
         (channel, min_supported_version_code, desktop_min_supported_version_code, updated_at)
       VALUES ($1, 0, $2, $3)
       ON CONFLICT (channel) DO UPDATE SET
         desktop_min_supported_version_code = excluded.desktop_min_supported_version_code,
         updated_at = excluded.updated_at`,
      [channel, versionCode, Date.now()],
    );
  }

  listConfig(versionCode: number): Promise<DesktopConfigEntry[]> {
    return this.database.all<DesktopConfigEntry>(
      `SELECT key, value FROM app_config
       WHERE (min_version_code IS NULL OR min_version_code <= $1)
         AND (max_version_code IS NULL OR max_version_code >= $1)
       ORDER BY key`,
      [versionCode],
    );
  }

  async configVersion(): Promise<number> {
    const row = await this.database.first<{ value: number }>(
      "SELECT COALESCE(MAX(updated_at), 0) AS value FROM app_config",
    );
    return row?.value ?? 0;
  }

  /** 与 Android 发布完全相同的稳定灰度算法。必须保持同步纯计算。 */
  bucketOf(releaseId: number, subject: string): number {
    return createHash("sha1").update(`${releaseId}:${subject}`).digest().readUInt32BE(0) % 100;
  }

  async upsertJar(jar: Omit<DesktopJarRecord, "id" | "published_at">): Promise<void> {
    await this.database.run(
      `INSERT INTO desktop_jar
         (release_id, path, category, artifact_file, size, sha256, published_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       ON CONFLICT (release_id, path) DO UPDATE SET
         category = excluded.category, artifact_file = excluded.artifact_file,
         size = excluded.size, sha256 = excluded.sha256, published_at = excluded.published_at`,
      [jar.release_id, jar.path, jar.category, jar.artifact_file, jar.size, jar.sha256, Date.now()],
    );
  }

  /** 同一版本的模块清单整体替换，避免重传时留下已经删除的旧路径。 */
  async replaceJars(
    releaseId: number,
    jars: Array<Omit<DesktopJarRecord, "id" | "published_at" | "release_id">>,
  ): Promise<void> {
    await this.database.transaction(async (client) => {
      await client.query("DELETE FROM desktop_patch WHERE release_id = $1", [releaseId]);
      await client.query("DELETE FROM desktop_jar WHERE release_id = $1", [releaseId]);
      for (const jar of jars) {
        await client.query(
          `INSERT INTO desktop_jar
             (release_id, path, category, artifact_file, size, sha256, published_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7)`,
          [releaseId, jar.path, jar.category, jar.artifact_file, jar.size, jar.sha256, Date.now()],
        );
      }
    });
  }

  findJar(releaseId: number, path: string): Promise<DesktopJarRecord | undefined> {
    return this.database.first<DesktopJarRecord>(
      "SELECT * FROM desktop_jar WHERE release_id = $1 AND path = $2",
      [releaseId, path],
    );
  }

  listJars(releaseId: number): Promise<DesktopJarRecord[]> {
    return this.database.all<DesktopJarRecord>(
      "SELECT * FROM desktop_jar WHERE release_id = $1 ORDER BY path",
      [releaseId],
    );
  }

  /** 自动差分只取最近的旧版本；更老客户端始终可以回退到完整 JAR。 */
  findPreviousJar(
    channel: string,
    architecture: string,
    targetVersionCode: number,
    path: string,
  ): Promise<PreviousDesktopJar | undefined> {
    return this.database.first<PreviousDesktopJar>(
      `SELECT jar.*, release.version_code
       FROM desktop_jar jar
       JOIN desktop_release release ON release.id = jar.release_id
       WHERE release.channel = $1 AND release.architecture = $2
         AND release.enabled = 1 AND release.version_code < $3 AND jar.path = $4
       ORDER BY release.version_code DESC
       LIMIT 1`,
      [channel, architecture, targetVersionCode, path],
    );
  }

  /** 只有至少登记一个模块的版本才允许开始放量。 */
  async hasJars(releaseId: number): Promise<boolean> {
    const row = await this.database.first<{ value: number }>(
      "SELECT COUNT(*)::integer AS value FROM desktop_jar WHERE release_id = $1",
      [releaseId],
    );
    return (row?.value ?? 0) > 0;
  }

  async upsertPatch(patch: Omit<DesktopPatchRecord, "id" | "published_at">): Promise<void> {
    await this.database.run(
      `INSERT INTO desktop_patch
         (release_id, from_version_code, path, from_sha256, to_sha256, algorithm,
          patch_file, size, sha256, enabled, published_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
       ON CONFLICT (release_id, from_version_code, path, algorithm) DO UPDATE SET
         from_sha256 = excluded.from_sha256, to_sha256 = excluded.to_sha256,
         patch_file = excluded.patch_file, size = excluded.size, sha256 = excluded.sha256,
         enabled = excluded.enabled, published_at = excluded.published_at`,
      [
        patch.release_id,
        patch.from_version_code,
        patch.path,
        patch.from_sha256,
        patch.to_sha256,
        patch.algorithm,
        patch.patch_file,
        patch.size,
        patch.sha256,
        patch.enabled,
        Date.now(),
      ],
    );
  }

  listPatches(releaseId: number): Promise<DesktopPatchRecord[]> {
    return this.database.all<DesktopPatchRecord>(
      `SELECT * FROM desktop_patch
       WHERE release_id = $1 AND enabled = 1
       ORDER BY path, from_version_code DESC`,
      [releaseId],
    );
  }

  findPatch(
    releaseId: number,
    fromVersionCode: number,
    path: string,
  ): Promise<DesktopPatchRecord | undefined> {
    return this.database.first<DesktopPatchRecord>(
      `SELECT * FROM desktop_patch
       WHERE release_id = $1 AND from_version_code = $2 AND path = $3 AND enabled = 1
       ORDER BY CASE algorithm WHEN 'courgette' THEN 0 ELSE 1 END, size ASC
       LIMIT 1`,
      [releaseId, fromVersionCode, path],
    );
  }

  findDownloadableJar(sha256: string): Promise<DesktopJarRecord | undefined> {
    return this.database.first<DesktopJarRecord>(
      `SELECT jar.* FROM desktop_jar jar
       JOIN desktop_release release ON release.id = jar.release_id
       WHERE jar.sha256 = $1 AND release.enabled = 1
       ORDER BY release.version_code DESC LIMIT 1`,
      [sha256],
    );
  }

  findDownloadablePatch(sha256: string): Promise<DesktopPatchRecord | undefined> {
    return this.database.first<DesktopPatchRecord>(
      `SELECT patch.* FROM desktop_patch patch
       JOIN desktop_release release ON release.id = patch.release_id
       WHERE patch.sha256 = $1 AND patch.enabled = 1 AND release.enabled = 1
       ORDER BY release.version_code DESC LIMIT 1`,
      [sha256],
    );
  }
}
