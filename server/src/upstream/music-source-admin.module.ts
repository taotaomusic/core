import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { MusicSourceAdminController } from "./music-source-admin.controller";
import { UpstreamModule } from "./upstream.module";

/**
 * 音源账号管理（后台）。
 *
 * 单独成模块而不是把控制器挂进 [UpstreamModule]：后者是**播放链路**的适配层，
 * 被 `MusicModule` 和 `SongShareModule` 依赖；把管理面混进去会让「依赖播放层」
 * 和「依赖管理面」两件事在模块图上再也分不开。
 *
 * 必须导入 [AdminAuthModule]：控制器用 `AdminAuthGuard`，而守卫的依赖是在
 * **声明 Controller 的模块**里解析的 —— 不导入会在启动时抛
 * `UnknownDependenciesException`，进程完全起不来。这条既不是类型错误，
 * 静态审计也看不出来（`ImageGenerationModule` 踩过）。
 */
@Module({
  imports: [AdminAuthModule, UpstreamModule],
  controllers: [MusicSourceAdminController],
})
export class MusicSourceAdminModule {}
