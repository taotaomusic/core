import { Body, Controller, Get, HttpCode, HttpStatus, Post, Req } from "@nestjs/common";
import { IsString, MaxLength } from "class-validator";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { PRIVILEGED_READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { AdminSettingKeys, AdminSettingRepository } from "./admin-setting.repository";

/** 更新一个密钥类设置。空串表示清空（关闭该项）。 */
class SettingValueDto {
  @IsString()
  @MaxLength(512)
  value: string;
}

/**
 * 管理端系统设置。
 *
 * 密钥类配置（如 GitHub webhook 验签密钥）从环境变量迁到这里，后台可改、可轮换，
 * 发版/轮换不必再碰服务器环境变量。读接口只回掩码，写接口不把明文写进审计。
 *
 * 守卫方式与其它管理控制器一致：类级 `@Public()` 跳过访问令牌，逐方法 `@AdminGuarded()`。
 */
@Public()
@RateLimit("admin")
@Controller("app/admin/settings")
export class AdminSettingController {
  constructor(
    private readonly settings: AdminSettingRepository,
    private readonly audit: AdminAuditService,
  ) {}

  /** 读所有已知设置的掩码摘要（永不回传明文）。 */
  @AdminGuarded()
  @Get()
  @RequireRole(...PRIVILEGED_READ_ROLES)
  async summary() {
    const githubWebhookSecret = await this.settings.getValue(AdminSettingKeys.githubWebhookSecret);
    return {
      githubWebhookSecret: {
        set: githubWebhookSecret.length > 0,
        masked: AdminSettingRepository.maskedOf(githubWebhookSecret),
      },
    };
  }

  @AdminGuarded()
  @Post("github-webhook-secret")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.OK)
  async setGithubWebhookSecret(@Req() request: AdminAuthenticatedRequest, @Body() body: SettingValueDto) {
    const value = body.value.trim();
    await this.settings.setValue(AdminSettingKeys.githubWebhookSecret, value);
    // 审计只记「改没改、改成空还是非空」，绝不记明文或掩码。
    await this.audit.record(request, "setting.update", "setting", AdminSettingKeys.githubWebhookSecret, {
      cleared: value.length === 0,
    });
    return { set: value.length > 0, masked: AdminSettingRepository.maskedOf(value) };
  }
}
