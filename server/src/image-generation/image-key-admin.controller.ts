import {
  Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Req,
} from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { ApiKeyRepository } from "./api-key.repository";

const IMAGE_API_CHANNEL = "GPTIMAGE2";

/**
 * AI 密钥管理独立于 /draw，确保管理路径不会被图片接口前缀拼接。
 *
 * 密钥是花钱的东西：导入和删除都要求 `admin` 及以上，并留审计。审计里
 * **只记 Key 的标识与额度，绝不记明文** —— 明文只写服务端数据库，从不回传。
 *
 * 守卫逐个方法标注（`@AdminGuarded()`），不挂类上。详见 [AdminGuarded]。
 */
@Public()
@RateLimit("admin")
@Controller("app/admin/image-keys")
export class ImageKeyAdminController {
  constructor(
    private readonly apiKeys: ApiKeyRepository,
    private readonly audit: AdminAuditService,
  ) {}

  @AdminGuarded()
  @Get()
  @RequireRole(...READ_ROLES)
  listKeys() {
    return this.apiKeys.list(IMAGE_API_CHANNEL);
  }

  /** 导入或更新 Key 的可用次数；明文只写服务端数据库，从不回传。 */
  @AdminGuarded()
  @Post()
  @RequireRole(...WRITE_ROLES)
  async importKey(@Req() request: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const key = typeof body?.key === "string" ? body.key.trim() : "";
    const quota = Number(body?.quota);
    if (key.length < 8 || key.length > 512) throw ApiErrors.badRequest(4007, "图片 Key 长度不合法");
    if (!Number.isSafeInteger(quota) || quota < 0 || quota > 1_000_000) {
      throw ApiErrors.badRequest(4007, "额度须为 0 至 1000000 的整数");
    }
    const imported = await this.apiKeys.importKey(IMAGE_API_CHANNEL, key, quota);
    // 只记掩码后的 Key，明文连审计表也不落。
    await this.audit.record(request, "image_key.import", "image_key", String(imported.id), {
      channel: IMAGE_API_CHANNEL,
      quota,
      maskedKey: imported.maskedKey,
    });
    return imported;
  }

  @AdminGuarded()
  @Delete(":id")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.NO_CONTENT)
  async removeKey(@Req() request: AdminAuthenticatedRequest, @Param("id") rawId: string): Promise<void> {
    const id = Number(rawId);
    if (!Number.isSafeInteger(id) || id <= 0) throw ApiErrors.badRequest(4007, "图片 Key ID 不合法");
    if ((await this.apiKeys.remove(id, IMAGE_API_CHANNEL)) !== 1) throw ApiErrors.notFound(4042, "图片 Key 不存在或已有任务正在使用");
    await this.audit.record(request, "image_key.delete", "image_key", String(id), {
      channel: IMAGE_API_CHANNEL,
    });
  }
}
