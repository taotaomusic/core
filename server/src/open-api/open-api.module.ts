import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { MusicModule } from "../music/music.module";
import { UpstreamModule } from "../upstream/upstream.module";
import { ApiKeyGuard } from "./open-api-key.guard";
import { OpenApiController } from "./open-api.controller";
import { OpenApiKeyAdminController } from "./open-api-key-admin.controller";
import { OpenApiKeyRepository } from "./open-api-key.repository";
import { OpenApiKeyService } from "./open-api-key.service";

/**
 * 开放搜歌 API 模块。
 *
 * - [MusicModule]：SearchService / SongMapper（开放侧复用同一条上游搜索链路）。
 * - [UpstreamModule]：MusicSourceRegistry —— 开放控制器的 lyrics / link 直接用注册表，
 *   而 MusicModule 虽 imports 了 UpstreamModule 却未导出 registry，
 *   不直接导入会在解析 OpenApiController 构造器时抛 `UnknownDependenciesException`。
 *   与 SongShareModule 的做法一致。
 * - [AdminAuthModule]：管理控制器用 AdminAuthGuard / RolesGuard / AdminAuditService。
 *   依赖在**声明 Controller 的模块**里解析，漏导入同样会让进程起不来（AGENTS 硬约束）。
 *
 * 本模块不需要 exports：key 校验只发生在自己的守卫与控制器内部。
 */
@Module({
  imports: [MusicModule, UpstreamModule, AdminAuthModule],
  controllers: [OpenApiController, OpenApiKeyAdminController],
  providers: [OpenApiKeyRepository, OpenApiKeyService, ApiKeyGuard],
})
export class OpenApiModule {}
