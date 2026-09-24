import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { KuwoClient } from "./kuwo.client";
import { MusicSourceAccountRepository } from "./music-source-account.repository";
import { MusicSourceRegistry } from "./music-source.registry";
import { NeteaseClient } from "./netease.client";
import { TencentClient } from "./tencent.client";

/**
 * 上游适配层：把第三方接口的不一致（成功码、字段名、音质降级）收敛在这里。
 *
 * 导入 [AdminAuthModule] 是硬要求：音源账号的管理接口用 `AdminAuthGuard`，
 * 而守卫的依赖是在**声明 Controller 的模块**里解析的 —— 不导入会在启动时抛
 * `UnknownDependenciesException`，进程完全起不来。这条既不是类型错误，
 * 静态审计也看不出来（`ImageGenerationModule` 踩过）。
 */
@Module({
  imports: [AdminAuthModule],
  providers: [
    TencentClient,
    NeteaseClient,
    KuwoClient,
    MusicSourceAccountRepository,
    MusicSourceRegistry,
  ],
  exports: [
    TencentClient,
    NeteaseClient,
    KuwoClient,
    MusicSourceAccountRepository,
    MusicSourceRegistry,
  ],
})
export class UpstreamModule {}
