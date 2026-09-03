import { Injectable, Logger } from "@nestjs/common";
import type { Request } from "express";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { DesktopArtifactService } from "./desktop-artifact.service";
import { DesktopDiffService } from "./desktop-diff.service";
import {
  DesktopReleaseRepository,
  type DesktopJarRecord,
  type DesktopReleaseRecord,
} from "./desktop-release.repository";

const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const VERSION_NAME_PATTERN = /^\d+(?:\.\d+){1,3}(?:[-+][a-z0-9.-]+)?$/i;
const ARCHITECTURE_PATTERN = /^[a-z0-9][a-z0-9._-]{0,31}$/i;
const CATEGORY_PATTERN = /^[a-z][a-z0-9_-]{0,31}$/i;

export type DesktopManifestInput = {
  channel?: unknown;
  versionCode?: unknown;
  versionName?: unknown;
  architecture?: unknown;
  entrypoint?: unknown;
  releaseNote?: unknown;
  rollout?: unknown;
  enabled?: unknown;
  files?: unknown;
};

type ManifestFileInput = {
  path?: unknown;
  category?: unknown;
  size?: unknown;
  sha256?: unknown;
};

export type DesktopUpdateDescriptor =
  | { available: false; forced: false; minSupportedVersionCode: number }
  | {
      available: true;
      forced: boolean;
      versionCode: number;
      versionName: string;
      entrypoint: string;
      releaseNote: string;
      totalSize: number;
      minSupportedVersionCode: number;
      files: DesktopFileDescriptor[];
    };

export type DesktopFileDescriptor = {
  path: string;
  category: string;
  size: number;
  sha256: string;
  url: string;
  patch?: {
    algorithm: string;
    fromSha256: string;
    size: number;
    sha256: string;
    url: string;
  };
};

/** Windows 模块化发布的校验、灰度解析和差分清单生成。 */
@Injectable()
export class DesktopReleaseService {
  private readonly logger = new Logger(DesktopReleaseService.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly repository: DesktopReleaseRepository,
    private readonly artifacts: DesktopArtifactService,
    private readonly diff: DesktopDiffService,
  ) {}

  async publish(body: DesktopManifestInput): Promise<DesktopReleaseRecord & { fileCount: number; totalSize: number }> {
    const manifest = this.validateManifest(body);
    // 文件先上传到内容寻址存储；这里只接受全部对象都可验证的完整清单。
    const jars = manifest.files.map((file) => ({
      path: file.path,
      category: file.category,
      artifact_file: this.artifacts.verifyObject(file.sha256, file.size),
      size: file.size,
      sha256: file.sha256,
    }));

    // 登记期间先停用，避免客户端在模块清单尚未整体替换时命中半成品。
    await this.repository.upsertRelease({
      channel: manifest.channel,
      architecture: manifest.architecture,
      version_code: manifest.versionCode,
      version_name: manifest.versionName,
      entrypoint: manifest.entrypoint,
      release_note: manifest.releaseNote,
      rollout_percent: 0,
      enabled: 0,
    });
    const release = await this.repository.findRelease(
      manifest.channel,
      manifest.architecture,
      manifest.versionCode,
    );
    if (!release) throw ApiErrors.serviceUnavailable(5032, "桌面发布记录写入失败");
    await this.repository.replaceJars(release.id, jars);

    let patchCount = 0;
    for (const jar of jars) {
      const previous = await this.repository.findPreviousJar(
        release.channel,
        release.architecture,
        release.version_code,
        jar.path,
      );
      if (!previous || previous.sha256 === jar.sha256) continue;
      const patch = await this.diff.create(previous.artifact_file, jar.artifact_file, jar.path);
      if (!patch) continue;
      await this.repository.upsertPatch({
        release_id: release.id,
        from_version_code: previous.version_code,
        path: jar.path,
        from_sha256: previous.sha256,
        to_sha256: jar.sha256,
        algorithm: patch.algorithm,
        patch_file: patch.file,
        size: patch.size,
        sha256: patch.sha256,
        enabled: 1,
      });
      patchCount++;
    }
    await this.repository.updateRollout(
      release.channel,
      release.architecture,
      release.version_code,
      manifest.rollout,
      manifest.enabled,
    );
    const completed = await this.repository.findRelease(release.channel, release.architecture, release.version_code);
    if (!completed) throw ApiErrors.serviceUnavailable(5032, "桌面发布记录读取失败");
    this.logger.log(
      `桌面版本 ${completed.version_code} 已登记：${jars.length} 个模块，生成 ${patchCount} 个差分`,
    );
    return {
      ...completed,
      fileCount: jars.length,
      totalSize: jars.reduce((sum, item) => sum + item.size, 0),
    };
  }

  async resolveUpdate(
    request: Request,
    channel: string,
    architecture: string,
    versionCode: number,
    subject: string,
  ): Promise<DesktopUpdateDescriptor> {
    const floor = await this.repository.minSupportedVersionCode(channel);
    const forced = versionCode < floor;
    const candidates = await this.repository.listUpgradeCandidates(channel, architecture, versionCode);
    const target = forced
      ? candidates.find((item) => item.rollout_percent >= 100)
      : candidates.find((item) => this.repository.bucketOf(item.id, subject) < item.rollout_percent);
    if (forced && !target) {
      this.logger.warn(`桌面渠道 ${channel} 下限 ${floor} 没有可用的全量版本，客户端 ${versionCode} 暂时放行`);
    }
    if (!target) return { available: false, forced: false, minSupportedVersionCode: floor };
    const jars = await this.repository.listJars(target.id);
    if (jars.length === 0) return { available: false, forced: false, minSupportedVersionCode: floor };
    const files = await Promise.all(
      jars.map((jar) => this.describeFile(request, target, jar, versionCode)),
    );
    return {
      available: true,
      forced,
      versionCode: target.version_code,
      versionName: target.version_name,
      entrypoint: target.entrypoint,
      releaseNote: target.release_note,
      totalSize: jars.reduce((sum, item) => sum + item.size, 0),
      minSupportedVersionCode: floor,
      files,
    };
  }

  async findRescueRelease(channel: string, architecture: string, versionCode: number) {
    const releases = await this.repository.listReleases(channel, architecture);
    for (const release of releases) {
      if (
        release.enabled === 1 &&
        release.rollout_percent >= 100 &&
        release.version_code >= versionCode &&
        (await this.repository.hasJars(release.id))
      ) return release;
    }
    return undefined;
  }

  private async describeFile(
    request: Request,
    release: DesktopReleaseRecord,
    jar: DesktopJarRecord,
    installedVersionCode: number,
  ): Promise<DesktopFileDescriptor> {
    const patch = await this.repository.findPatch(release.id, installedVersionCode, jar.path);
    return {
      path: jar.path,
      category: jar.category,
      size: jar.size,
      sha256: jar.sha256,
      url: `${this.baseUrlOf(request)}/api/v1/desktop/artifacts/${jar.sha256}`,
      ...(patch
        ? {
            patch: {
              algorithm: patch.algorithm,
              fromSha256: patch.from_sha256,
              size: patch.size,
              sha256: patch.sha256,
              url: `${this.baseUrlOf(request)}/api/v1/desktop/patches/${patch.sha256}`,
            },
          }
        : {}),
    };
  }

  private validateManifest(body: DesktopManifestInput) {
    const channel = String(body.channel ?? this.config.defaultChannel).trim().toLowerCase();
    const versionCode = Number(body.versionCode);
    const versionName = String(body.versionName ?? "").trim();
    const architecture = String(body.architecture ?? "windows-x64").trim().toLowerCase();
    const entrypoint = this.normalizePath(body.entrypoint);
    const releaseNote = String(body.releaseNote ?? "").slice(0, 4_000);
    const rollout = this.percent(body.rollout ?? 0);
    const enabled = body.enabled !== false;
    if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
      throw ApiErrors.badRequest(4005, "versionCode 不合法");
    }
    if (!ARCHITECTURE_PATTERN.test(channel)) throw ApiErrors.badRequest(4005, "channel 不合法");
    if (!VERSION_NAME_PATTERN.test(versionName)) throw ApiErrors.badRequest(4005, "versionName 不合法");
    if (!ARCHITECTURE_PATTERN.test(architecture)) throw ApiErrors.badRequest(4005, "architecture 不合法");
    if (!Array.isArray(body.files) || body.files.length === 0 || body.files.length > 512) {
      throw ApiErrors.badRequest(4005, "files 必须包含 1 至 512 个模块");
    }
    const paths = new Set<string>();
    const files = body.files.map((raw) => {
      if (!raw || typeof raw !== "object") throw ApiErrors.badRequest(4005, "模块清单项不合法");
      const item = raw as ManifestFileInput;
      const path = this.normalizePath(item.path);
      const category = String(item.category ?? "app").trim().toLowerCase();
      const size = Number(item.size);
      const sha256 = String(item.sha256 ?? "").trim().toLowerCase();
      if (paths.has(path)) throw ApiErrors.badRequest(4005, `模块路径重复：${path}`);
      if (!CATEGORY_PATTERN.test(category)) throw ApiErrors.badRequest(4005, `模块分类不合法：${category}`);
      if (!Number.isSafeInteger(size) || size <= 0 || size > 500 * 1024 * 1024) {
        throw ApiErrors.badRequest(4005, `模块大小不合法：${path}`);
      }
      if (!SHA256_PATTERN.test(sha256)) throw ApiErrors.badRequest(4005, `模块 sha256 不合法：${path}`);
      paths.add(path);
      return { path, category, size, sha256 };
    });
    if (!paths.has(entrypoint)) throw ApiErrors.badRequest(4005, "entrypoint 必须指向清单内文件");
    return { channel, versionCode, versionName, architecture, entrypoint, releaseNote, rollout, enabled, files };
  }

  private normalizePath(value: unknown): string {
    const path = String(value ?? "").trim().replace(/\\/g, "/");
    const parts = path.split("/");
    if (
      !path ||
      path.length > 240 ||
      path.startsWith("/") ||
      /^[a-z]:/i.test(path) ||
      parts.some((part) => !part || part === "." || part === "..")
    ) throw ApiErrors.badRequest(4005, `模块路径不合法：${path || "(空)"}`);
    return path;
  }

  private percent(value: unknown): number {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return 0;
    return Math.min(100, Math.max(0, Math.trunc(parsed)));
  }

  private baseUrlOf(request: Request): string {
    if (this.config.publicBaseUrl) return this.config.publicBaseUrl;
    const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
    const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
    return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}`;
  }
}
