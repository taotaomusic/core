import { Body, Controller, Get, HttpCode, HttpStatus, Post, Query, Req, UseGuards } from "@nestjs/common";
import type { Request } from "express";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { AdminAuthGuard } from "../admin-auth/admin-auth.guard";
import { AppConfigService } from "../config/app-config.service";
import { ApkService } from "./apk.service";
import { MinVersionDto, PatchRolloutDto, RemoteConfigDto, RolloutDto } from "./dto/admin.dto";
import { ReleaseRepository } from "./release.repository";
import { ReleaseService } from "./release.service";

const CONFIG_KEY_PATTERN = /^[a-z0-9_.-]{1,64}$/i;

/**
 * 「匹配所有版本」的哨兵，用于管理端列出全部配置项。
 *
 * 不能用 `Number.MAX_SAFE_INTEGER`：`min_version_code` / `max_version_code` 是 int4，
 * PostgreSQL 绑定超出范围的值会直接报 22003（SQLite 会静默比较通过）。
 * int4 上限已经远高于任何可能的 versionCode。
 */
const ALL_VERSIONS = 2_147_483_647;

/**
 * 发布管理。
 *
 * 用 [Public] 跳过访问令牌，改由 [AdminAuthGuard] 校验管理员认证：
 * 发布是运维动作，不属于任何用户会话。
 */
@Public()
@UseGuards(AdminAuthGuard)
@RateLimit("admin")
@Controller("app/admin")
export class ReleaseAdminController {
  constructor(
    private readonly config: AppConfigService,
    private readonly releases: ReleaseRepository,
    private readonly release: ReleaseService,
    private readonly apk: ApkService,
  ) {}

  @Get("releases")
  list(@Query("channel") channel?: string) {
    return this.releases.listReleases(channel ?? this.config.defaultChannel);
  }

  /**
   * 登记一个新版本。请求体是 APK 原始字节，元数据走查询参数。
   *
   * `versionCode` 与 `versionName` 必须取自构建产物的 `output-metadata.json`，
   * **不能读 `version.properties`**：`incrementVersion` 是 assemble 的 finalizedBy，
   * 构建结束时那个文件里的值已经比刚产出的 APK 大 1，登记错了客户端会陷入
   * 「提示更新 → 装完还提示」的死循环。
   */
  @Post("releases")
  @HttpCode(HttpStatus.CREATED)
  async publish(
    @Req() request: Request,
    @Query("versionCode") versionCodeParam?: string,
    @Query("versionName") versionNameParam?: string,
    @Query("channel") channelParam?: string,
    @Query("note") note?: string,
    @Query("rollout") rollout?: string,
    @Query("minSdk") minSdk?: string,
    @Query("sha256") sha256?: string,
    @Query("enabled") enabled?: string,
  ) {
    const channel = (channelParam ?? this.config.defaultChannel).trim();
    const versionCode = Number(versionCodeParam);
    const versionName = (versionNameParam ?? "").trim();
    if (!Number.isInteger(versionCode) || versionCode <= 0) {
      throw ApiErrors.badRequest(4005, "versionCode 不合法");
    }
    if (!/^\d+(\.\d+)*$/.test(versionName)) throw ApiErrors.badRequest(4005, "versionName 不合法");

    const stored = await this.apk.store(request, channel, versionCode, (sha256 ?? "").trim());
    await this.releases.upsertRelease({
      channel,
      version_code: versionCode,
      version_name: versionName,
      apk_file: stored.apkFile,
      apk_size: stored.size,
      apk_sha256: stored.sha256,
      release_note: note ?? "",
      // 默认不放量：先登记，确认无误后再逐步放开。
      rollout_percent: this.clampPercent(rollout ?? 0),
      min_sdk: this.positiveIntOr(minSdk, 24),
      enabled: enabled === "false" ? 0 : 1,
    });
    return this.releases.findRelease(channel, versionCode);
  }

  @Post("rollout")
  @HttpCode(HttpStatus.OK)
  async changeRollout(@Body() body: RolloutDto) {
    const channel = body.channel?.trim() || this.config.defaultChannel;
    if (!(await this.releases.findRelease(channel, body.versionCode))) {
      throw ApiErrors.notFound(4041, "版本不存在");
    }
    await this.releases.updateRollout(channel, body.versionCode, this.clampPercent(body.percent));
    if (body.enabled !== undefined) {
      await this.releases.setReleaseEnabled(channel, body.versionCode, body.enabled !== false);
    }
    return this.releases.findRelease(channel, body.versionCode);
  }

  /**
   * 抬高最低可用版本（强制更新下限）。
   *
   * 守卫：必须已存在放量 100% 且不低于该下限的发布，否则被判定为强制更新的客户端
   * 会被拦在门外却拿不到升级包。这里从接口层面堵住这条变砖路径。
   */
  @Post("min-version")
  @HttpCode(HttpStatus.OK)
  async setMinVersion(@Body() body: MinVersionDto) {
    const channel = body.channel?.trim() || this.config.defaultChannel;
    const rescue = await this.release.findRescueRelease(channel, body.versionCode);
    if (body.versionCode > 0 && !rescue) {
      throw ApiErrors.conflict(
        4091,
        `不存在放量 100% 且版本号不低于 ${body.versionCode} 的发布，抬高下限会让客户端无法升级`,
      );
    }
    await this.releases.setMinSupportedVersionCode(channel, body.versionCode);
    return {
      channel,
      minSupportedVersionCode: body.versionCode,
      rescueVersionCode: rescue?.version_code ?? null,
    };
  }

  @Get("config")
  listConfig() {
    return this.releases.listConfig(ALL_VERSIONS);
  }

  @Get("patches")
  listPatches(@Query("channel") channel?: string) {
    return this.releases.listPatches(channel ?? this.config.defaultChannel);
  }

  /**
   * 登记一个热修复补丁。请求体是补丁包原始字节，元数据走查询参数。
   *
   * `targetVersionCode` 必须是补丁**基于哪个已发布版本**生成的 —— 补丁里的方法签名
   * 就是那份代码的，装到别的版本上要么不生效要么直接抛 NoSuchMethodError。
   * 客户端也会再校验一次，两边都不放过。
   *
   * 与安装包一样默认不放量：先登记，自己验过再逐步放开。
   */
  @Post("patches")
  @HttpCode(HttpStatus.CREATED)
  async publishPatch(
    @Req() request: Request,
    @Query("targetVersionCode") targetVersionCodeParam?: string,
    @Query("patchVersion") patchVersionParam?: string,
    @Query("channel") channelParam?: string,
    @Query("note") note?: string,
    @Query("rollout") rollout?: string,
    @Query("sha256") sha256?: string,
  ) {
    const channel = (channelParam ?? this.config.defaultChannel).trim();
    const targetVersionCode = Number(targetVersionCodeParam);
    const patchVersion = Number(patchVersionParam);
    if (!Number.isInteger(targetVersionCode) || targetVersionCode <= 0) {
      throw ApiErrors.badRequest(4005, "targetVersionCode 不合法");
    }
    if (!Number.isInteger(patchVersion) || patchVersion <= 0) {
      throw ApiErrors.badRequest(4005, "patchVersion 不合法");
    }
    // 补丁的目标版本必须真的发布过：拿一个不存在的版本号登记补丁，
    // 客户端永远匹配不上，问题会很难查。
    if (!(await this.releases.findRelease(channel, targetVersionCode))) {
      throw ApiErrors.notFound(4041, `渠道 ${channel} 没有版本 ${targetVersionCode} 的发布记录`);
    }

    const stored = await this.apk.storePatch(
      request,
      channel,
      targetVersionCode,
      patchVersion,
      (sha256 ?? "").trim(),
    );
    await this.releases.upsertPatch({
      channel,
      target_version_code: targetVersionCode,
      patch_version: patchVersion,
      patch_file: stored.apkFile,
      patch_size: stored.size,
      patch_sha256: stored.sha256,
      note: note ?? "",
      rollout_percent: this.clampPercent(rollout ?? 0),
      enabled: 1,
    });
    return this.releases.findPatch(channel, targetVersionCode, patchVersion);
  }

  @Post("patch-rollout")
  @HttpCode(HttpStatus.OK)
  async changePatchRollout(@Body() body: PatchRolloutDto) {
    const channel = body.channel?.trim() || this.config.defaultChannel;
    if (!(await this.releases.findPatch(channel, body.targetVersionCode, body.patchVersion))) {
      throw ApiErrors.notFound(4041, "补丁不存在");
    }
    await this.releases.updatePatchRollout(
      channel,
      body.targetVersionCode,
      body.patchVersion,
      this.clampPercent(body.percent),
      body.enabled,
    );
    return this.releases.findPatch(channel, body.targetVersionCode, body.patchVersion);
  }
  @Post("config")
  @HttpCode(HttpStatus.OK)
  async changeConfig(@Body() body: RemoteConfigDto) {
    if (!CONFIG_KEY_PATTERN.test(body.key)) throw ApiErrors.badRequest(4005, "配置键不合法");
    if (body.value === null) {
      await this.releases.deleteConfig(body.key);
      return { key: body.key, removed: true };
    }
    await this.releases.upsertConfig(
      body.key,
      String(body.value ?? ""),
      body.minVersionCode ?? null,
      body.maxVersionCode ?? null,
    );
    return this.releases.listConfig(ALL_VERSIONS);
  }

  private clampPercent(value: unknown): number {
    return Math.min(100, Math.max(0, Math.trunc(Number(value) || 0)));
  }

  /** 查询参数缺省是 undefined，而 Number(undefined) 是 NaN，不能只用 Number.isInteger 判断。 */
  private positiveIntOr(value: string | undefined, fallback: number): number {
    if (value === undefined || value.trim() === "") return fallback;
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
  }
}
