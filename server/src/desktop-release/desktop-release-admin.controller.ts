import { Body, Controller, Get, HttpCode, HttpStatus, Post, Query, Req } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { AppConfigService } from "../config/app-config.service";
import { DesktopArtifactService } from "./desktop-artifact.service";
import { DesktopReleaseRepository } from "./desktop-release.repository";
import { DesktopReleaseService, type DesktopManifestInput } from "./desktop-release.service";
import { DesktopMinVersionDto, DesktopRolloutDto } from "./dto/desktop-admin.dto";

/**
 * Windows 发布管理；与 Android 共享灰度策略与权限模型。
 *
 * 写操作（上传模块、提交清单、放量、抬下限）要求 `admin` 及以上，只读账号被拒。
 * 守卫逐个方法标注（`@AdminGuarded()`），不挂类上，避免将来新增公开路由时被
 * 类级守卫静默拦下。详见 [AdminGuarded]。
 */
@Public()
@RateLimit("admin")
@Controller("desktop/admin")
export class DesktopReleaseAdminController {
  constructor(
    private readonly config: AppConfigService,
    private readonly repository: DesktopReleaseRepository,
    private readonly releases: DesktopReleaseService,
    private readonly artifacts: DesktopArtifactService,
    private readonly audit: AdminAuditService,
  ) {}

  @AdminGuarded()
  @Get("releases")
  @RequireRole(...READ_ROLES)
  list(@Query("channel") channel?: string, @Query("architecture") architecture?: string) {
    return this.repository.listReleases(
      channel?.trim() || this.config.defaultChannel,
      architecture?.trim().toLowerCase() || "windows-x64",
    );
  }

  /** 先按 sha256 上传单个模块；已存在的内容重复上传是幂等的。 */
  @AdminGuarded()
  @Post("artifacts")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.CREATED)
  async uploadArtifact(@Req() request: AdminAuthenticatedRequest, @Query("sha256") sha256?: string) {
    const stored = await this.artifacts.storeUpload(request, sha256 ?? "", "object");
    await this.audit.record(request, "desktop.artifact_upload", "desktop_artifact", stored.sha256, {
      sha256: stored.sha256,
      size: stored.size,
    });
    return stored;
  }

  /** 全部模块上传后提交清单，服务端会同步预计算相邻版本差分。 */
  @AdminGuarded()
  @Post("releases")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.CREATED)
  async publish(@Req() request: AdminAuthenticatedRequest, @Body() body: DesktopManifestInput) {
    const published = await this.releases.publish(body);
    // 清单字段在校验前都是 unknown，审计只取落库后的真实值，避免记下用户原样传入的脏数据。
    await this.audit.record(request, "desktop.publish", "desktop_release",
      `${published.channel}#${published.architecture}#${published.version_code}`, {
        channel: published.channel,
        architecture: published.architecture,
        versionCode: published.version_code,
        versionName: published.version_name,
        fileCount: published.fileCount,
        totalSize: published.totalSize,
      });
    return published;
  }

  @AdminGuarded()
  @Post("rollout")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.OK)
  async changeRollout(@Req() request: AdminAuthenticatedRequest, @Body() body: DesktopRolloutDto) {
    const channel = body.channel?.trim() || this.config.defaultChannel;
    const architecture = body.architecture?.trim().toLowerCase() || "windows-x64";
    const release = await this.repository.findRelease(channel, architecture, body.versionCode);
    if (!release) throw ApiErrors.notFound(4041, "桌面版本不存在");
    if (body.percent > 0 && !(await this.repository.hasJars(release.id))) {
      throw ApiErrors.conflict(4091, "桌面版本没有模块清单，不能开始放量");
    }
    await this.repository.updateRollout(channel, architecture, body.versionCode, body.percent, body.enabled);
    await this.audit.record(request, "desktop.rollout", "desktop_release",
      `${channel}#${architecture}#${body.versionCode}`, {
        channel,
        architecture,
        versionCode: body.versionCode,
        percent: body.percent,
        enabled: body.enabled,
      });
    return this.repository.findRelease(channel, architecture, body.versionCode);
  }

  @AdminGuarded()
  @Post("min-version")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.OK)
  async setMinVersion(@Req() request: AdminAuthenticatedRequest, @Body() body: DesktopMinVersionDto) {
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
    await this.audit.record(request, "desktop.min_version", "desktop_release", `${channel}#${architecture}`, {
      channel,
      architecture,
      minSupportedVersionCode: body.versionCode,
      rescueVersionCode: rescue?.version_code ?? null,
    });
    return {
      channel,
      architecture,
      minSupportedVersionCode: body.versionCode,
      rescueVersionCode: rescue?.version_code ?? null,
    };
  }
}
