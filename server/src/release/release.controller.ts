import { Controller, Get, Head, Param, ParseIntPipe, Query, Req, Res } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import { clientAddressOf, type SessionUser } from "../common/request.types";
import { AppConfigService } from "../config/app-config.service";
import { ApkService } from "./apk.service";
import { ReleaseRepository } from "./release.repository";
import { ReleaseService } from "./release.service";

/**
 * 客户端引导与安装包下载。
 *
 * 整组接口**公开**，理由是最需要强制更新的场景恰恰是「上一个版本把登录搞坏了」——
 * 若检查接口自己要求登录，这些客户端永远收不到升级通知。
 */
@Controller("app")
export class ReleaseController {
  constructor(
    private readonly config: AppConfigService,
    private readonly releases: ReleaseRepository,
    private readonly release: ReleaseService,
    private readonly apk: ApkService,
  ) {}

  /**
   * 一次返回更新信息与远程配置，减少启动时的往返。
   *
   * 客户端刻意用 `currentToken()` 而不是 `validToken()` 来调用，所以**会带着已过期的
   * 令牌**来。这里必须容忍并降级为按设备号分桶，**绝不能返回 401** ——
   * 客户端会把异常静默吞成「已是最新」，热更新通道就永久失效且没有任何报错。
   */
  @Public()
  @RateLimit("app")
  @Get("bootstrap")
  async bootstrap(
    @Req() request: Request,
    @CurrentUser() user: SessionUser | undefined,
    @Query("channel") channel?: string,
    @Query("versionCode") versionCode?: string,
    @Query("sdk") sdk?: string,
    @Query("deviceId") deviceId?: string,
  ) {
    const currentChannel = channel ?? this.config.defaultChannel;
    const installedVersion = Number(versionCode ?? 0);
    const sdkLevel = Number(sdk ?? 0);
    if (!Number.isInteger(installedVersion) || installedVersion <= 0) {
      throw ApiErrors.badRequest(4005, "versionCode 不合法");
    }
    if (!Number.isInteger(sdkLevel) || sdkLevel <= 0) throw ApiErrors.badRequest(4005, "sdk 不合法");

    const device = String(deviceId ?? "").slice(0, 64);
    // 带了有效令牌就按用户分桶，未登录或令牌过期时退回匿名设备号，
    // 保证同一个客户端在整个灰度周期里稳定落在同一个桶。
    const subject = user ? `user:${user.id}` : `device:${device || clientAddressOf(request)}`;

    const [update, config, configVersion] = await Promise.all([
      this.release.resolveUpdate(request, currentChannel, installedVersion, sdkLevel, subject),
      this.releases.listConfig(installedVersion),
      this.releases.configVersion(),
    ]);
    // 有整包更新时不下发补丁：既然能装新版本，就没必要再打补丁。
    const patch = update.available
      ? { available: false as const }
      : await this.release.resolvePatch(request, currentChannel, installedVersion, subject);
    return {
      update,
      patch,
      config: Object.fromEntries(config.map((item) => [item.key, item.value])),
      configVersion,
    };
  }

  /**
   * 下载热修复补丁。
   *
   * 与安装包下载同样是公开的：补丁要修的可能正是登录本身。
   * 复用 [ApkService] 的文件下发逻辑（Range、ETag、路径穿越防护都在里面）。
   */
  @Public()
  @RateLimit("app")
  @RawResponse()
  @Get("patch/:targetVersionCode/:patchVersion")
  async downloadPatch(
    @Req() request: Request,
    @Res() response: Response,
    @Param("targetVersionCode", ParseIntPipe) targetVersionCode: number,
    @Param("patchVersion", ParseIntPipe) patchVersion: number,
    @Query("channel") channel?: string,
  ): Promise<void> {
    const patch = await this.releases.findPatch(
      channel ?? this.config.defaultChannel,
      targetVersionCode,
      patchVersion,
    );
    if (!patch || !patch.enabled) throw ApiErrors.notFound(4041, "补丁不存在");
    this.apk.downloadFile(request, response, patch.patch_file, patch.patch_sha256, `patch-${patchVersion}.apk`);
  }

  @Public()
  @RateLimit("app")
  @RawResponse()
  @Get("apk/:versionCode")
  download(
    @Req() request: Request,
    @Res() response: Response,
    @Param("versionCode", ParseIntPipe) versionCode: number,
    @Query("channel") channel?: string,
  ): Promise<void> {
    return this.serve(request, response, versionCode, channel);
  }

  /** 客户端不发 HEAD，但保留它便于用 curl 排查。 */
  @Public()
  @RateLimit("app")
  @RawResponse()
  @Head("apk/:versionCode")
  head(
    @Req() request: Request,
    @Res() response: Response,
    @Param("versionCode", ParseIntPipe) versionCode: number,
    @Query("channel") channel?: string,
  ): Promise<void> {
    return this.serve(request, response, versionCode, channel);
  }

  private async serve(
    request: Request,
    response: Response,
    versionCode: number,
    channel?: string,
  ): Promise<void> {
    const release = await this.releases.findRelease(channel ?? this.config.defaultChannel, versionCode);
    if (!release || !release.enabled) throw ApiErrors.notFound(4041, "版本不存在");
    this.apk.download(request, response, release);
  }
}
