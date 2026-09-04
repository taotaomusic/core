import { Injectable, Logger } from "@nestjs/common";
import type { Request } from "express";
import { AppConfigService } from "../config/app-config.service";
import { ReleaseRepository, type ReleaseRecord } from "./release.repository";

export type UpdateDescriptor =
  | { available: false; forced: false; minSupportedVersionCode: number }
  | {
      available: true;
      forced: boolean;
      versionCode: number;
      versionName: string;
      apkUrl: string;
      apkSize: number;
      apkSha256: string;
      releaseNote: string;
      minSupportedVersionCode: number;
    };

export type PatchDescriptor =
  | { available: false }
  | {
      available: true;
      patchVersion: number;
      targetVersionCode: number;
      url: string;
      size: number;
      sha256: string;
      note: string;
    };

@Injectable()
export class ReleaseService {
  private readonly logger = new Logger(ReleaseService.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly releases: ReleaseRepository,
  ) {}

  /**
   * 版本判定。
   *
   * 强制更新的目标一律取放量 100% 的版本，**绝不返回灰度包**：否则抬高最低可用版本后，
   * 不在灰度名单里的客户端会被拦住却拿不到升级包，直接变砖。
   */
  async resolveUpdate(
    request: Request,
    channel: string,
    versionCode: number,
    sdk: number,
    subject: string,
  ): Promise<UpdateDescriptor> {
    const floor = await this.releases.minSupportedVersionCode(channel);
    const forced = versionCode < floor;
    const candidates = await this.releases.listUpgradeCandidates(channel, versionCode, sdk);
    // bucketOf 是同步的纯计算，所以下面这个 find 回调可以照常写。
    // 若它哪天变成 async，回调返回的 Promise 恒为真值，find 会命中第一个候选版本，
    // 灰度会静默失效成全量下发。
    const target = forced
      ? candidates.find((item) => item.rollout_percent >= 100)
      : candidates.find((item) => this.releases.bucketOf(item.id, subject) < item.rollout_percent);

    if (forced && !target) {
      this.logger.warn(
        `渠道 ${channel} 的最低可用版本为 ${floor}，但没有可下发的全量版本，客户端 ${versionCode} 将被放行`,
      );
    }
    if (!target) return { available: false, forced: false, minSupportedVersionCode: floor };

    return {
      available: true,
      forced,
      versionCode: target.version_code,
      versionName: target.version_name,
      apkUrl: this.apkUrlOf(request, channel, target.version_code),
      apkSize: target.apk_size,
      apkSha256: target.apk_sha256,
      releaseNote: target.release_note,
      minSupportedVersionCode: floor,
    };
  }

  /**
   * 抬高最低可用版本前的守卫。
   *
   * 必须已经存在放量 100% 且不低于该下限的发布，否则被判定为强制更新的客户端
   * 会被拦在门外却拿不到升级包。从接口层面堵住这条变砖路径。
   */
  async findRescueRelease(channel: string, versionCode: number): Promise<ReleaseRecord | undefined> {
    const releases = await this.releases.listReleases(channel);
    return releases.find(
      (item) => item.enabled === 1 && item.rollout_percent >= 100 && item.version_code >= versionCode,
    );
  }

  /**
   * 这个宿主版本该拿哪个补丁。
   *
   * 只在**没有可用整包更新**时才下发补丁：既然能装新版本，就没必要再打补丁 ——
   * 补丁只是给"来不及发版或用户还没升级"的场景兜底。
   *
   * 分桶复用发布那套（`bucketOf` 是同步的纯计算，可以在 find 回调里调），
   * 只是主体标识里混的是补丁 ID 而不是发布 ID，两者的灰度名单互相独立。
   */
  async resolvePatch(
    request: Request,
    channel: string,
    versionCode: number,
    subject: string,
  ): Promise<PatchDescriptor> {
    const candidates = await this.releases.listPatchCandidates(channel, versionCode);
    const target = candidates.find((item) => this.releases.bucketOf(item.id, subject) < item.rollout_percent);
    if (!target) return { available: false };
    return {
      available: true,
      patchVersion: target.patch_version,
      targetVersionCode: target.target_version_code,
      url: this.patchUrlOf(request, channel, target.target_version_code, target.patch_version),
      size: target.patch_size,
      sha256: target.patch_sha256,
      note: target.note,
    };
  }

  private patchUrlOf(request: Request, channel: string, targetVersionCode: number, patchVersion: number): string {
    const query = channel === this.config.defaultChannel ? "" : `?channel=${encodeURIComponent(channel)}`;
    return `${this.baseUrlOf(request)}/api/v1/app/patch/${targetVersionCode}/${patchVersion}${query}`;
  }

  /** 下载地址优先用配置的对外基地址；未配置时按请求推导，TLS 由 socket 或代理头判断。 */
  private apkUrlOf(request: Request, channel: string, versionCode: number): string {
    const query = channel === this.config.defaultChannel ? "" : `?channel=${encodeURIComponent(channel)}`;
    return `${this.baseUrlOf(request)}/api/v1/app/apk/${versionCode}${query}`;
  }

  private baseUrlOf(request: Request): string {
    if (this.config.publicBaseUrl) return this.config.publicBaseUrl;
    const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
    const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
    return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}`;
  }
}
