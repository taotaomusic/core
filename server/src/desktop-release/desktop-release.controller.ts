import { Controller, Get, Head, Param, Query, Req, Res } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import { clientAddressOf, type SessionUser } from "../common/request.types";
import { AppConfigService } from "../config/app-config.service";
import { DesktopArtifactService } from "./desktop-artifact.service";
import { DesktopReleaseRepository } from "./desktop-release.repository";
import { DesktopReleaseService } from "./desktop-release.service";

const SHA256_PATTERN = /^[0-9a-f]{64}$/;

/** Windows 客户端公开的更新清单与内容寻址文件下载。 */
@Controller("desktop")
export class DesktopReleaseController {
  constructor(
    private readonly config: AppConfigService,
    private readonly repository: DesktopReleaseRepository,
    private readonly releases: DesktopReleaseService,
    private readonly artifacts: DesktopArtifactService,
  ) {}

  @Public()
  @RateLimit("app")
  @Get("bootstrap")
  async bootstrap(
    @Req() request: Request,
    @CurrentUser() user: SessionUser | undefined,
    @Query("channel") channel?: string,
    @Query("architecture") architecture?: string,
    @Query("versionCode") versionCode?: string,
    @Query("deviceId") deviceId?: string,
  ) {
    const installedVersion = Number(versionCode);
    if (!Number.isSafeInteger(installedVersion) || installedVersion <= 0) {
      throw ApiErrors.badRequest(4005, "versionCode 不合法");
    }
    const currentChannel = channel?.trim() || this.config.defaultChannel;
    const currentArchitecture = architecture?.trim().toLowerCase() || "windows-x64";
    const device = String(deviceId ?? "").slice(0, 64);
    const subject = user ? `user:${user.id}` : `device:${device || clientAddressOf(request)}`;
    const [update, config, configVersion] = await Promise.all([
      this.releases.resolveUpdate(
        request,
        currentChannel,
        currentArchitecture,
        installedVersion,
        subject,
      ),
      this.repository.listConfig(installedVersion),
      this.repository.configVersion(),
    ]);
    return {
      update,
      config: Object.fromEntries(config.map((item) => [item.key, item.value])),
      configVersion,
    };
  }

  @Public()
  @RateLimit("app")
  @RawResponse()
  @Get("artifacts/:sha256")
  downloadArtifact(
    @Req() request: Request,
    @Res() response: Response,
    @Param("sha256") sha256: string,
  ): void {
    this.serveArtifact(request, response, sha256);
  }

  @Public()
  @RateLimit("app")
  @RawResponse()
  @Head("artifacts/:sha256")
  headArtifact(
    @Req() request: Request,
    @Res() response: Response,
    @Param("sha256") sha256: string,
  ): void {
    this.serveArtifact(request, response, sha256);
  }

  @Public()
  @RateLimit("app")
  @RawResponse()
  @Get("patches/:sha256")
  downloadPatch(
    @Req() request: Request,
    @Res() response: Response,
    @Param("sha256") sha256: string,
  ): void {
    this.servePatch(request, response, sha256);
  }

  @Public()
  @RateLimit("app")
  @RawResponse()
  @Head("patches/:sha256")
  headPatch(
    @Req() request: Request,
    @Res() response: Response,
    @Param("sha256") sha256: string,
  ): void {
    this.servePatch(request, response, sha256);
  }

  private async serveArtifact(request: Request, response: Response, rawSha256: string): Promise<void> {
    const sha256 = this.sha256Of(rawSha256);
    const artifact = await this.repository.findDownloadableJar(sha256);
    if (!artifact) throw ApiErrors.notFound(4041, "桌面模块不存在");
    this.artifacts.download(request, response, artifact.artifact_file, artifact.sha256, `${sha256}.bin`);
  }

  private async servePatch(request: Request, response: Response, rawSha256: string): Promise<void> {
    const sha256 = this.sha256Of(rawSha256);
    const patch = await this.repository.findDownloadablePatch(sha256);
    if (!patch) throw ApiErrors.notFound(4041, "桌面差分不存在");
    this.artifacts.download(request, response, patch.patch_file, patch.sha256, `${sha256}.patch`);
  }

  private sha256Of(value: string): string {
    const sha256 = value.toLowerCase();
    if (!SHA256_PATTERN.test(sha256)) throw ApiErrors.badRequest(4005, "sha256 不合法");
    return sha256;
  }
}
