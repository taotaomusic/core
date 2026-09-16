import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, UseGuards } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { AdminAuthGuard } from "../admin-auth/admin-auth.guard";
import { ApiKeyRepository } from "./api-key.repository";

const IMAGE_API_CHANNEL = "GPTIMAGE2";

/** AI 密钥管理独立于 /draw，确保管理路径不会被图片接口前缀拼接。 */
@Public()
@UseGuards(AdminAuthGuard)
@RateLimit("admin")
@Controller("app/admin/image-keys")
export class ImageKeyAdminController {
  constructor(private readonly apiKeys: ApiKeyRepository) {}

  @Get()
  listKeys() {
    return this.apiKeys.list(IMAGE_API_CHANNEL);
  }

  /** 导入或更新 Key 的可用次数；明文只写服务端数据库，从不回传。 */
  @Post()
  async importKey(@Body() body: Record<string, unknown>) {
    const key = typeof body?.key === "string" ? body.key.trim() : "";
    const quota = Number(body?.quota);
    if (key.length < 8 || key.length > 512) throw ApiErrors.badRequest(4007, "图片 Key 长度不合法");
    if (!Number.isSafeInteger(quota) || quota < 0 || quota > 1_000_000) {
      throw ApiErrors.badRequest(4007, "额度须为 0 至 1000000 的整数");
    }
    return this.apiKeys.importKey(IMAGE_API_CHANNEL, key, quota);
  }

  @Delete(":id")
  @HttpCode(HttpStatus.NO_CONTENT)
  async removeKey(@Param("id") rawId: string): Promise<void> {
    const id = Number(rawId);
    if (!Number.isSafeInteger(id) || id <= 0) throw ApiErrors.badRequest(4007, "图片 Key ID 不合法");
    if ((await this.apiKeys.remove(id, IMAGE_API_CHANNEL)) !== 1) throw ApiErrors.notFound(4042, "图片 Key 不存在或已有任务正在使用");
  }
}
