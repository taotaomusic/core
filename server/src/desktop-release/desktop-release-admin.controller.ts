import { Body, Controller, Get, HttpCode, HttpStatus, Post, Query, Req, UseGuards } from "@nestjs/common";
import type { Request } from "express";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { AdminTokenGuard } from "../common/guards/admin-token.guard";
import { AppConfigService } from "../config/app-config.service";
import { DesktopArtifactService } from "./desktop-artifact.service";
import { DesktopReleaseRepository } from "./desktop-release.repository";
import { DesktopReleaseService, type DesktopManifestInput } from "./desktop-release.service";
import { DesktopMinVersionDto, DesktopRolloutDto } from "./dto/desktop-admin.dto";

/** Windows 发布管理；与 Android 共享管理令牌和灰度策略。 */
@Public()
@UseGuards(AdminTokenGuard)
@RateLimit("admin")
@Controller("desktop/admin")
export class DesktopReleaseAdminController {
  constructor(
    private readonly config: AppConfigService,
    private readonly repository: DesktopReleaseRepository,
    private readonly releases: DesktopReleaseService,
    private readonly artifacts: DesktopArtifactService,
  ) {}

  @Get("releases")
  list(@Query("channel") channel?: string, @Query("architecture") architecture?: string) {
    return this.repository.listReleases(
      channel?.trim() || this.config.defaultChannel,
      architecture?.trim().toLowerCase() || "windows-x64",
    );
  }

  /** 先按 sha256 上传单个模块；已存在的内容重复上传是幂等的。 */
  @Post("artifacts")
  @HttpCode(HttpStatus.CREATED)
  uploadArtifact(@Req() request: Request, @Query("sha256") sha256?: string) {
    return this.artifacts.storeUpload(request, sha256 ?? "", "object");
  }

  /** 全部模块上传后提交清单，服务端会同步预计算相邻版本差分。 */
  @Post("releases")
  @HttpCode(HttpStatus.CREATED)
  publish(@Body() body: DesktopManifestInput) {
    return this.releases.publish(body);
  }

  @Post("rollout")
  @HttpCode(HttpStatus.OK)
  async changeRollout(@Body() body: DesktopRolloutDto) {
    const channel = body.channel?.trim() || this.config.defaultChannel;
    const architecture = body.architecture?.trim().toLowerCase() || "windows-x64";
    const release = await this.repository.findRelease(channel, architecture, body.versionCode);
    if (!release) throw ApiErrors.notFound(4041, "桌面版本不存在");
    if (body.percent > 0 && !(await this.repository.hasJars(release.id))) {
      throw ApiErrors.conflict(4091, "桌面版本没有模块清单，不能开始放量");
    }
    await this.repository.updateRollout(channel, architecture, body.versionCode, body.percent, body.enabled);
    return this.repository.findRelease(channel, architecture, body.versionCode);
  }

  @Post("min-version")
  @HttpCode(HttpStatus.OK)
  async setMinVersion(@Body() body: DesktopMinVersionDto) {
    const channel = body.channel?.trim() || this.config.defaultChannel;
    const architecture = body.architecture?.trim().toLowerCase() || "windows-x64";
    const rescue = await this.releases.findRescueRelease(channel, architecture, body.versionCode);
    if (body.versionCode > 0 && !rescue) {
      throw ApiErrors.conflict(
        4091,
        `不存在放量 100% 且版本号不低于 ${body.versionCode} 的 Windows 发布`,
      );
    }
    await this.repository.setMinSupportedVersionCode(channel, body.versionCode);
    return {
      channel,
      architecture,
      minSupportedVersionCode: body.versionCode,
      rescueVersionCode: rescue?.version_code ?? null,
    };
  }
}
