import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { ApkService } from "./apk.service";
import { ReleaseAdminController } from "./release-admin.controller";
import { ReleaseController } from "./release.controller";
import { ReleaseRepository } from "./release.repository";
import { ReleaseService } from "./release.service";

/**
 * 热更新：客户端引导、安装包分发与发布管理。
 *
 * 导出 [ReleaseRepository]：全局的版本号响应头拦截器要用它取「当前全量可用的最高版本」。
 */
@Module({
  imports: [AdminAuthModule],
  controllers: [ReleaseController, ReleaseAdminController],
  providers: [ReleaseService, ReleaseRepository, ApkService],
  exports: [ReleaseRepository],
})
export class ReleaseModule {}
