import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { ApiKeyRepository } from "./api-key.repository";
import { ImageGenerationClient } from "./image-generation.client";
import { ImageGenerationController } from "./image-generation.controller";
import { ImageKeyAdminController } from "./image-key-admin.controller";
import { ImageTaskRepository } from "./image-task.repository";

/**
 * gpt-image-2 图片生成模块。
 *
 * 必须导入 [AdminAuthModule]：[ImageKeyAdminController] 用 [AdminAuthGuard]
 * 保护密钥管理接口，而守卫的依赖是在**声明它的模块**里解析的 —— 不导入就
 * 会在启动时抛 UnknownDependenciesException，整个进程起不来。
 */
@Module({
  imports: [AdminAuthModule],
  controllers: [ImageGenerationController, ImageKeyAdminController],
  providers: [ApiKeyRepository, ImageTaskRepository, ImageGenerationClient],
})
export class ImageGenerationModule {}
