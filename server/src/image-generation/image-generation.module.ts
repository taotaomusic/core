import { Module } from "@nestjs/common";
import { ImageGenerationClient } from "./image-generation.client";
import { ImageGenerationController } from "./image-generation.controller";

/** gpt-image-2 图片生成模块。 */
@Module({
  controllers: [ImageGenerationController],
  providers: [ImageGenerationClient],
})
export class ImageGenerationModule {}
