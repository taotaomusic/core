import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, UseGuards } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { AdminTokenGuard } from "../common/guards/admin-token.guard";
import { ApiKeyRepository } from "./api-key.repository";
import { CreateImageDto } from "./dto/create-image.dto";
import { ImageGenerationClient } from "./image-generation.client";

/** 与上游 gpt-image-2 任务使用的 Key 池名称保持一致。 */
const IMAGE_API_CHANNEL = "GPTIMAGE2";

/** 图片生成任务接口。默认受全局访问令牌守卫保护。 */
@Controller("draw")
export class ImageGenerationController {
  constructor(
    private readonly images: ImageGenerationClient,
    private readonly apiKeys: ApiKeyRepository,
  ) {}

  @Post("completions")
  @HttpCode(HttpStatus.OK)
  @RateLimit("image")
  create(@Body() body: CreateImageDto) {
    return this.images.createTask(body);
  }

  /** 客户端每隔约 3 秒调用一次，直到 state 变成 COMPLETED 或 FAILED。 */
  @Get("result/:taskId")
  @RateLimit("image-status")
  result(@Param("taskId") taskId: string) {
    return this.images.getTaskResult(taskId);
  }

  /** 后援团管理：只给管理员展示脱敏 Key 和可用额度。 */
  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Get("/app/admin/image-keys")
  listKeys() {
    return this.apiKeys.list(IMAGE_API_CHANNEL);
  }

  /** 导入或更新 Key 的可用次数；Key 只写到服务端数据库，不会返回客户端或后台页面。 */
  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Post("/app/admin/image-keys")
  async importKey(@Body() body: Record<string, unknown>) {
    const key = typeof body?.key === "string" ? body.key.trim() : "";
    const quota = Number(body?.quota);
    if (key.length < 8 || key.length > 512) throw ApiErrors.badRequest(4007, "图片 Key 长度不合法");
    if (!Number.isSafeInteger(quota) || quota < 0 || quota > 1_000_000) {
      throw ApiErrors.badRequest(4007, "额度须为 0 至 1000000 的整数");
    }
    return this.apiKeys.importKey(IMAGE_API_CHANNEL, key, quota);
  }

  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Delete("/app/admin/image-keys/:id")
  @HttpCode(HttpStatus.NO_CONTENT)
  async removeKey(@Param("id") rawId: string): Promise<void> {
    const id = Number(rawId);
    if (!Number.isSafeInteger(id) || id <= 0) throw ApiErrors.badRequest(4007, "图片 Key ID 不合法");
    if ((await this.apiKeys.remove(id, IMAGE_API_CHANNEL)) !== 1) throw ApiErrors.notFound(4042, "图片 Key 不存在或已有任务正在使用");
  }
}
