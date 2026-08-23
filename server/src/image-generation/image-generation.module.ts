import { Module } from "@nestjs/common";
import { ApiKeyRepository } from "./api-key.repository";
import { ImageGenerationClient } from "./image-generation.client";
import { ImageGenerationController } from "./image-generation.controller";
import { ImageTaskRepository } from "./image-task.repository";

/** gpt-image-2 图片生成模块。 */
@Module({
  controllers: [ImageGenerationController],
  providers: [ApiKeyRepository, ImageTaskRepository, ImageGenerationClient],
})
export class ImageGenerationModule {}
