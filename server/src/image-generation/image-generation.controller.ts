import { Body, Controller, Get, HttpCode, HttpStatus, Param, Post } from "@nestjs/common";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { CreateImageDto } from "./dto/create-image.dto";
import { ImageGenerationClient } from "./image-generation.client";

/** 图片生成任务接口。默认受全局访问令牌守卫保护。 */
@Controller("draw")
export class ImageGenerationController {
  constructor(private readonly images: ImageGenerationClient) {}

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
}
